package cn.com.magnity.coresdk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import cn.com.magnity.coresdk.MagDevice;
import java.util.HashMap;
import java.util.Map;

/* loaded from: classes.dex */
class MagUsb {
    private static final String ACTION_USB_PERMISSION = "cn.com.magnity.sdk.USB_PERMISSION";
    static final int USB_DETACHED = -4;
    static final int USB_OPEN_FAIL = -2;
    static final int USB_OPEN_SUCC = -1;
    static final int USB_PENDING = -3;
    private MagDevice.ILinkCallback mConnCallback;
    private IntentFilter mIntentFilter;
    private IUSBCallback mUsbCallback;
    private UsbDeviceConnection mUsbHandle;
    private UsbReceiver mUsbReceiver;

    interface IUSBCallback {
        void usbResult(Context context, int i, int i2, MagDevice.ILinkCallback iLinkCallback);
    }

    MagUsb() {
    }

    void init(Context ctx) {
        if (ctx != null) {
            Context appCtx = ctx.getApplicationContext();
            if (this.mIntentFilter == null) {
                this.mUsbReceiver = new UsbReceiver();
                this.mIntentFilter = new IntentFilter();
                this.mIntentFilter.addAction(ACTION_USB_PERMISSION);
                this.mIntentFilter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
                this.mIntentFilter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
            }
            // Patched: targetSdk >= 34 requires an export flag when the filter
            // carries a non-system action (ACTION_USB_PERMISSION below), else
            // registerReceiver throws SecurityException.
            appCtx.registerReceiver(this.mUsbReceiver, this.mIntentFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }

    void exit(Context ctx) {
        if (ctx != null) {
            Context appCtx = ctx.getApplicationContext();
            appCtx.unregisterReceiver(this.mUsbReceiver);
            this.mIntentFilter = null;
            this.mUsbReceiver = null;
            if (this.mUsbHandle != null) {
                this.mUsbHandle.close();
                this.mUsbHandle = null;
            }
            this.mUsbCallback = null;
            this.mConnCallback = null;
        }
    }

    int requestPermission(Context ctx, int id, IUSBCallback cb, MagDevice.ILinkCallback connCb) {
        if (ctx == null) {
            return -2;
        }
        Context appCtx = ctx.getApplicationContext();
        UsbManager usbMgr = (UsbManager) appCtx.getSystemService("usb");
        UsbDevice dev = findDevice(usbMgr, id);
        if (dev == null) {
            return -2;
        }
        this.mUsbCallback = cb;
        this.mConnCallback = connCb;
        if (usbMgr.hasPermission(dev)) {
            this.mUsbHandle = usbMgr.openDevice(dev);
            if (this.mUsbHandle != null) {
                return this.mUsbHandle.getFileDescriptor();
            }
            return -2;
        }
        if (cb == null) {
            return -2;
        }
        // Patched: targetSdk >= 31 requires an explicit mutability flag, and the
        // USB permission broadcast must be mutable so the framework can fill in
        // EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED.
        PendingIntent peddingIntent = PendingIntent.getBroadcast(appCtx, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(appCtx.getPackageName()),
                PendingIntent.FLAG_MUTABLE);
        usbMgr.requestPermission(dev, peddingIntent);
        return USB_PENDING;
    }

    private UsbDevice findDevice(UsbManager usbMgr, int id) {
        HashMap<String, UsbDevice> devices = usbMgr.getDeviceList();
        for (Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
            UsbDevice dev = entry.getValue();
            if (dev.getDeviceId() == id) {
                return dev;
            }
        }
        return null;
    }

    private final class UsbReceiver extends BroadcastReceiver {
        private UsbReceiver() {
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            char c = 0;
            try {
                String action = intent.getAction();
                switch (action.hashCode()) {
                    case -2114103349:
                        if (action.equals("android.hardware.usb.action.USB_DEVICE_ATTACHED")) {
                            c = 1;
                            break;
                        }
                        c = 65535;
                        break;
                    case -1608292967:
                        if (action.equals("android.hardware.usb.action.USB_DEVICE_DETACHED")) {
                            c = 2;
                            break;
                        }
                        c = 65535;
                        break;
                    case 908404393:
                        if (action.equals(MagUsb.ACTION_USB_PERMISSION)) {
                            break;
                        }
                        c = 65535;
                        break;
                    default:
                        c = 65535;
                        break;
                }
                switch (c) {
                    case 0:
                        if (!intent.getBooleanExtra("permission", false)) {
                            if (MagUsb.this.mUsbCallback != null) {
                                MagUsb.this.mUsbCallback.usbResult(context.getApplicationContext(), -2, -1, MagUsb.this.mConnCallback);
                                break;
                            }
                        } else {
                            UsbDevice dev = (UsbDevice) intent.getParcelableExtra("device");
                            if (dev == null) {
                                if (MagUsb.this.mUsbCallback != null) {
                                    MagUsb.this.mUsbCallback.usbResult(context.getApplicationContext(), -2, -1, MagUsb.this.mConnCallback);
                                    break;
                                }
                            } else {
                                UsbManager usbMgr = (UsbManager) context.getSystemService("usb");
                                MagUsb.this.mUsbHandle = usbMgr.openDevice(dev);
                                if (MagUsb.this.mUsbCallback != null) {
                                    if (MagUsb.this.mUsbHandle != null) {
                                        MagUsb.this.mUsbCallback.usbResult(context.getApplicationContext(), -1, MagUsb.this.mUsbHandle.getFileDescriptor(), MagUsb.this.mConnCallback);
                                        break;
                                    } else {
                                        MagUsb.this.mUsbCallback.usbResult(context.getApplicationContext(), -2, -1, MagUsb.this.mConnCallback);
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                    case 2:
                        UsbDevice dev2 = (UsbDevice) intent.getParcelableExtra("device");
                        if (dev2 != null && dev2.getProductId() == 1 && dev2.getVendorId() == 33596 && MagUsb.this.mUsbCallback != null) {
                            MagUsb.this.mUsbCallback.usbResult(context.getApplicationContext(), MagUsb.USB_DETACHED, -1, MagUsb.this.mConnCallback);
                            break;
                        }
                        break;
                }
            } catch (Exception e) {
            }
        }
    }
}
