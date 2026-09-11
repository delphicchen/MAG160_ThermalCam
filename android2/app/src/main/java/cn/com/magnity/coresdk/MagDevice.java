package cn.com.magnity.coresdk;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import cn.com.magnity.coresdk.MagUsb;
import cn.com.magnity.coresdk.types.CameraInfo;
import cn.com.magnity.coresdk.types.CorrectionPara;
import cn.com.magnity.coresdk.types.DDTPara;
import cn.com.magnity.coresdk.types.EnumInfo;
import cn.com.magnity.coresdk.types.FilterPara;
import cn.com.magnity.coresdk.types.JRect;
import cn.com.magnity.coresdk.types.MDT;
import cn.com.magnity.coresdk.types.RemoteInfo;
import cn.com.magnity.coresdk.types.StatisticInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/* loaded from: classes.dex */
public class MagDevice implements MagUsb.IUSBCallback {
    public static final int CONN_DETACHED = -2;
    public static final int CONN_FAIL = -1;
    public static final int CONN_PENDING = 0;
    public static final int CONN_SUCC = 1;
    public static final int PREPARE_FAILED = -1;
    public static final int PREPARE_PENDING = 1;
    public static final int PREPARE_SUCC = 0;
    private int mChannel = -1;
    private Context mContext;
    private Handler mHadler;
    private Runnable mRunnable;
    private MagUsb mUsb;

    public static class BodyTempConvertMode {
        public static final int ConvertModeMagnity = 1;
        public static final int ConvertModeMagnity2 = 3;
        public static final int ConvertModeUnkown = 0;
        public static final int ConvertModeYuyue = 2;
    }

    public static class ColorPalette {
        public static final int PaletteAutumn = 5;
        public static final int PaletteBlue2Red = 12;
        public static final int PaletteGlowBow = 4;
        public static final int PaletteGray0to255 = 0;
        public static final int PaletteGray255to0 = 1;
        public static final int PaletteHighContrast = 10;
        public static final int PaletteHotMetal = 7;
        public static final int PaletteIronBow = 2;
        public static final int PaletteIronBow2 = 11;
        public static final int PaletteJet = 8;
        public static final int PaletteRainBow = 3;
        public static final int PaletteRedSaturation = 9;
        public static final int PaletteWinter = 6;
    }

    public static class ElectronicZoom {
        public static final int E16X = 4;
        public static final int E1X = 0;
        public static final int E2X = 1;
        public static final int E4X = 2;
        public static final int E8X = 3;
    }

    public interface ILinkCallback {
        void linkResult(int i);
    }

    public interface INewFrameCallback {
        void newFrame(int i, int i2);
    }

    public interface IPrepareTransferCallback {
        void prepareInBackground(int i, int i2);
    }

    public interface IStitchingCallback {
        void stitching(Bitmap bitmap, int i, int i2, int i3, int i4, int i5);
    }

    private native boolean ConvertVisCorr2IrCorr(int i, int i2, int i3, int i4, int i5, int i6, int i7, int[] iArr);

    private native void DislinkCamera(int i);

    private native int EstimateUnderArmTempFromForeheadRect(int i, int i2, int i3, int i4, int i5);

    private native int FindDetectTarget(int i, byte[] bArr, int i2, int i3, JRect[] jRectArr);

    private native int FixTemperature(int i, int i2, float f, int i3);

    private native int FixTemperature(int i, int i2, float f, int i3, int i4);

    private native boolean GetCameraInfo(int i, CameraInfo cameraInfo);

    private native int GetCameraTemperature(int i);

    private native int GetCurrentCameraInnerTemperature(int i);

    private native boolean GetDetectSuggestedParameter(int i, FilterPara filterPara);

    private native int GetEXLevel(int i);

    private native boolean GetEllipseTemperatureInfo(int i, int i2, int i3, int i4, int i5, int[] iArr);

    private native int GetEstimateUnderArmTempMode(int i);

    private native int GetEstimatedEnvTemp(int i);

    private native boolean GetFixPara(int i, CorrectionPara correctionPara);

    private native boolean GetFrameStatisticalData(int i, StatisticInfo statisticInfo);

