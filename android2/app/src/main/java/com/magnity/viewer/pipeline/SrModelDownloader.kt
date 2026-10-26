package com.magnity.viewer.pipeline

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Thermal SR models for frame sizes the APK does not ship (another camera's sensor),
 * fetched from the `sr-models` release on GitHub. `models.json` there lists the packages:
 *
 *   {"packages": [{"file": "thermal_ncnn_256x192.zip", "sizes": ["256x192", "192x256"],
 *                  "version": 1, "bytes": 1525216, "sha256": "…"}]}
 *
 * A package is the same zip "Load SR model" imports. It is installed next to whatever is
 * already imported ([SrModelFiles.install] with merge), so a model for another camera
 * stays; `downloaded.json` in the model dir remembers each package's version, so a newer
 * one can be offered. Blocking I/O — call off the main thread.
 */
object SrModelDownloader {
    const val BASE = "https://github.com/delphicchen/MAG160_ThermalCam/releases/download/sr-models/"
    /** A package's version 1 is the built-in weights renamed, which already run at any
     *  frame size — only a newer (retrained) version is worth offering. */
    const val BUILTIN_VERSION = 1
    private const val VERSIONS = "downloaded.json"
    private const val PROGRESS_EVERY_MS = 100L

    data class Package(
        val file: String, val sizes: List<String>, val version: Int,
        val bytes: Long, val sha256: String,
    )

    fun fetchManifest(): List<Package> {
        val text = get(BASE + "models.json").inputStream.use { it.readBytes().decodeToString() }
        val arr = JSONObject(text).getJSONArray("packages")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val sizes = o.getJSONArray("sizes")
            Package(
                file = o.getString("file"),
                sizes = (0 until sizes.length()).map { sizes.getString(it) },
                version = o.optInt("version", 1),
                bytes = o.optLong("bytes", 0),
                sha256 = o.getString("sha256").lowercase(),
            )
        }
    }

    /** Version of [file] as last downloaded into [dir]; 0 = never (or since replaced). */
    fun installedVersion(dir: File, file: String): Int =
        runCatching { JSONObject(File(dir, VERSIONS).readText()).optInt(file, 0) }.getOrDefault(0)

    /**
     * Download [pkg] (via [cacheDir]), check its sha256 and install it into the dir
     * [dirFor] gives its model size (see [SrModelFiles.install]). [onProgress] gets (bytes
     * done, bytes total — 0 when unknown), throttled. Throws [CancellationException] once
     * [isCancelled] turns true.
     */
    fun download(
        cacheDir: File, pkg: Package,
        onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean,
        dirFor: (SrTier) -> File,
    ): SrModelFiles.Installed {
        val tmp = File(cacheDir, "${pkg.file}.part")
        try {
            val conn = get(BASE + pkg.file)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: pkg.bytes
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (isCancelled()) throw CancellationException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_EVERY_MS) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            val sum = md.digest().joinToString("") { "%02x".format(it) }
            require(sum == pkg.sha256) { "checksum mismatch — download again" }
            val got = tmp.inputStream().use { SrModelFiles.install(it, merge = true, dirFor = dirFor) }
            val versions = runCatching { JSONObject(File(got.dir, VERSIONS).readText()) }
                .getOrDefault(JSONObject())
            File(got.dir, VERSIONS).writeText(versions.put(pkg.file, pkg.version).toString())
            return got
        } finally {
            tmp.delete()
        }
    }

    /** GET [url] (GitHub answers release downloads with a redirect, followed here). */
    private fun get(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MagViewer")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            error("HTTP $code")
        }
        return conn
    }
}
