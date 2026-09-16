package com.magnity.viewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import com.magnity.viewer.ui.ViewerScreen
import com.magnity.viewer.ui.ViewerViewModel
import com.magnity.viewer.usb.MagDeviceWrapper

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "com.magnity.viewer.USB_PERMISSION"
    }

    private val vm: ViewerViewModel by viewModels()

    // throttle USB-permission dialogs so a device that re-enumerates in a loop can't
    // spam the prompt faster than once / 10 s
    private var lastPermReqMs = 0L

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
                        }
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == i.action) {
                    i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                        requestPermission(usb, it)
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == i.action) {
                    i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                        if (MagDeviceWrapper.isMagnity(it)) vm.disconnect()
                    }
                }
            }
        }
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
        val target = device?.takeIf { MagDeviceWrapper.isMagnity(it) }
            ?: usb.deviceList.values.firstOrNull { MagDeviceWrapper.isMagnity(it) }
        if (target != null) requestPermission(usb, target)
    }

    private fun requestPermission(usb: UsbManager, device: UsbDevice) {
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
