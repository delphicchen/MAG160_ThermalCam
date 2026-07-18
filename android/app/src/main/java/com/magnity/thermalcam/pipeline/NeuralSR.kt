package com.magnity.thermalcam.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * ESPCN thermal super-resolution inference — Kotlin port of enhance.py's NeuralSR.
 *
 * Backend priority on a Dimensity 9200-class SoC:
 *   1. NNAPI (MediaTek APU / GPU via the vendor NNAPI driver)
 *   2. XNNPACK (optimised Arm CPU kernels)
 *   3. ONNX Runtime default CPU EP
 *
 * The .onnx graph + .onnx.data external-weights sidecar are copied from APK assets to
 * the app files dir on first use (ONNX Runtime resolves the sidecar relative to the
 * model path, so both must be real files in the same directory).
 */
class NeuralSR(context: Context, val scale: Int) {

    companion object {
        private const val TAG = "NeuralSR"

        /** Where the on-device trainer (EspcnTrainer + OnnxExport) writes its model. */
        fun localModelFile(context: Context, scale: Int): File =
            File(File(context.filesDir, "models"), "local_thermal_espcn_${scale}x.onnx")
    }

    var backend: String = "none"; private set
    /** True when running a model trained on this device (preferred over the asset). */
    var isLocal = false; private set
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val local = localModelFile(context, scale)
        val modelPath = if (local.exists() && local.length() > 0) {
            isLocal = true
            local.absolutePath
        } else {
            val onnxName = "thermal_espcn_${scale}x.onnx"
            val model = File(modelDir, onnxName)
            val sidecar = File(modelDir, "$onnxName.data")
            copyAsset(context, onnxName, model)
            copyAsset(context, "$onnxName.data", sidecar)
            model.absolutePath
        }
        session = createSession(modelPath)
        if (isLocal) backend += " local"
        inputName = session.inputNames.iterator().next()
    }

    private fun copyAsset(context: Context, assetName: String, dst: File) {
        // re-copy when the packaged asset size differs (app update)
        val assetSize = runCatching {
            context.assets.openFd(assetName).use { it.length }
        }.getOrDefault(-1L)
        if (dst.exists() && assetSize == dst.length()) return
        context.assets.open(assetName).use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
    }

    private fun createSession(modelPath: String): OrtSession {
        // 1) NNAPI — APU/GPU acceleration on MediaTek
        try {
            val opts = OrtSession.SessionOptions()
            opts.addNnapi()
            val s = env.createSession(modelPath, opts)
            backend = "NNAPI"
            return s
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI EP unavailable, trying XNNPACK: ${e.message}")
        }
        // 2) XNNPACK — fast Arm CPU kernels
        try {
            val opts = OrtSession.SessionOptions()
            opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
            val s = env.createSession(modelPath, opts)
            backend = "XNNPACK"
            return s
        } catch (e: Exception) {
            Log.w(TAG, "XNNPACK EP unavailable, using default CPU: ${e.message}")
        }
        // 3) default CPU
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)
        val s = env.createSession(modelPath, opts)
        backend = "CPU"
        return s
    }

    /**
     * Upscale a single w*h frame by [scale]; values are preserved (normalised to [0,1]
     * for the NN, then rescaled back to counts).
     */
    fun upscale(frame: FloatArray, w: Int, h: Int): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in frame) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).coerceAtLeast(1e-6f)

        val normed = FloatBuffer.allocate(frame.size)
        for (v in frame) normed.put((v - lo) / span)
        normed.rewind()

        OnnxTensor.createTensor(env, normed, longArrayOf(1, 1, h.toLong(), w.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val outTensor = result[0] as OnnxTensor
                val fb = outTensor.floatBuffer
                val out = FloatArray(w * scale * h * scale)
                fb.get(out)
                for (i in out.indices) out[i] = out[i] * span + lo
                return out
            }
        }
    }

    fun close() {
        runCatching { session.close() }
    }
}
