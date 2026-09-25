package com.magnity.viewer.pipeline

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Thermal super-resolution through ncnn + Vulkan (fp16).
 *
 * The model is trained by `sr_train/`. A model in the app's external files dir wins over
 * the one built into the APK (`assets/model/`), which is how you try a new model without
 * rebuilding: the drawer's "Load SR model" imports a package ([importPackage] — the zip
 * of `thermal_<w>x<h>_fp16.param/.bin` that sr_train's Colab export produces), or push
 * the files by hand:
 *
 *   adb push thermal_120x160_fp16.param /sdcard/Android/data/com.magnity.viewer/files/model/
 *   adb push thermal_120x160_fp16.bin   /sdcard/Android/data/com.magnity.viewer/files/model/
 *
 * One model per orientation, because the exported graph has a static input size — the
 * portrait default is 120×160, the unrotated frame is 160×120.
 *
 * Both model generations run: the first release is 3-channel (the grey field replicated
 * into R=G=B, the outputs averaged), the v2 recipe 1-channel. Which one is read from the
 * `.param` when the model loads ([ncnnParamInputChannels]).
 *
 * Like [Anime4kGpu], inference runs on whatever thread calls [render]; ncnn has no
 * per-thread context of its own, and the processing loop is the only caller.
 */
class NcnnUpscaler private constructor() : AutoCloseable {

    private var handle = 0L
    var vulkan = false; private set
    /** 1 or 3 — how many copies of the grey field the network takes. */
    var inChannels = 3; private set
    /** Loaded from the external files dir (an imported or pushed model), not the APK. */
    var fromFiles = false; private set
    private val timings = FloatArray(3)
    private var out = IntArray(0)

    companion object {
        private const val TAG = "MagViewer"

        init {
            System.loadLibrary("magviewer_ncnn")
        }

        fun modelDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "model")

        /** Input sizes ("120x160", …) with a complete model in the files dir. */
        fun importedSizes(ctx: Context): List<String> = SrModelFiles.importedSizes(modelDir(ctx))

        /** Install a model package into [modelDir]; see [SrModelFiles.install]. */
        fun importPackage(ctx: Context, input: InputStream): List<String> =
            SrModelFiles.install(modelDir(ctx), input)

        /** Drop the imported model; the APK's own takes over again. */
        fun removeImported(ctx: Context) {
            modelDir(ctx).deleteRecursively()
        }

        /** `<files>/model/thermal_<w>x<h>_fp16.{param,bin}` for this input size. */
        fun modelFiles(ctx: Context, w: Int, h: Int): Pair<File, File> {
            val dir = modelDir(ctx)
            return File(dir, "thermal_${w}x${h}_fp16.param") to
                File(dir, "thermal_${w}x${h}_fp16.bin")
        }

        private fun assetNames(w: Int, h: Int) =
            "model/thermal_${w}x${h}_fp16.param" to "model/thermal_${w}x${h}_fp16.bin"

        private fun inAssets(ctx: Context, w: Int, h: Int): Boolean {
            val (p, _) = assetNames(w, h)
            return runCatching { ctx.assets.open(p).close() }.isSuccess
        }

        fun isInstalled(ctx: Context, w: Int, h: Int): Boolean {
            val (p, b) = modelFiles(ctx, w, h)
            return (p.isFile && b.isFile) || inAssets(ctx, w, h)
        }

        /**
         * Null when no model is installed for this frame size. A file pushed into the
         * external dir wins over a bundled asset, so a bundled model can be overridden
         * for testing without rebuilding.
         */
        fun open(ctx: Context, w: Int, h: Int): NcnnUpscaler? {
            val (p, b) = modelFiles(ctx, w, h)
            val fromFiles = p.isFile && b.isFile
            if (!fromFiles && !inAssets(ctx, w, h)) {
                Log.i(TAG, "no ncnn model for ${w}x$h in assets or ${modelDir(ctx)}")
                return null
            }
            val u = NcnnUpscaler()
            u.vulkan = u.nativeGpuAvailable()
            u.handle = if (fromFiles) {
                u.nativeInit(p.absolutePath, b.absolutePath, u.vulkan)
            } else {
                val (ap, ab) = assetNames(w, h)
                u.nativeInitAsset(ctx.assets, ap, ab, u.vulkan)
            }
            if (u.handle == 0L) {
                Log.e(TAG, "ncnn model failed to load")
                return null
            }
            val param = runCatching {
                if (fromFiles) p.readText()
                else ctx.assets.open(assetNames(w, h).first).use { it.readBytes().decodeToString() }
            }.getOrDefault("")
            u.inChannels = ncnnParamInputChannels(param)
            u.fromFiles = fromFiles
            Log.i(TAG, "ncnn model ${w}x$h: ${u.inChannels}-channel input")
            return u
        }
    }

    private external fun nativeInit(param: String, bin: String, useVulkan: Boolean): Long
    private external fun nativeInitAsset(
        assets: android.content.res.AssetManager, param: String, bin: String,
        useVulkan: Boolean,
    ): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeTimings(handle: Long, out: FloatArray)
    private external fun nativeGpuAvailable(): Boolean
    private external fun nativeRun(
        handle: Long, field: FloatArray, w: Int, h: Int, lo: Float, hi: Float,
        lut: IntArray, inChannels: Int, out: IntArray,
    ): Boolean

    /** Scalar field in, palette-mapped 4× pixels out — this object's own buffer, valid
     *  until the next call (see [ArgbImage]). Throws if inference fails. */
    fun render(field: FloatArray, w: Int, h: Int, lo: Float, hi: Float, lut: IntArray): ArgbImage {
        val ow = w * 4
        val oh = h * 4
        if (out.size != ow * oh) out = IntArray(ow * oh)
        check(nativeRun(handle, field, w, h, lo, hi, lut, inChannels, out)) {
            "ncnn inference failed"
        }
        return ArgbImage(out, ow, oh)
    }

    /** The last [render]'s split in ms: input fill, network, palette + copy-out. */
    fun lastTimings(): FloatArray {
        if (handle != 0L) nativeTimings(handle, timings)
        return timings
    }

    override fun close() {
        if (handle != 0L) nativeRelease(handle)
    }
}

