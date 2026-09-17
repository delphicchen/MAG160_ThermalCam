package com.magnity.viewer.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
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

    companion object { private const val TAG = "RgbCamera" }

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
    // 2 CALIBRATED); diopters is the latest LENS_FOCUS_DISTANCE (1/m, 0 = ∞).
    @Volatile var focusCalibration: Int? = null; private set
    @Volatile var minFocusDiopters: Float? = null; private set
    @Volatile var focusDiopters: Float? = null; private set
    @Volatile var afActive = false; private set

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
                            focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                            val af = result.get(CaptureResult.CONTROL_AF_STATE)
                            afActive = af != null && af != CaptureResult.CONTROL_AF_STATE_INACTIVE
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
        focusCalibration = null
        minFocusDiopters = null
    }
}
