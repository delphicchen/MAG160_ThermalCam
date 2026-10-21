package com.magnity.viewer.usb

import android.content.Context
import android.hardware.usb.UsbDevice

/**
 * A thermal camera on USB, as the viewer sees it: a stream of per-pixel temperature frames
 * in milli-Celsius ([getTemperatureData], row-major [width]×[height]) plus the few controls
 * the UI drives. [MagDeviceWrapper] (MAG160) and [Tiny2cSource] (InfiRay AC020) implement
 * it; [ThermalSources] picks one for an attached device.
 *
 * Threading is the implementation's business — frames arrive on an SDK thread and
 * [getTemperatureData] hands the newest one to the processing loop.
 */
interface ThermalSource {
    /** Frame geometry (valid after [open]). */
    val width: Int
    val height: Int
    val fps: Int
    /** Short id keeping per-camera settings apart ("mag160", "tiny2c"). */
    val kind: String
    /** Thermal lens focal length in native pixels — fusion's starting zoom. */
    val focalPx: Float
    /** Frames delivered since [start]; the loop watches it to spot a stalled stream. */
    val frameCount: Long

    /** Link and prepare the camera; [onCaliProgress] gets 0..100 when a download runs. */
    fun open(context: Context, onCaliProgress: (Int) -> Unit = {})
    fun start()
    fun stop()
    fun close()

    /** Latest frame in milli-Celsius, or null if none yet. */
    fun getTemperatureData(): IntArray?
    /** Shutter/housing temperature in milli-Celsius, when the camera reports one. */
    fun sensorTemp(): Int?
    fun setEmissivity(e: Float): Boolean
    fun triggerFfc(): Boolean
}

object ThermalSources {
    /** VID/PID pairs a Tiny2C has streamed from on this phone (the viewer persists them);
     *  treated like the built-in ids from then on. */
    @Volatile var learned: Set<Pair<Int, Int>> = emptySet()

    /** A camera the viewer connects to without asking first (known VID/PID). */
    fun isKnown(device: UsbDevice): Boolean =
        MagDeviceWrapper.isMagnity(device) || Tiny2cSource.isKnown(device) ||
            (device.vendorId to device.productId) in learned

    /** [isKnown], or a UVC device that may be a Tiny2C under another id — Android's USB
     *  permission dialog is the user's say before it is opened. */
    fun isCandidate(device: UsbDevice): Boolean = isKnown(device) || Tiny2cSource.isUvc(device)

    /** "0A0A:2602" — what the viewer shows and what a device filter needs. */
    fun idText(device: UsbDevice): String = "%04X:%04X".format(device.vendorId, device.productId)

    /** What the viewer takes [device] for, for the device list; null = not a camera. */
    fun label(device: UsbDevice): String? = when {
        MagDeviceWrapper.isMagnity(device) -> "MAG160"
        isKnown(device) -> "Tiny2C"
        Tiny2cSource.isUvc(device) -> "UVC"
        else -> null
    }

    /** The source for [device], reusing [current] when it is already the right kind. */
    fun forDevice(device: UsbDevice, current: ThermalSource?): ThermalSource = when {
        MagDeviceWrapper.isMagnity(device) -> current as? MagDeviceWrapper ?: MagDeviceWrapper()
        else -> (current as? Tiny2cSource)?.takeIf { it.device == device } ?: Tiny2cSource(device)
    }
}