    private native boolean GetLineTemperatureInfo(int i, int i2, int i3, int i4, int i5, int[] iArr);

    private native Bitmap GetOutputColorBarImage(int i);

    private native boolean GetOutputColorBarRGBAData(int i, int[] iArr, int i2);

    private native boolean GetOutputGrayData(int i, byte[] bArr);

    private native Bitmap GetOutputImage(int i);

    private native Bitmap GetOutputImage2(int i);

    private native boolean GetOutputRGBAData(int i, int[] iArr, int i2);

    private native boolean GetOutputRawData(int i, short[] sArr);

    private native boolean GetOutputYUVData(int i, byte[] bArr, int i2);

    private native boolean GetRectTemperatureInfo(int i, int i2, int i3, int i4, int i5, int[] iArr);

    private native boolean GetRemoteInfo(int i, RemoteInfo remoteInfo);

    private native boolean GetRgnTemperatureInfo(int i, int[] iArr, int[] iArr2);

    private native int GetSenorTemperature(int i);

    private native boolean GetTemperatureData(int i, int[] iArr, boolean z, boolean z2);

    private native int GetTemperatureProbe(int i, int i2, int i3);

    private native int GetTemperatureProbe(int i, int i2, int i3, int i4);

    private static native boolean Init(String str);

    private native boolean IsLinked(int i);

    private native boolean IsPause(int i);

    private native boolean IsProcessingImage(int i);

    private native int LinkCamera(int i);

    private native int LoadBufferedDDT(int i, int i2, byte[] bArr, INewFrameCallback iNewFrameCallback, DDTPara dDTPara);

    private native int LoadDDT(String str, int i, int i2, INewFrameCallback iNewFrameCallback, DDTPara dDTPara);

    private native void Lock(int i);

    private native void Pause(int i);

    private native int PrepareProcessImage(int i, IPrepareTransferCallback iPrepareTransferCallback);

    private native void Resume(int i);

    private native boolean SaveBMP(int i, int i2, String str);

    private native boolean SaveDDT(int i, String str);

    private native int SaveDDT2Buffer(int i, byte[] bArr);

    private native void SetAutoEnlargePara(int i, int i2, int i3, int i4);

    private native void SetColorPalette(int i, int i2);

    private native void SetDetailEnhancement(int i, int i2, boolean z);

    private native boolean SetDetectMaskPoints(int i, int[] iArr);

    private native boolean SetDetectParameter(int i, FilterPara filterPara);

    private native void SetEXLevel(int i, int i2, int i3, int i4);

    private native int SetEnvTempEstimateMode(int i, int i2, int i3);

    private native int SetEstimateUnderArmTempMode(int i, int i2);

    private native boolean SetFFCMode(int i, boolean z);

    private static native void SetFilter(int i);

    private native float SetFixPara(int i, CorrectionPara correctionPara);

    private native boolean SetImageTransform(int i, int i2, int i3);

    private native boolean SetIoAlarmState(int i, boolean z);

    private native void SetIsothermalPara(int i, int i2, int i3);

    private native boolean SetSubsectionEnlargePara(int i, int i2, int i3, int i4, int i5);

    private native boolean StartProcessImage(int i, INewFrameCallback iNewFrameCallback, int i2, int i3);

    private native boolean StartProcessImage_v2(int i, INewFrameCallback iNewFrameCallback, int i2, int i3, int i4, int i5);

    private native boolean StartStitching(int i, int i2, int i3, int i4, int i5, IStitchingCallback iStitchingCallback);

    private native void StopProcessImage(int i);

    private native void StopStitching(int i, boolean z);

    private native boolean TriggerFFC(int i);

    private native boolean TriggerStitching(int i);

    private native void UnloadDDT(int i);

    private native void Unlock(int i);

    private native boolean isStitching(int i);

    private static native boolean nativeBlendBitmap(Bitmap bitmap, Bitmap bitmap2, int i);

    private static native boolean nativeCopyBitmap(Bitmap bitmap, Bitmap bitmap2);

    private static native boolean nativeLoadMDT(String str, MDT mdt);

    private static native boolean nativeSaveMDT(MDT mdt, String str);

