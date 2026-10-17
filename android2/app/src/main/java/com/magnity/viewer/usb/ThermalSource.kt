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
    /** A camera the viewer connects to on its own (known VID/PID). */
    fun isKnown(device: UsbDevice): Boolean =
        MagDeviceWrapper.isMagnity(device) || Tiny2cSource.isKnown(device)

    /** [isKnown], or a UVC device that may be a Tiny2C under another id — tried on a
     *  manual Connect only, so a webcam never gets opened on its own. */
    fun isCandidate(device: UsbDevice): Boolean = isKnown(device) || Tiny2cSource.isUvc(device)

    /** The source for [device], reusing [current] when it is already the right kind. */
    fun forDevice(device: UsbDevice, current: ThermalSource?): ThermalSource = when {
        MagDeviceWrapper.isMagnity(device) -> current as? MagDeviceWrapper ?: MagDeviceWrapper()
        else -> (current as? Tiny2cSource)?.takeIf { it.device == device } ?: Tiny2cSource(device)
    }
}
