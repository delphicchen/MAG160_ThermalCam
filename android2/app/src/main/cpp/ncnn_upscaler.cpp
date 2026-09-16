// ncnn + Vulkan bridge for the thermal super-resolution model.
//
// Same contract as the Anime4K path: in goes the scalar temperature field (before
// colouring), out comes palette-mapped ARGB at 4x. Doing the normalisation, the network
// and the palette lookup in one call keeps the per-frame JNI traffic to two arrays.
//
// The model is SRVGGNetCompact x4 (see sr_train/): 3-channel in/out, values in 0..1. The
// frame is grey, so the field is replicated across RGB on the way in and the channels are
// averaged on the way out.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <vector>

#include "net.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MagViewerNcnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MagViewerNcnn", __VA_ARGS__)

namespace {

struct Session {
    ncnn::Net net;
    bool vulkan = false;
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
 * out    (w*4)*(h*4) ARGB, caller-allocated
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_magnity_viewer_pipeline_NcnnUpscaler_nativeRun(
        JNIEnv* env, jobject, jlong handle, jfloatArray jfield, jint w, jint h,
        jfloat lo, jfloat hi, jintArray jlut, jintArray jout) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) return JNI_FALSE;

    const int ow = w * 4, oh = h * 4;
    if (env->GetArrayLength(jout) < (jsize)(ow * oh)) {
        LOGE("output array too small");
        return JNI_FALSE;
    }

    jfloat* field = env->GetFloatArrayElements(jfield, nullptr);

    ncnn::Mat in(w, h, 3);
    const float inv = 1.0f / std::max(hi - lo, 1e-6f);
    float* c0 = in.channel(0);
    float* c1 = in.channel(1);
    float* c2 = in.channel(2);
    for (int i = 0; i < w * h; i++) {
        float t = (field[i] - lo) * inv;
        t = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
        c0[i] = c1[i] = c2[i] = t;
    }
    env->ReleaseFloatArrayElements(jfield, field, JNI_ABORT);

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
    if (out.w != ow || out.h != oh) {
        LOGE("unexpected output %dx%d, wanted %dx%d", out.w, out.h, ow, oh);
        return JNI_FALSE;
    }

    jint* lut = env->GetIntArrayElements(jlut, nullptr);
    std::vector<jint> argb((size_t)ow * oh);
    const float* o0 = out.channel(0);
    const float* o1 = out.channel(1);
    const float* o2 = out.channel(2);
    for (int i = 0; i < ow * oh; i++) {
        float v = (o0[i] + o1[i] + o2[i]) * (1.0f / 3.0f);
        v = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
        argb[i] = lut[(int)(v * 255.0f + 0.5f)];
    }
    env->ReleaseIntArrayElements(jlut, lut, JNI_ABORT);
    env->SetIntArrayRegion(jout, 0, ow * oh, argb.data());
    return JNI_TRUE;
}