    public static void getDevices(Context ctx, int vendorId, int productId, ArrayList<EnumInfo> devices) {
        UsbManager usbMgr;
        if (ctx != null && devices != null && (usbMgr = (UsbManager) ctx.getSystemService("usb")) != null) {
            if (!devices.isEmpty()) {
                devices.clear();
            }
            HashMap<String, UsbDevice> devList = usbMgr.getDeviceList();
            for (Map.Entry<String, UsbDevice> entry : devList.entrySet()) {
                UsbDevice dev = entry.getValue();
                if (dev.getVendorId() == vendorId && dev.getProductId() == productId) {
                    EnumInfo info = new EnumInfo();
                    info.name = dev.getDeviceName();
                    info.id = dev.getDeviceId();
                    devices.add(info);
                }
            }
        }
    }

    public static boolean init(Context ctx) {
        File file;
        String state = Environment.getExternalStorageState();
        if ("mounted".equals(state) && (file = ctx.getExternalFilesDir("")) != null) {
            return Init(file.getAbsolutePath());
        }
        return false;
    }

    public static void setFilter(int filter) {
        SetFilter(filter);
    }

    @Override // cn.com.magnity.coresdk.MagUsb.IUSBCallback
    public void usbResult(Context ctx, int status, int fd, ILinkCallback cb) {
        switch (status) {
            case -4:
                dislinkCamera();
                if (cb != null) {
                    cb.linkResult(-2);
                    break;
                }
                break;
            case -2:
                dislinkCamera();
                if (cb != null) {
                    cb.linkResult(-1);
                    break;
                }
                break;
            case -1:
                this.mChannel = LinkCamera(fd);
                if (this.mChannel > 0) {
                    if (cb != null) {
                        cb.linkResult(1);
                        break;
                    }
                } else {
                    dislinkCamera();
                    if (cb != null) {
                        cb.linkResult(-1);
                        break;
                    }
                }
                break;
        }
    }

    private void postResult(final ILinkCallback cb, final int result) {
        this.mHadler = new Handler(Looper.getMainLooper());
        this.mRunnable = new Runnable() { // from class: cn.com.magnity.coresdk.MagDevice.1
            @Override // java.lang.Runnable
            public void run() {
                if (cb != null) {
                    cb.linkResult(result);
                }
            }
        };
        this.mHadler.postDelayed(this.mRunnable, 2L);
    }

    public int linkCamera(Context ctx, int id, ILinkCallback cb) {
        if (ctx == null || this.mUsb != null || this.mChannel > 0) {
            return -1;
        }
        this.mContext = ctx.getApplicationContext();
        this.mUsb = new MagUsb();
        this.mUsb.init(this.mContext);
        int r = this.mUsb.requestPermission(this.mContext, id, this, cb);
        if (r == -2) {
            dislinkCamera();
            postResult(cb, -1);
            return -1;
        }
        if (r == -3) {
            return 0;
        }
        this.mChannel = LinkCamera(r);
        if (this.mChannel < 0) {
            dislinkCamera();
            postResult(cb, -1);
            return -1;
        }
        postResult(cb, 1);
        return 1;
    }

    public void dislinkCamera() {
        if (this.mChannel > 0) {
            if (IsProcessingImage(this.mChannel)) {
                StopProcessImage(this.mChannel);
            }
            DislinkCamera(this.mChannel);
            this.mChannel = -1;
        }
        if (this.mUsb != null) {
            this.mUsb.exit(this.mContext);
            this.mUsb = null;
        }
        if (this.mHadler != null && this.mRunnable != null) {
            this.mHadler.removeCallbacks(this.mRunnable);
            this.mHadler = null;
            this.mRunnable = null;
        }
        this.mContext = null;
    }

    public boolean isLinked() {
        if (this.mChannel < 0) {
            return false;
        }
        return IsLinked(this.mChannel);
    }

    public boolean startProcessImage(INewFrameCallback cb, int colorBarW, int colorBarH) {
        if (this.mChannel < 0) {
            return false;
        }
        return StartProcessImage(this.mChannel, cb, colorBarW, colorBarH);
    }

    public boolean startProcessImage(INewFrameCallback cb, int colorBarW, int colorBarH, int videoWidth, int videoHeight) {
        if (this.mChannel < 0) {
            return false;
        }
        return StartProcessImage_v2(this.mChannel, cb, colorBarW, colorBarH, videoWidth, videoHeight);
    }

