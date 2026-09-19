package com.magnity.viewer.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone (RGB) camera source for the thermal/visible sensor-fusion overlay.
 *
 * Streams luma (Y-plane) frames from the back camera via CameraX ImageAnalysis at
 * ~VGA resolution — enough for the MSX-style edge overlay while keeping the per-frame
 * Sobel + warp cheap. Frames are delivered in SENSOR orientation (we deliberately do
 * NOT auto-rotate: the USB thermal camera is physically fixed relative to the phone,
 * so the rotation between the two sensors is a constant the user sets once in the
 * alignment controls, independent of how the phone is held).
 *
 * CameraX wants a LifecycleOwner; we drive a private LifecycleRegistry so the stream
 * follows the fusion toggle instead of the Activity. start()/stop() must be called
 * from the main thread.
 */
@OptIn(ExperimentalCamera2Interop::class)
class RgbCamera(private val context: Context) {

    companion object {
        private const val TAG = "RgbCamera"
        /** Settled focus readings kept for the median (~0.25 s at 30 fps). */
        private const val FOCUS_MEDIAN_N = 7
        /** Side of the centred AF window, as a fraction of the sensor array. */
        private const val AF_CENTER_FRAC = 0.2f
    }

    /** One luma frame in sensor orientation. `id` increments per frame (cache key). */
    class LumaFrame(val data: ByteArray, val width: Int, val height: Int, val id: Long)

    private class FusionLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val lifecycleOwner = FusionLifecycle()
    private var provider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private val frameId = AtomicLong(0)

    @Volatile private var latestFrame: LumaFrame? = null
    @Volatile var running = false; private set

    fun latest(): LumaFrame? = latestFrame

    // Focus-distance reporting, read for the fusion distance source. Calibration
    // level is a static lens characteristic (0 UNCALIBRATED / 1 APPROXIMATE /
    // 2 CALIBRATED); diopters is the median of the last settled LENS_FOCUS_DISTANCE
    // readings (1/m on a calibrated lens, 0 = ∞) — readings taken while AF is
    // sweeping the lens are dropped, that sweep is what made the value jump.
    @Volatile var focusCalibration: Int? = null; private set
    @Volatile var minFocusDiopters: Float? = null; private set
    @Volatile var focusDiopters: Float? = null; private set
    @Volatile var afActive = false; private set
    /** AF is searching right now; [focusDiopters] holds the last settled value. */
    @Volatile var afScanning = false; private set
    private val focusRing = FloatArray(FOCUS_MEDIAN_N)
    private var focusRingN = 0
    private var focusRingPos = 0

    /** Camera thread: keep settled readings only, publish their median. */
    private fun onFocusReading(d: Float?, af: Int?) {
        afActive = af != null && af != CaptureResult.CONTROL_AF_STATE_INACTIVE
        afScanning = af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                     af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        if (d == null || d < 0f || afScanning) return
        focusRing[focusRingPos] = d
        focusRingPos = (focusRingPos + 1) % FOCUS_MEDIAN_N
        if (focusRingN < FOCUS_MEDIAN_N) focusRingN++
        val sorted = focusRing.copyOf(focusRingN).also { it.sort() }
        focusDiopters = sorted[focusRingN / 2]
    }

    /** Bind the analysis stream. Main thread only; CAMERA permission must be granted. */
    fun start(onError: (String) -> Unit = {}) {
        if (running) return
        running = true
        lifecycleOwner.registry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val prov = future.get()
                provider = prov
                if (!running) return@addListener      // stopped while initialising

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                // Stabilisation shifts the image relative to the rigidly mounted thermal
                // camera, so ask for it off. A HAL may ignore the request. An
                // analysis-only use case may not enable continuous AF by itself, so
                // request it explicitly and read the lens focus distance per frame —
                // the fusion overlay uses it as the AUTO object distance.
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession, request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            onFocusReading(result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                                           result.get(CaptureResult.CONTROL_AF_STATE))
                        }
                    })
                val analysis = builder.build()

                val exec = Executors.newSingleThreadExecutor()
                analysisExecutor = exec
                analysis.setAnalyzer(exec) { proxy ->
                    try {
                        val plane = proxy.planes[0]           // Y plane, pixelStride 1
                        val w = proxy.width
                        val h = proxy.height
                        val rowStride = plane.rowStride
                        val buf = plane.buffer
                        val out = ByteArray(w * h)
                        if (rowStride == w) {
                            buf.get(out, 0, w * h)
                        } else {
                            for (y in 0 until h) {
                                buf.position(y * rowStride)
                                buf.get(out, y * w, w)
                            }
                        }
                        latestFrame = LumaFrame(out, w, h, frameId.incrementAndGet())
                    } catch (e: Exception) {
                        Log.w(TAG, "luma extract failed", e)
                    } finally {
                        proxy.close()
                    }
                }

                lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
                prov.unbind()      // nothing else of ours is bound, but be tidy
                val camera = prov.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                // Registration is only valid for one lens: hold the logical camera at 1x so
                // the HAL has no reason to hand over to the ultra-wide or telephoto.
                camera.cameraControl.setZoomRatio(1f)
                // Whether the focus distance above can be trusted: only APPROXIMATE
                // (1) or CALIBRATED (2) lenses report usable values.
                runCatching {
                    val info = Camera2CameraInfo.from(camera.cameraInfo)
                    focusCalibration = info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                    minFocusDiopters = info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    // Focus on the centre only: the thermal overlay is judged by what's in
                    // the middle, and a whole-frame AF keeps re-picking foreground/background.
                    val maxAf = info.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                    val arr = info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (maxAf > 0 && arr != null) {
                        val rw = (arr.width() * AF_CENTER_FRAC).toInt()
                        val rh = (arr.height() * AF_CENTER_FRAC).toInt()
                        val region = MeteringRectangle(arr.centerX() - rw / 2,
                            arr.centerY() - rh / 2, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX)
                        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
                            CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS,
                                                         arrayOf(region))
                                .build())
                    }
                }.onFailure { Log.w(TAG, "focus characteristics unavailable", it) }
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                running = false
                onError(e.message ?: "camera start failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbind and drop the stream. Main thread only. */
    fun stop() {
        running = false
        runCatching { provider?.unbindAll() }
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        analysisExecutor?.shutdown()
        analysisExecutor = null
        latestFrame = null
        focusDiopters = null
        afActive = false
        afScanning = false
        focusRingN = 0
        focusRingPos = 0
        focusCalibration = null
        minFocusDiopters = null
    }
}
