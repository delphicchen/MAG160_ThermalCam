package cn.com.magnity.sdk;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import cn.com.magnity.sdk.types.EnumerationInfo;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/* loaded from: classes.dex */
public class MagService {
    public static final int TYPE_NET = 0;
    public static final int TYPE_USB = 1;

    private static native boolean EnumCameras();

    private static native int GetTerminalCount();

    private static native int GetTerminalList(EnumerationInfo[] enumerationInfoArr);

    private static native boolean IsInitialized();

    public static boolean isInitialized() {
        return IsInitialized();
    }

    public static boolean enumCameras() {
        return EnumCameras();
    }

    public static int getDevices(Context ctx, int vendorId, int productId, EnumerationInfo[] infos) {
        UsbManager usbMgr;
        if (infos == null) {
            return -1;
        }
        int num = infos.length;
        int n1 = GetTerminalList(infos);
        if (n1 >= num) {
            n1 = num;
        }
        if (ctx != null && (usbMgr = (UsbManager) ctx.getSystemService("usb")) != null) {
            int i = n1;
            HashMap<String, UsbDevice> devices = usbMgr.getDeviceList();
            Iterator<Map.Entry<String, UsbDevice>> it = devices.entrySet().iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                Map.Entry<String, UsbDevice> entry = it.next();
                UsbDevice dev = entry.getValue();
                if (dev.getVendorId() == vendorId && dev.getProductId() == productId) {
                    EnumerationInfo info = new EnumerationInfo();
                    info.charCameraName = dev.getDeviceName();
                    info.intVersion = 0;
                    info.intCameraIpOrUsbId = dev.getDeviceId();
                    info.intControllerIp = 0;
                    info.charCameraMAC = "";
                    info.intCameraType = 1;
                    int i2 = i + 1;
                    infos[i] = info;
                    if (i2 == num) {
                        i = i2;
                        break;
                    }
                    i = i2;
                }
            }
            return i;
        }
        return n1;
    }

    static {
        System.loadLibrary("thermogroupsdk");
    }
}
