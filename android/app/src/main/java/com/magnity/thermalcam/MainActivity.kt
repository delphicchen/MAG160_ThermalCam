package com.magnity.thermalcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.magnity.thermalcam.ui.ThermalScreen
import com.magnity.thermalcam.ui.ThermalViewModel
import com.magnity.thermalcam.usb.MagCamera

/**
 * Entry point: owns the USB attach/permission dance and the Storage Access Framework
 * pickers (DDT save/load); everything else lives in ThermalViewModel + ThermalScreen.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.magnity.thermalcam.USB_PERMISSION"
    }

    private val vm: ThermalViewModel by viewModels()

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (device != null && granted) vm.connect(usbManager, device)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && MagCamera.isMagnity(device)) vm.disconnect()
        }
    }

    // ---- camera permission for the RGB fusion overlay ----
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setFusion(true)
    }

    private fun onFusionToggle(on: Boolean) {
        if (!on) { vm.setFusion(false); return }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            vm.setFusion(true)
        } else {
            cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // ---- SAF pickers for DDT snapshots ----
    private val saveDdtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) contentResolver.openOutputStream(uri)?.use { vm.saveDdt(it) }
    }
    private val loadDdtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else "snapshot.ddt"
            } ?: "snapshot.ddt"
            contentResolver.openInputStream(uri)?.use { vm.loadDdt(it, name) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        // system broadcasts are delivered to NOT_EXPORTED receivers; the flag only
        // blocks other apps from sending us spoofed intents
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), RECEIVER_NOT_EXPORTED)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ThermalScreen(
                    vm = vm,
                    onSaveDdt = {
                        val name = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
                            .format(java.util.Date()) + ".ddt"
                        saveDdtLauncher.launch(name)
                    },
                    onLoadDdt = { loadDdtLauncher.launch(arrayOf("*/*")) },
                    onRetryConnect = { openCameraIfPresent() },
                    onFusionToggle = { onFusionToggle(it) },
                )
            }
        }

        // launched by USB_DEVICE_ATTACHED? then the extra carries the device (permission implicit)
        handleAttachIntent(intent)
        openCameraIfPresent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAttachIntent(intent)
    }

    private fun handleAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && MagCamera.isMagnity(device)) vm.connect(usbManager, device)
        }
    }

    /** Find an already-plugged camera and connect (asking USB permission if needed). */
    private fun openCameraIfPresent() {
        val device = usbManager.deviceList.values.firstOrNull { MagCamera.isMagnity(it) } ?: return
        if (usbManager.hasPermission(device)) {
            vm.connect(usbManager, device)
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, pi)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(detachReceiver)
        super.onDestroy()
    }
}