    public int prepareProcessImage(IPrepareTransferCallback cb) {
        if (this.mChannel < 0) {
            return -1;
        }
        return PrepareProcessImage(this.mChannel, cb);
    }

    public void stopProcessImage() {
        if (this.mChannel > 0 && IsProcessingImage(this.mChannel)) {
            StopProcessImage(this.mChannel);
        }
    }

    public boolean isProcessingImage() {
        if (this.mChannel < 0) {
            return false;
        }
        return IsProcessingImage(this.mChannel);
    }

    public void pause() {
        if (this.mChannel >= 0) {
            Pause(this.mChannel);
        }
    }

    public void resume() {
        if (this.mChannel >= 0) {
            Resume(this.mChannel);
        }
    }

    public boolean isPaused() {
        if (this.mChannel < 0) {
            return false;
        }
        return IsPause(this.mChannel);
    }

    public boolean getCameraInfo(CameraInfo info) {
        if (this.mChannel < 0 || info == null) {
            return false;
        }
        return GetCameraInfo(this.mChannel, info);
    }

    public boolean setIoAlarmState(boolean isAlarm) {
        if (this.mChannel < 0) {
            return false;
        }
        return SetIoAlarmState(this.mChannel, isAlarm);
    }

    public boolean triggrtFFC() {
        if (this.mChannel < 0) {
            return false;
        }
        return TriggerFFC(this.mChannel);
    }

    public boolean setFFCMode(boolean isManual) {
        if (this.mChannel < 0) {
            return false;
        }
        return SetFFCMode(this.mChannel, isManual);
    }

    public void setColorPalette(int palette) {
        if (this.mChannel > 0) {
            SetColorPalette(this.mChannel, palette);
        }
    }

    public boolean setSubsectionEnlargePara(int temp1, int temp2, int gray1, int gray2) {
        if (this.mChannel < 0) {
            return false;
        }
        return SetSubsectionEnlargePara(this.mChannel, temp1, temp2, gray1, gray2);
    }

    public void setAutoEnlargePara(int range, int brightness, int contrast) {
        if (this.mChannel > 0) {
            SetAutoEnlargePara(this.mChannel, range, brightness, contrast);
        }
    }

    public void setIsothermalPara(int temp1, int temp2) {
        if (this.mChannel > 0) {
            SetIsothermalPara(this.mChannel, temp1, temp2);
        }
    }

    public boolean setImageTransform(int flip, int rotate) {
        if (this.mChannel < 0) {
            return false;
        }
        return SetImageTransform(this.mChannel, flip, rotate);
    }

    public void setExLevel(int level, int x, int y) {
        if (this.mChannel > 0) {
            SetEXLevel(this.mChannel, level, x, y);
        }
    }

    public int getExLevel() {
        if (this.mChannel < 0) {
            return 0;
        }
        return GetEXLevel(this.mChannel);
    }

    public void setDetailEnhancement(int level, boolean simplified) {
        if (this.mChannel > 0) {
            SetDetailEnhancement(this.mChannel, level, simplified);
        }
    }

    public boolean getFixPara(CorrectionPara para) {
        if (this.mChannel < 0 || para == null) {
            return false;
        }
        return GetFixPara(this.mChannel, para);
    }

    public float setFixPara(CorrectionPara para) {
        if (this.mChannel < 0) {
            return 0.0f;
        }
        return SetFixPara(this.mChannel, para);
    }

    public Bitmap getOutputImage() {
        if (this.mChannel < 0) {
            return null;
        }
        return GetOutputImage(this.mChannel);
    }

    public Bitmap getOutputImage2() {
        if (this.mChannel < 0) {
            return null;
        }
        return GetOutputImage2(this.mChannel);
    }

