// ncnn + Vulkan bridge for the thermal super-resolution model.
//
// Same contract as the Anime4K path: in goes the scalar temperature field (before
// colouring), out comes palette-mapped ARGB at 4x. Doing the normalisation, the network
// and the palette lookup in one call keeps the per-frame JNI traffic to two arrays.
//
// The model is SRVGGNetCompact x4 (see sr_train/), values in 0..1. The first release is
// 3-channel: the grey field is replicated across RGB on the way in and the channels are
// averaged on the way out. The v2 recipe is 1-channel and takes the field as-is. The
// Kotlin side reads which from the .param and passes it in; the output side simply
// averages however many channels come back.

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <vector>

#include "net.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MagViewerNcnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MagViewerNcnn", __VA_ARGS__)

namespace {

struct Session {
    ncnn::Net net;
    bool vulkan = false;
    // last nativeRun split, ms: input fill, network (input + extract), palette + copy-out
    float prepMs = 0.f, netMs = 0.f, postMs = 0.f;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_magnity_viewer_pipeline_NcnnUpscaler_nativeInit(
        JNIEnv* env, jobject, jstring jparam, jstring jbin, jboolean useVulkan) {
    const char* param = env->GetStringUTFChars(jparam, nullptr);
    const char* bin = env->GetStringUTFChars(jbin, nullptr);

    auto* s = new Session();
    // fp16 storage/arithmetic is what the exported model was verified against; packing
    // and winograd are ncnn's own speed options and do not change the graph.
    s->net.opt.use_vulkan_compute = useVulkan == JNI_TRUE;
    s->net.opt.use_fp16_packed = true;
    s->net.opt.use_fp16_storage = true;
    s->net.opt.use_fp16_arithmetic = true;
    s->net.opt.num_threads = 4;

    int rc = s->net.load_param(param);
    if (rc != 0) {
        LOGE("load_param(%s) failed: %d", param, rc);
        delete s;
        s = nullptr;
    } else if ((rc = s->net.load_model(bin)) != 0) {
        LOGE("load_model(%s) failed: %d", bin, rc);
        delete s;
        s = nullptr;
    } else {
        s->vulkan = s->net.opt.use_vulkan_compute;
        LOGI("model loaded (%s), vulkan=%d", param, (int)s->vulkan);
    }

    env->ReleaseStringUTFChars(jparam, param);
    env->ReleaseStringUTFChars(jbin, bin);
    return reinterpret_cast<jlong>(s);
}

/** Same, but reading the model straight out of the APK's assets. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_magnity_viewer_pipeline_NcnnUpscaler_nativeInitAsset(
        JNIEnv* env, jobject, jobject assetManager, jstring jparam, jstring jbin,
        jboolean useVulkan) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }
    const char* param = env->GetStringUTFChars(jparam, nullptr);
    const char* bin = env->GetStringUTFChars(jbin, nullptr);

    auto* s = new Session();
    s->net.opt.use_vulkan_compute = useVulkan == JNI_TRUE;
    s->net.opt.use_fp16_packed = true;
    s->net.opt.use_fp16_storage = true;
    s->net.opt.use_fp16_arithmetic = true;
    s->net.opt.num_threads = 4;

    int rc = s->net.load_param(mgr, param);
    if (rc != 0) {
        LOGE("asset load_param(%s) failed: %d", param, rc);
        delete s;
        s = nullptr;
    } else if ((rc = s->net.load_model(mgr, bin)) != 0) {
        LOGE("asset load_model(%s) failed: %d", bin, rc);
        delete s;
        s = nullptr;
    } else {
        s->vulkan = s->net.opt.use_vulkan_compute;
        LOGI("model loaded from assets (%s), vulkan=%d", param, (int)s->vulkan);
    }

    env->ReleaseStringUTFChars(jparam, param);
    env->ReleaseStringUTFChars(jbin, bin);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magnity_viewer_pipeline_NcnnUpscaler_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_magnity_viewer_pipeline_NcnnUpscaler_nativeGpuAvailable(JNIEnv*, jobject) {
    return ncnn::get_gpu_count() > 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * field  w*h scalars (°C)
 * lo/hi  display range; the network sees (v - lo) / (hi - lo) clamped to 0..1
 * lut    256 ARGB entries
 * inCh   1 or 3: copies of the field the network takes (read from the .param)
 * out    (w*4)*(h*4) ARGB, caller-allocated
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_magnity_viewer_pipeline_NcnnUpscaler_nativeRun(
        JNIEnv* env, jobject, jlong handle, jfloatArray jfield, jint w, jint h,
        jfloat lo, jfloat hi, jintArray jlut, jint inCh, jintArray jout) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) return JNI_FALSE;

    const int ow = w * 4, oh = h * 4;
    if (env->GetArrayLength(jout) < (jsize)(ow * oh)) {
        LOGE("output array too small");
        return JNI_FALSE;
    }

    using clock = std::chrono::steady_clock;
    auto ms = [](clock::time_point a, clock::time_point b) {
        return std::chrono::duration<float, std::milli>(b - a).count();
    };
    const auto t0 = clock::now();
    jfloat* field = env->GetFloatArrayElements(jfield, nullptr);

    const int ic = inCh == 1 ? 1 : 3;
    ncnn::Mat in(w, h, ic);
    const float inv = 1.0f / std::max(hi - lo, 1e-6f);
    float* c0 = in.channel(0);
    for (int i = 0; i < w * h; i++) {
        float t = (field[i] - lo) * inv;
        c0[i] = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
    }
    for (int c = 1; c < ic; c++) {        // first-release models: R = G = B
        std::copy(c0, c0 + w * h, static_cast<float*>(in.channel(c)));
    }
    env->ReleaseFloatArrayElements(jfield, field, JNI_ABORT);

    const auto t1 = clock::now();
    ncnn::Mat out;
    {
        ncnn::Extractor ex = s->net.create_extractor();
        ex.set_light_mode(true);
        if (ex.input("data", in) != 0) {
            LOGE("input blob 'data' not found");
            return JNI_FALSE;
        }
        if (ex.extract("output", out) != 0) {
            LOGE("output blob 'output' not found");
            return JNI_FALSE;
        }
    }
    const auto t2 = clock::now();
    if (out.w != ow || out.h != oh) {
        LOGE("unexpected output %dx%d, wanted %dx%d", out.w, out.h, ow, oh);
        return JNI_FALSE;
    }

    jint* lut = env->GetIntArrayElements(jlut, nullptr);
    std::vector<jint> argb((size_t)ow * oh);
    // back to one channel: 1-channel models as-is, RGB ones averaged
    const int oc = out.c;
    const float* o0 = out.channel(0);
    const float* o1 = oc >= 3 ? static_cast<const float*>(out.channel(1)) : nullptr;
    const float* o2 = oc >= 3 ? static_cast<const float*>(out.channel(2)) : nullptr;
    for (int i = 0; i < ow * oh; i++) {
        float v = o1 ? (o0[i] + o1[i] + o2[i]) * (1.0f / 3.0f) : o0[i];
        v = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
        argb[i] = lut[(int)(v * 255.0f + 0.5f)];
    }
    env->ReleaseIntArrayElements(jlut, lut, JNI_ABORT);
    env->SetIntArrayRegion(jout, 0, ow * oh, argb.data());
    const auto t3 = clock::now();
    s->prepMs = ms(t0, t1);
    s->netMs = ms(t1, t2);
    s->postMs = ms(t2, t3);
    return JNI_TRUE;
}

/** The last nativeRun's split in ms: {input fill, network, palette + copy-out}. */
extern "C" JNIEXPORT void JNICALL
Java_com_magnity_viewer_pipeline_NcnnUpscaler_nativeTimings(
        JNIEnv* env, jobject, jlong handle, jfloatArray jout) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr || env->GetArrayLength(jout) < 3) return;
    const jfloat t[3] = {s->prepMs, s->netMs, s->postMs};
    env->SetFloatArrayRegion(jout, 0, 3, t);
}