/**
 * Input channel count of an ncnn model, from its `.param` text: the first Convolution's
 * weight count (`6=`) over its output channels (`0=`) and kernel area (`1=` × `11=`). For
 * SRVGGNetCompact that is 1728 / (64·3·3) = 3 for the first release and 576 → 1 for the
 * v2 recipe. Anything unreadable counts as 3, the original contract.
 */
internal fun ncnnParamInputChannels(param: String): Int {
    val line = param.lineSequence().firstOrNull { it.startsWith("Convolution") } ?: return 3
    val kv = line.split(Regex("\\s+"))
        .mapNotNull { t -> t.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()
    val outCh = kv["0"]?.toIntOrNull()?.takeIf { it > 0 } ?: return 3
    val kw = kv["1"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
    val kh = kv["11"]?.toIntOrNull()?.takeIf { it > 0 } ?: kw
    val weights = kv["6"]?.toIntOrNull() ?: return 3
    val c = weights / (outCh * kw * kh)
    return if (c == 1 || c == 3) c else 3
}

/**
 * Model files in the app's external dir: which sizes are complete, and installing a model
 * package. Kept apart from [NcnnUpscaler] so it carries no native library.
 */
object SrModelFiles {
    private val MODEL_FILE = Regex("""thermal_(\d+)x(\d+)_fp16\.(param|bin)""")
    private const val PACKAGE_MAX_BYTES = 64L shl 20
    /** First line of every ncnn .param. */
    private const val NCNN_PARAM_MAGIC = "7767517"

    /** Input sizes ("120x160", …) with a complete model in [dir]. */
    fun importedSizes(dir: File): List<String> {
        val names = dir.list()?.toSet() ?: return emptyList()
        return names.mapNotNull { MODEL_FILE.matchEntire(it) }
            .map { "${it.groupValues[1]}x${it.groupValues[2]}" }.distinct()
            .filter { "thermal_${it}_fp16.param" in names && "thermal_${it}_fp16.bin" in names }
            .sorted()
    }

    /**
     * Install a model package — a zip of `thermal_<w>x<h>_fp16.param/.bin` pairs — into
     * [dir] (staged next to it), replacing whatever was imported before. Folders inside
     * the zip are ignored (entries are matched by file name only, so nothing lands
     * outside [dir]), and nothing is replaced unless every size in the package has both
     * files and a real ncnn .param. Returns the sizes installed; throws with a readable
     * message. Blocking I/O — call off the main thread.
     */
    fun install(dir: File, input: InputStream): List<String> {
        val staging = File(dir.parentFile, "model.import").apply { deleteRecursively(); mkdirs() }
        try {
            var total = 0L
            val buf = ByteArray(1 shl 16)
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.substringAfterLast('/').substringAfterLast('\\')
                    if (e.isDirectory || !MODEL_FILE.matches(name)) continue
                    File(staging, name).outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= PACKAGE_MAX_BYTES) { "package is larger than 64 MB" }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val files = staging.list()?.toSet().orEmpty()
            val sizes = files.mapNotNull { MODEL_FILE.matchEntire(it) }
                .map { "${it.groupValues[1]}x${it.groupValues[2]}" }.distinct().sorted()
            require(sizes.isNotEmpty()) { "no thermal_<w>x<h>_fp16.param/.bin in the package" }
            for (sz in sizes) {
                val p = File(staging, "thermal_${sz}_fp16.param")
                require(p.isFile && File(staging, "thermal_${sz}_fp16.bin").isFile) {
                    "thermal_$sz: the .param and the .bin must both be in the package"
                }
                require(p.bufferedReader().use { it.readLine()?.trim() } == NCNN_PARAM_MAGIC) {
                    "thermal_${sz}_fp16.param is not an ncnn model"
                }
            }
            dir.deleteRecursively()
            check(staging.renameTo(dir)) { "could not install into $dir" }
            return sizes
        } finally {
            staging.deleteRecursively()          // no-op after a successful rename
        }
    }
}