    public boolean getOutputRGBAData(int[] data) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetOutputRGBAData(this.mChannel, data, 0);
    }

    public boolean getOutputGrayData(byte[] data) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetOutputGrayData(this.mChannel, data);
    }

    public boolean getOutputYUVData(byte[] data, int format) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetOutputYUVData(this.mChannel, data, format);
    }

    public Bitmap getOutputColorBarImage() {
        if (this.mChannel < 0) {
            return null;
        }
        return GetOutputColorBarImage(this.mChannel);
    }

    public boolean getOutputColorBarRGBA(int[] data) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetOutputColorBarRGBAData(this.mChannel, data, 0);
    }

    public boolean getOutputRawData(short[] data) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetOutputRawData(this.mChannel, data);
    }

    public boolean getFrameStatisticInfo(StatisticInfo info) {
        if (this.mChannel < 0 || info == null) {
            return false;
        }
        return GetFrameStatisticalData(this.mChannel, info);
    }

    public boolean getRemoteInfo(RemoteInfo info) {
        if (this.mChannel < 0 || info == null) {
            return false;
        }
        return GetRemoteInfo(this.mChannel, info);
    }

    public boolean getTemperatureData(int[] temp, boolean accurate, boolean enableCorrect) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetTemperatureData(this.mChannel, temp, accurate, enableCorrect);
    }

    public int fixTemperature(int t, float emissivity, int x, int y) {
        return this.mChannel < 0 ? t : FixTemperature(this.mChannel, t, emissivity, x, y);
    }

    public int fixTemperature(int t, float emissivity, int pos) {
        return this.mChannel < 0 ? t : FixTemperature(this.mChannel, t, emissivity, pos);
    }

    public int getTemperatureProbe(int x, int y, int size) {
        if (this.mChannel < 0) {
            return Integer.MIN_VALUE;
        }
        return GetTemperatureProbe(this.mChannel, x, y, size);
    }

    public int getTemperatureProbe(int pos, int size) {
        if (this.mChannel < 0) {
            return Integer.MIN_VALUE;
        }
        return GetTemperatureProbe(this.mChannel, pos, size);
    }

    public boolean getLineTemperatureInfo(int x0, int y0, int x1, int y1, int[] info) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetLineTemperatureInfo(this.mChannel, x0, y0, x1, y1, info);
    }

    public boolean getRectTemperatureInfo(int x0, int y0, int x1, int y1, int[] info) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetRectTemperatureInfo(this.mChannel, x0, y0, x1, y1, info);
    }

    public boolean getEllipseTemperatureInfo(int x0, int y0, int x1, int y1, int[] info) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetEllipseTemperatureInfo(this.mChannel, x0, y0, x1, y1, info);
    }

    public boolean getRgnTemperatureInfo(int[] pos, int[] info) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetRgnTemperatureInfo(this.mChannel, pos, info);
    }

    public boolean saveBMP(int index, String fileName) {
        if (this.mChannel < 0) {
            return false;
        }
        return SaveBMP(this.mChannel, index, fileName);
    }

    public boolean saveDDT(String fileName) {
        if (this.mChannel < 0) {
            return false;
        }
        return SaveDDT(this.mChannel, fileName);
    }

    public int saveDDT2Buffer(byte[] buffer) {
        if (this.mChannel < 0) {
            return -1;
        }
        return SaveDDT2Buffer(this.mChannel, buffer);
    }

    public boolean loadBufferedDDT(int colorbarWidth, int colorbarHeight, byte[] buffer, INewFrameCallback cb, DDTPara para) {
        if (isLinked()) {
            return false;
        }
        this.mChannel = LoadBufferedDDT(colorbarWidth, colorbarHeight, buffer, cb, para);
        return this.mChannel >= 0;
    }

    public boolean loadDDT(String filename, int colorbarWidth, int colorbarHeight, INewFrameCallback cb, DDTPara para) {
        if (isLinked()) {
            return false;
        }
        this.mChannel = LoadDDT(filename, colorbarWidth, colorbarHeight, cb, para);
        return this.mChannel >= 0;
    }

    public void unloadDDT() {
        if (this.mChannel > 0) {
            UnloadDDT(this.mChannel);
            this.mChannel = -1;
        }
    }

    public boolean saveMDT(MDT mdt, String fileName) {
        if (fileName.isEmpty()) {
            return false;
        }
        return nativeSaveMDT(mdt, fileName);
    }

    public boolean loadMDT(String fileName, MDT mdt) {
        if (fileName.isEmpty()) {
            return false;
        }
        return nativeLoadMDT(fileName, mdt);
    }

    public void lock() {
        if (this.mChannel > 0) {
            Lock(this.mChannel);
        }
    }

    public void unlock() {
        if (this.mChannel > 0) {
            Unlock(this.mChannel);
        }
    }

    public int getCurrentCameraInnerTemperature() {
        if (this.mChannel < 0) {
            return Integer.MIN_VALUE;
        }
        return GetCurrentCameraInnerTemperature(this.mChannel);
    }

    public int getCameraTemperature() {
        if (this.mChannel < 0) {
            return Integer.MIN_VALUE;
        }
        return GetCameraTemperature(this.mChannel);
    }

    public int getSenorTemperature() {
        if (this.mChannel < 0) {
            return Integer.MIN_VALUE;
        }
        return GetSenorTemperature(this.mChannel);
    }

    public static boolean copyBitmap(Bitmap dst, Bitmap src) {
        return nativeCopyBitmap(dst, src);
    }

    public static boolean blendBitmap(Bitmap dst, Bitmap src, int alpha) {
        return nativeBlendBitmap(dst, src, alpha);
    }

    public boolean startStitching(int width, int height, int focusLength, int pixelSize, IStitchingCallback cb) {
        if (this.mChannel < 0) {
            return false;
        }
        return StartStitching(this.mChannel, width, height, focusLength, pixelSize, cb);
    }

    public boolean triggerStitching() {
        if (this.mChannel < 0) {
            return false;
        }
        return TriggerStitching(this.mChannel);
    }

    public void stopStitching(boolean force) {
        if (this.mChannel > 0) {
            StopStitching(this.mChannel, force);
        }
    }

    public boolean isStitching() {
        if (this.mChannel < 0) {
            return false;
        }
        return isStitching(this.mChannel);
    }

    public boolean setDetectParameter(FilterPara filterPara) {
        if (this.mChannel < 0) {
            return false;
        }
        return SetDetectParameter(this.mChannel, filterPara);
    }

    public boolean getDetectSuggestedParameter(FilterPara filterPara) {
        if (this.mChannel < 0) {
            return false;
        }
        return GetDetectSuggestedParameter(this.mChannel, filterPara);
    }

    public boolean setDetectMaskPoints(int[] maskPoints) {
        if (this.mChannel < 0) {
            return false;
        }
        return SetDetectMaskPoints(this.mChannel, maskPoints);
    }

    public int findDetectTarget(byte[] data, int w, int h, JRect[] regions) {
        if (this.mChannel < 0) {
            return -1;
        }
        return FindDetectTarget(this.mChannel, data, w, h, regions);
    }

    public int estimateUnderArmTempFromForeheadRect(int x0, int y0, int x1, int y1) {
        if (this.mChannel < 0) {
            return 0;
        }
        return EstimateUnderArmTempFromForeheadRect(this.mChannel, x0, y0, x1, y1);
    }

    public int setEstimateUnderArmTempMode(int mode) {
        if (this.mChannel < 0) {
            return 0;
        }
        return SetEstimateUnderArmTempMode(this.mChannel, mode);
    }

    public int getEstimateUnderArmTempMode() {
        if (this.mChannel < 0) {
            return 0;
        }
        return GetEstimateUnderArmTempMode(this.mChannel);
    }

    public int getEstimatedEnvTemp() {
        if (this.mChannel < 0) {
            return 0;
        }
        return GetEstimatedEnvTemp(this.mChannel);
    }

    public int setEnvTempEstimateMode(int intTempOffset, int bApplyOffsetSimply) {
        if (this.mChannel < 0) {
            return 0;
        }
        return SetEnvTempEstimateMode(this.mChannel, intTempOffset, bApplyOffsetSimply);
    }

    public boolean convertVisCorr2IrCorr(int x, int y, int horOffset, int verOffset, int faceWidth, int faceHeight, int[] xy) {
        if (this.mChannel < 0) {
            return false;
        }
        return ConvertVisCorr2IrCorr(this.mChannel, x, y, horOffset, verOffset, faceWidth, faceHeight, xy);
    }

    static {
        System.loadLibrary("coresdk");
    }
}
