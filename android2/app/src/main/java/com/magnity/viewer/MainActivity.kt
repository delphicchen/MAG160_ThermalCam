package com.magnity.viewer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import com.magnity.viewer.ui.ViewerScreen
import com.magnity.viewer.ui.ViewerViewModel
import com.magnity.viewer.usb.ThermalSources
import com.magnity.viewer.usb.Tiny2cSource

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "com.magnity.viewer.USB_PERMISSION"
    }

    private val vm: ViewerViewModel by viewModels()

    // throttle USB-permission dialogs so a device that re-enumerates in a loop can't
    // spam the prompt faster than once / 10 s
    private var lastPermReqMs = 0L

    // unregistered in onDestroy — a leaked receiver keeps calling the previous
    // (cleared) ViewModel and races the new one for the camera on every plug event
    private var usbReceiver: BroadcastReceiver? = null

    // A UVC device (the Tiny2C) is granted to an app targeting P+ only while it holds
    // CAMERA (UsbManager.requestPermission docs); without it the request is refused with
    // no dialog at all. So ask for CAMERA first, then for the device.
    private var pendingUvc: UsbDevice? = null
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val dev = pendingUvc.also { pendingUvc = null } ?: return@registerForActivityResult
            if (ok) requestPermission(getSystemService(Context.USB_SERVICE) as UsbManager, dev)
            else vm.reportUsb(getString(R.string.st_uvc_camera_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (ACTION_USB_PERMISSION == i.action) {
                    synchronized(this) {
                        val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            && dev != null) {
                            vm.connect(dev, true)
                        } else if (dev != null && ThermalSources.isCandidate(dev)) {
                            vm.reportUsb(getString(R.string.st_usb_denied,
                                                   ThermalSources.idText(dev)))
                        }
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == i.action) {
                    i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        ?.takeIf { ThermalSources.isCandidate(it) }
                        ?.let { requestPermission(usb, it) }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == i.action) {
                    i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                        if (ThermalSources.isCandidate(it)) vm.disconnect()
                    }
                }
            }
        }
        usbReceiver = receiver
        // targetSdk 34+: custom-action receivers MUST declare export state,
        // otherwise SecurityException at registration = instant crash on launch.
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            ContextCompat.RECEIVER_EXPORTED      // protected system broadcast
        )
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_EXPORTED      // protected system broadcast
        )

        setContent { MaterialTheme(colorScheme = darkColorScheme()) { ViewerScreen() } }

        // launch-by-intent (device_filter auto start) or manual open of an attached cam
        val target = device?.takeIf { ThermalSources.isCandidate(it) }
            ?: usb.deviceList.values.firstOrNull { ThermalSources.isKnown(it) }
            ?: usb.deviceList.values.firstOrNull { ThermalSources.isCandidate(it) }
        if (target != null) requestPermission(usb, target)
    }

    override fun onDestroy() {
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbReceiver = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        vm.onAppVisible(true)
    }

    override fun onStop() {
        vm.onAppVisible(false)      // release the phone camera while in the background
        super.onStop()
    }

    private fun requestPermission(usb: UsbManager, device: UsbDevice) {
        if (Tiny2cSource.isUvc(device) && checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            if (pendingUvc != null) return                 // the CAMERA dialog is up
            pendingUvc = device
            vm.reportUsb(getString(R.string.st_uvc_needs_camera, ThermalSources.idText(device)))
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (usb.hasPermission(device)) {
            vm.connect(device, true)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastPermReqMs < 10_000) return
            lastPermReqMs = now
            usb.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    this, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE,
                )
            )
        }
    }
}
