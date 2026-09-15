package com.magnity.viewer.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Anime4K CNN upscaler (bloc97/Anime4K v3.2, MIT) on an off-screen GLES 3.0 context.
 *
 * The original mpv user shader (assets/anime4k/…) is loaded unchanged: its `//!` pass
 * headers are parsed at runtime and each pass is wrapped into a GLES fragment shader
 * that provides the mpv `NAME_tex / _texOff / _pos / _size / _pt` bindings.
 *
 * Input is the scalar field BEFORE colouring (normalised to the display range, fed as
 * grey RGB), so edges are rebuilt on temperature data; the palette LUT is applied in a
 * last GPU pass and the result read back into a Bitmap. That keeps screenshots, video
 * and ROI overlays on the existing CPU-side path.
 *
 * All GL work runs on one private thread — an EGL context is bound to a thread, while the
 * caller's coroutine may hop between pool threads.
 */
class Anime4kGpu(context: Context) : AutoCloseable {

    private class Pass(
        val binds: List<String>, val save: String,
        val wExpr: List<String>, val hExpr: List<String>, val body: String,
    ) {
        var program = 0
        var outLoc = -1
        lateinit var samplerLocs: IntArray
        lateinit var sizeLocs: IntArray
    }

    private class Tex(val id: Int, val fbo: Int, val w: Int, val h: Int)

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "anime4k-gl") }
    private val passes: List<Pass> =
        context.assets.open(SHADER).bufferedReader().use { parse(it.readText()) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var paletteProgram = 0
    private var input: Tex? = null
    private var output: Tex? = null
    private var lutTex = 0
    private var lutKey: IntArray? = null
    private val targets = HashMap<String, Tex>()
    private var inBuf: FloatBuffer? = null
    private var outBuf: ByteBuffer? = null

    companion object {
        const val SHADER = "anime4k/Anime4K_Upscale_CNN_x2_M.glsl"

        private const val VERT = """#version 300 es
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    gl_Position = vec4(p, 0.0, 1.0);
}"""

        private const val PALETTE_FRAG = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D u_src;
uniform sampler2D u_lut;
uniform vec2 u_out;
out vec4 fragColor;
void main() {
    float t = clamp(texture(u_src, gl_FragCoord.xy / u_out).r, 0.0, 1.0);
    fragColor = vec4(texture(u_lut, vec2((t * 255.0 + 0.5) / 256.0, 0.5)).rgb, 1.0);
}"""

        /** Split an mpv user shader into passes (header lines start with `//!`). */
        private fun parse(src: String): List<Pass> {
            val chunks = src.split("//!DESC").drop(1)          // [0] is the licence comment
            return chunks.map { chunk ->
                val binds = mutableListOf<String>()
                var save = ""
                var w = emptyList<String>(); var h = emptyList<String>()
                val body = StringBuilder()
                chunk.lines().drop(1).forEach { line ->          // drop rest of DESC line
                    if (line.startsWith("//!")) {
                        val parts = line.removePrefix("//!").trim().split(Regex("\\s+"))
                        when (parts[0]) {
                            "BIND" -> binds += parts[1]
                            "SAVE" -> save = parts[1]
                            "WIDTH" -> w = parts.drop(1)
                            "HEIGHT" -> h = parts.drop(1)
                        }
                    } else body.append(line).append('\n')
                }
                Pass(binds, save, w, h, body.toString())
            }
        }

        private fun fragmentFor(p: Pass): String {
            val sb = StringBuilder(
                "#version 300 es\nprecision highp float;\nprecision highp sampler2D;\n" +
                "uniform vec2 u_out;\nout vec4 fragColor;\n")
            for (b in p.binds) {
                sb.append("uniform sampler2D ${b}_raw;\nuniform vec2 ${b}_size;\n")
                sb.append("#define ${b}_pos (gl_FragCoord.xy / u_out)\n")
                sb.append("#define ${b}_pt (vec2(1.0) / ${b}_size)\n")
                sb.append("#define ${b}_tex(p) texture(${b}_raw, (p))\n")
                sb.append("#define ${b}_texOff(off) ${b}_tex(${b}_pos + ${b}_pt * (off))\n")
            }
            sb.append(p.body).append("\nvoid main() { fragColor = hook(); }\n")
            return sb.toString()
        }
    }

    init {
        try {
            onGl { initGl() }
        } catch (e: Throwable) {
            exec.shutdown()
            throw e
        }
    }

    private fun <T> onGl(block: () -> T): T = try {
        exec.submit(block).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    // ---- setup ----------------------------------------------------------------------

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        check(n[0] > 0) { "no ES3 EGL config" }
        eglCtx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglCtx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(display, cfgs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, eglCtx)) { "eglMakeCurrent failed" }

        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        check("EXT_color_buffer_half_float" in ext || "EXT_color_buffer_float" in ext) {
            "GPU lacks half-float render targets"
        }

        for (p in passes) {
            p.program = link(fragmentFor(p))
            p.outLoc = GLES30.glGetUniformLocation(p.program, "u_out")
            p.samplerLocs = IntArray(p.binds.size) {
                GLES30.glGetUniformLocation(p.program, "${p.binds[it]}_raw") }
            p.sizeLocs = IntArray(p.binds.size) {
                GLES30.glGetUniformLocation(p.program, "${p.binds[it]}_size") }
        }
        paletteProgram = link(PALETTE_FRAG)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            error("shader compile: $log")
        }
        return s
    }

    private fun link(frag: String): Int {
        val prog = GLES30.glCreateProgram()
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, frag)
        GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link: ${GLES30.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun makeTex(w: Int, h: Int, internal: Int, format: Int, type: Int,
                        filter: Int = GLES30.GL_LINEAR, withFbo: Boolean = true): Tex {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        var fbo = 0
        if (withFbo) {
            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, id[0], 0)
            val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(st == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete 0x${st.toString(16)}" }
            fbo = f[0]
        }
        return Tex(id[0], fbo, w, h)
    }

    private fun deleteTex(t: Tex) {
        GLES30.glDeleteTextures(1, intArrayOf(t.id), 0)
        if (t.fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(t.fbo), 0)
    }

    /** Reuse a render target per (stage, pass); reallocate only if its size changed. */
    private fun target(key: String, w: Int, h: Int, internal: Int = GLES30.GL_RGBA16F,
                       format: Int = GLES30.GL_RGBA, type: Int = GLES30.GL_HALF_FLOAT): Tex {
        targets[key]?.let { if (it.w == w && it.h == h) return it; deleteTex(it) }
        return makeTex(w, h, internal, format, type).also { targets[key] = it }
    }

    private fun eval(expr: List<String>, tex: Map<String, Tex>): Int {
        val st = ArrayDeque<Float>()
        for (t in expr) when {
            t.endsWith(".w") -> st.addLast(tex.getValue(t.dropLast(2)).w.toFloat())
            t.endsWith(".h") -> st.addLast(tex.getValue(t.dropLast(2)).h.toFloat())
            t == "*" || t == "/" || t == "+" || t == "-" -> {
                val b = st.removeLast(); val a = st.removeLast()
                st.addLast(when (t) { "*" -> a * b; "/" -> a / b; "+" -> a + b; else -> a - b })
            }
            else -> st.addLast(t.toFloat())
        }
        return st.last().roundToInt()
    }

    // ---- per frame ------------------------------------------------------------------

    /**
     * @param field scalar display field, row 0 = top
     * @param stages number of ×2 CNN stages (1 → 2×, 2 → 4×)
     */
    fun render(field: FloatArray, w: Int, h: Int, lo: Float, hi: Float,
               stages: Int, lut: IntArray): Bitmap = onGl {
        // input: normalised grey in RGB (the CNN's first layer reads rgb)
        val inp = input?.takeIf { it.w == w && it.h == h } ?: run {
            input?.let { deleteTex(it) }
            makeTex(w, h, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_FLOAT,
                    withFbo = false).also { input = it }
        }
        val buf = inBuf?.takeIf { it.capacity() == w * h * 4 }
            ?: ByteBuffer.allocateDirect(w * h * 4 * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().also { inBuf = it }
        buf.clear()
        val inv = 1f / (hi - lo)
        for (v in field) {
            var t = (v - lo) * inv
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            buf.put(t); buf.put(t); buf.put(t); buf.put(1f)
        }
        buf.flip()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inp.id)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
                               GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)

        // palette LUT texture (re-uploaded only when the palette changes)
        if (lutKey !== lut) {
            if (lutTex == 0) {
                lutTex = makeTex(256, 1, GLES30.GL_RGBA8, GLES30.GL_RGBA,
                                 GLES30.GL_UNSIGNED_BYTE, withFbo = false).id
            }
            val lb = ByteBuffer.allocateDirect(256 * 4)
            for (c in lut) {
                lb.put((c shr 16).toByte()); lb.put((c shr 8).toByte())
                lb.put(c.toByte()); lb.put(0xFF.toByte())
            }
            lb.flip()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 256, 1,
                                   GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lb)
            lutKey = lut
        }

        // CNN passes
        val tex = HashMap<String, Tex>()
        tex["MAIN"] = inp
        for (s in 0 until stages) {
            passes.forEachIndexed { pi, p ->
                val ow = eval(p.wExpr, tex); val oh = eval(p.hExpr, tex)
                val t = target("$s/$pi", ow, oh)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
                GLES30.glViewport(0, 0, ow, oh)
                GLES30.glUseProgram(p.program)
                GLES30.glUniform2f(p.outLoc, ow.toFloat(), oh.toFloat())
                p.binds.forEachIndexed { u, name ->
                    val src = tex.getValue(name)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.id)
                    GLES30.glUniform1i(p.samplerLocs[u], u)
                    GLES30.glUniform2f(p.sizeLocs[u], src.w.toFloat(), src.h.toFloat())
                }
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                tex[p.save] = t
            }
        }

        // palette → RGBA8, read back
        val main = tex.getValue("MAIN")
        val out = output?.takeIf { it.w == main.w && it.h == main.h } ?: run {
            output?.let { deleteTex(it) }
            makeTex(main.w, main.h, GLES30.GL_RGBA8, GLES30.GL_RGBA,
                    GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST).also { output = it }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, out.fbo)
        GLES30.glViewport(0, 0, out.w, out.h)
        GLES30.glUseProgram(paletteProgram)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(paletteProgram, "u_out"),
                           out.w.toFloat(), out.h.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, main.id)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(paletteProgram, "u_src"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(paletteProgram, "u_lut"), 1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        val ob = outBuf?.takeIf { it.capacity() == out.w * out.h * 4 }
            ?: ByteBuffer.allocateDirect(out.w * out.h * 4).order(ByteOrder.nativeOrder())
                .also { outBuf = it }
        ob.clear()
        // FBO row 0 is texture row 0 = image top, so no vertical flip is needed
        GLES30.glReadPixels(0, 0, out.w, out.h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ob)
        ob.rewind()
        Bitmap.createBitmap(out.w, out.h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ob)
        }
    }

    override fun close() {
        runCatching {
            onGl {
                targets.values.forEach { deleteTex(it) }; targets.clear()
                input?.let { deleteTex(it) }; output?.let { deleteTex(it) }
                passes.forEach { if (it.program != 0) GLES30.glDeleteProgram(it.program) }
                if (paletteProgram != 0) GLES30.glDeleteProgram(paletteProgram)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                                     EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, eglCtx)
                EGL14.eglTerminate(display)
            }
        }
        exec.shutdown()
    }
}
