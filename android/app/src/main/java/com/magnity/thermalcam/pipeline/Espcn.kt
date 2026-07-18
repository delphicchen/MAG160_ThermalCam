package com.magnity.thermalcam.pipeline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Pure-Kotlin ESPCN — the same tiny network as train_sr.py's ThermalESPCN (~7K params),
 * with on-device training. No PyTorch / no extra dependencies: three conv layers are
 * small enough to train with hand-written forward/backward on a Dimensity-class CPU in
 * a few minutes. Inference of the trained result still runs through ONNX Runtime
 * (NNAPI/XNNPACK) — see OnnxExport, which writes the trained weights as a standard
 * .onnx file.
 *
 * Architecture (identical to the desktop trainer):
 *   Conv2d(1→32, 5x5, pad 2) → ReLU
 *   Conv2d(32→16, 3x3, pad 1) → ReLU
 *   Conv2d(16→scale², 3x3, pad 1) → PixelShuffle(scale)
 * ICNR initialisation on the last conv (sub-pixel copies share one kernel), Kaiming on
 * the rest. Training: self-supervised (HR = real frame normalised [0,1], LR = bicubic
 * ÷scale), L1 + 0.5·gradient-L1 loss, Adam + cosine LR schedule, flip augmentation.
 */
class Espcn(val scale: Int, seed: Long = 42) {

    companion object {
        const val C1 = 32; const val C2 = 16
        const val K1 = 5; const val K2 = 3; const val K3 = 3
    }

    val outC = scale * scale
    val w1 = FloatArray(C1 * 1 * K1 * K1); val b1 = FloatArray(C1)
    val w2 = FloatArray(C2 * C1 * K2 * K2); val b2 = FloatArray(C2)
    val w3 = FloatArray(outC * C2 * K3 * K3); val b3 = FloatArray(outC)

    val paramCount get() = w1.size + b1.size + w2.size + b2.size + w3.size + b3.size

    init {
        val rnd = java.util.Random(seed)
        fun kaiming(a: FloatArray, fanIn: Int) {
            val std = sqrt(2.0 / fanIn)
            for (i in a.indices) a[i] = (rnd.nextGaussian() * std).toFloat()
        }
        kaiming(w1, 1 * K1 * K1)
        kaiming(w2, C1 * K2 * K2)
        // ICNR: one Kaiming base kernel replicated across the scale² sub-pixel channels
        val base = FloatArray(C2 * K3 * K3)
        kaiming(base, C2 * K3 * K3)
        for (oc in 0 until outC) System.arraycopy(base, 0, w3, oc * base.size, base.size)
    }

    fun copyWeightsFrom(o: Espcn) {
        o.w1.copyInto(w1); o.b1.copyInto(b1)
        o.w2.copyInto(w2); o.b2.copyInto(b2)
        o.w3.copyInto(w3); o.b3.copyInto(b3)
    }

    /** Reusable per-worker activation buffers for one LR geometry. */
    class Acts(w: Int, h: Int, outC: Int) {
        val a1 = FloatArray(C1 * w * h)
        val a2 = FloatArray(C2 * w * h)
        val a3 = FloatArray(outC * w * h)
        val d1 = FloatArray(C1 * w * h)
        val d2 = FloatArray(C2 * w * h)
        val d3 = FloatArray(outC * w * h)
    }

    // ---- forward ---------------------------------------------------------------

    private fun conv(
        input: FloatArray, inC: Int, w: Int, h: Int,
        weight: FloatArray, bias: FloatArray, outC: Int, k: Int,
        out: FloatArray, relu: Boolean,
    ) {
        val p = k / 2
        val wh = w * h
        for (oc in 0 until outC) {
            val ob = oc * wh
            val bv = bias[oc]
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var acc = bv
                    for (ic in 0 until inC) {
                        val wOff = ((oc * inC) + ic) * k * k
                        val ib = ic * wh
                        for (ky in 0 until k) {
                            val iy = y + ky - p
                            if (iy < 0 || iy >= h) continue
                            val rowW = wOff + ky * k
                            val rowI = ib + iy * w
                            for (kx in 0 until k) {
                                val ix = x + kx - p
                                if (ix < 0 || ix >= w) continue
                                acc += input[rowI + ix] * weight[rowW + kx]
                            }
                        }
                    }
                    out[ob + y * w + x] = if (relu && acc < 0f) 0f else acc
                }
            }
        }
    }

    /** LR (w x h) → SR (w*scale x h*scale). Pass [acts] to keep buffers for backward. */
    fun forward(x: FloatArray, w: Int, h: Int, acts: Acts = Acts(w, h, outC)): FloatArray {
        conv(x, 1, w, h, w1, b1, C1, K1, acts.a1, relu = true)
        conv(acts.a1, C1, w, h, w2, b2, C2, K2, acts.a2, relu = true)
        conv(acts.a2, C2, w, h, w3, b3, outC, K3, acts.a3, relu = false)
        // PixelShuffle: out[y*r+i][x*r+j] = a3[c = i*r + j][y][x]
        val r = scale
        val out = FloatArray(w * r * h * r)
        val wr = w * r
        val wh = w * h
        for (i in 0 until r) for (j in 0 until r) {
            val cb = (i * r + j) * wh
            for (y in 0 until h) {
                val orow = (y * r + i) * wr
                val irow = cb + y * w
                for (x in 0 until w) out[orow + x * r + j] = acts.a3[irow + x]
            }
        }
        return out
    }

    // ---- backward --------------------------------------------------------------

    class Grads(m: Espcn) {
        val g1 = FloatArray(m.w1.size); val gb1 = FloatArray(m.b1.size)
        val g2 = FloatArray(m.w2.size); val gb2 = FloatArray(m.b2.size)
        val g3 = FloatArray(m.w3.size); val gb3 = FloatArray(m.b3.size)
        fun clear() {
            java.util.Arrays.fill(g1, 0f); java.util.Arrays.fill(gb1, 0f)
            java.util.Arrays.fill(g2, 0f); java.util.Arrays.fill(gb2, 0f)
            java.util.Arrays.fill(g3, 0f); java.util.Arrays.fill(gb3, 0f)
        }
        fun add(o: Grads) {
            for (i in g1.indices) g1[i] += o.g1[i]; for (i in gb1.indices) gb1[i] += o.gb1[i]
            for (i in g2.indices) g2[i] += o.g2[i]; for (i in gb2.indices) gb2[i] += o.gb2[i]
            for (i in g3.indices) g3[i] += o.g3[i]; for (i in gb3.indices) gb3[i] += o.gb3[i]
        }
    }

    private fun convBackward(
        input: FloatArray, inC: Int, w: Int, h: Int,
        weight: FloatArray, outC: Int, k: Int,
        dOut: FloatArray, gradW: FloatArray, gradB: FloatArray, dIn: FloatArray?,
    ) {
        val p = k / 2
        val wh = w * h
        dIn?.let { java.util.Arrays.fill(it, 0f) }
        for (oc in 0 until outC) {
            val ob = oc * wh
            var gb = 0f
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val g = dOut[ob + y * w + x]
                    if (g == 0f) continue
                    gb += g
                    for (ic in 0 until inC) {
                        val wOff = ((oc * inC) + ic) * k * k
                        val ib = ic * wh
                        for (ky in 0 until k) {
                            val iy = y + ky - p
                            if (iy < 0 || iy >= h) continue
                            val rowW = wOff + ky * k
                            val rowI = ib + iy * w
                            for (kx in 0 until k) {
                                val ix = x + kx - p
                                if (ix < 0 || ix >= w) continue
                                gradW[rowW + kx] += g * input[rowI + ix]
                                dIn?.let { it[rowI + ix] += g * weight[rowW + kx] }
                            }
                        }
                    }
                }
            }
            gradB[oc] += gb
        }
    }

    /**
     * Forward + combined loss (L1 + gradWeight·gradient-L1) + full backward for one
     * (LR, HR) pair. Accumulates into [grads]; returns the loss.
     */
    fun lossAndGrads(
        lr: FloatArray, hr: FloatArray, w: Int, h: Int,
        gradWeight: Float, grads: Grads, acts: Acts,
    ): Float {
        val pred = forward(lr, w, h, acts)
        val r = scale
        val wr = w * r; val hr2 = h * r
        val n = wr * hr2

        // L1 pixel loss + gradient into dY
        val dY = FloatArray(n)
        var loss = 0.0
        for (i in 0 until n) {
            val d = pred[i] - hr[i]
            loss += Math.abs(d.toDouble())
            dY[i] = sign(d) / n
        }
        loss /= n

        // gradient (finite-difference) L1 loss
        val nDx = hr2 * (wr - 1)
        var gl = 0.0
        for (y in 0 until hr2) {
            val row = y * wr
            for (x in 0 until wr - 1) {
                val d = (pred[row + x + 1] - pred[row + x]) - (hr[row + x + 1] - hr[row + x])
                gl += Math.abs(d.toDouble())
                val s = gradWeight * sign(d) / nDx
                dY[row + x + 1] += s
                dY[row + x] -= s
            }
        }
        var glv = gl / nDx
        val nDy = (hr2 - 1) * wr
        gl = 0.0
        for (y in 0 until hr2 - 1) {
            val row = y * wr
            for (x in 0 until wr) {
                val d = (pred[row + wr + x] - pred[row + x]) - (hr[row + wr + x] - hr[row + x])
                gl += Math.abs(d.toDouble())
                val s = gradWeight * sign(d) / nDy
                dY[row + wr + x] += s
                dY[row + x] -= s
            }
        }
        glv += gl / nDy
        loss += gradWeight * glv

        // un-shuffle dY -> d3
        val wh = w * h
        for (i in 0 until r) for (j in 0 until r) {
            val cb = (i * r + j) * wh
            for (y in 0 until h) {
                val orow = (y * r + i) * wr
                val irow = cb + y * w
                for (x in 0 until w) acts.d3[irow + x] = dY[orow + x * r + j]
            }
        }

        // conv3 (linear) → d2, then ReLU mask of a2
        convBackward(acts.a2, C2, w, h, w3, outC, K3, acts.d3, grads.g3, grads.gb3, acts.d2)
        for (i in acts.d2.indices) if (acts.a2[i] <= 0f) acts.d2[i] = 0f
        // conv2 → d1, ReLU mask of a1
        convBackward(acts.a1, C1, w, h, w2, C2, K2, acts.d2, grads.g2, grads.gb2, acts.d1)
        for (i in acts.d1.indices) if (acts.a1[i] <= 0f) acts.d1[i] = 0f
        // conv1 (no dIn needed)
        convBackward(lr, 1, w, h, w1, C1, K1, acts.d1, grads.g1, grads.gb1, null)

        return loss.toFloat()
    }
}

/**
 * Self-supervised trainer — the on-device counterpart of `train_sr.py --train`.
 * Frames are per-frame-normalised HR ground truth; LR is bicubic ÷scale (our
 * resizeBicubic matches torch's bicubic: a=-0.75, half-pixel centres).
 */
class EspcnTrainer(val scale: Int, private val seed: Long = 42) {

    val model = Espcn(scale, seed)

    private class Adam(val p: FloatArray, val g: FloatArray) {
        val m = FloatArray(p.size); val v = FloatArray(p.size)
    }

    /**
     * @param frames per-frame-normalised [0,1] HR frames (w x h each)
     * @return best validation PSNR (dB); model holds the best weights on return
     */
    suspend fun train(
        frames: List<FloatArray>, w: Int, h: Int,
        epochs: Int = 40, batch: Int = 8, lr0: Float = 2e-3f, gradWeight: Float = 0.5f,
        valSplit: Float = 0.1f,
        onProgress: (epoch: Int, epochs: Int, trainLoss: Float, valPsnr: Float) -> Unit = { _, _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Float = coroutineScope {
        require(frames.size >= 16) { "need at least 16 frames" }
        require(w % scale == 0 && h % scale == 0)
        val rnd = java.util.Random(seed)

        // shuffle + split
        val idx = frames.indices.shuffled(kotlin.random.Random(seed)).toIntArray()
        val nVal = maxOf(1, (frames.size * valSplit).toInt())
        val valIdx = idx.take(nVal)
        val trainIdx = idx.drop(nVal)

        val opts = listOf(
            Adam(model.w1, FloatArray(model.w1.size)), Adam(model.b1, FloatArray(model.b1.size)),
            Adam(model.w2, FloatArray(model.w2.size)), Adam(model.b2, FloatArray(model.b2.size)),
            Adam(model.w3, FloatArray(model.w3.size)), Adam(model.b3, FloatArray(model.b3.size)),
        )
        var adamT = 0
        val beta1 = 0.9f; val beta2 = 0.999f; val eps = 1e-8f

        val lw = w / scale; val lh = h / scale
        val best = Espcn(scale, seed)
        best.copyWeightsFrom(model)
        var bestPsnr = -1f

        fun augment(hr: FloatArray): FloatArray {
            var f = hr
            if (rnd.nextBoolean()) f = ImageOps.flipHorizontal(f, w, h)
            if (rnd.nextBoolean()) {        // vertical flip
                val o = FloatArray(f.size)
                for (y in 0 until h) System.arraycopy(f, (h - 1 - y) * w, o, y * w, w)
                f = o
            }
            return f
        }

        fun valPsnr(): Float {
            var mseSum = 0.0
            for (vi in valIdx) {
                val hr = frames[vi]
                val lr = ImageOps.resizeBicubic(hr, w, h, lw, lh)
                val pred = model.forward(lr, lw, lh)
                var mse = 0.0
                for (i in pred.indices) {
                    val d = (pred[i] - hr[i]).toDouble(); mse += d * d
                }
                mseSum += mse / pred.size
            }
            val mse = mseSum / valIdx.size
            return if (mse < 1e-10) 100f else (10.0 * log10(1.0 / mse)).toFloat()
        }

        for (ep in 1..epochs) {
            if (isCancelled()) break
            val lrNow = lr0 * 0.5f * (1f + cos(PI * (ep - 1) / epochs).toFloat())
            val order = trainIdx.shuffled(kotlin.random.Random(seed + ep))
            var epLoss = 0.0
            var nBatches = 0
            var bi = 0
            while (bi < order.size) {
                if (isCancelled()) break
                val ids = order.subList(bi, minOf(bi + batch, order.size))
                bi += batch
                // pre-generate augmented pairs on this thread (deterministic rnd), then
                // fan the heavy forward/backward out over the default dispatcher
                val pairs = ids.map { fi ->
                    val hr = augment(frames[fi])
                    hr to ImageOps.resizeBicubic(hr, w, h, lw, lh)
                }
                val results = pairs.map { (hr, lr) ->
                    async(Dispatchers.Default) {
                        val g = Espcn.Grads(model)
                        val acts = Espcn.Acts(lw, lh, model.outC)
                        val loss = model.lossAndGrads(lr, hr, lw, lh, gradWeight, g, acts)
                        loss to g
                    }
                }.map { it.await() }

                val total = Espcn.Grads(model)
                var loss = 0f
                for ((l, g) in results) { loss += l; total.add(g) }
                loss /= results.size
                epLoss += loss; nBatches++

                // average grads over the batch, Adam step with cosine LR
                val inv = 1f / results.size
                adamT++
                val bc1 = 1f - Math.pow(beta1.toDouble(), adamT.toDouble()).toFloat()
                val bc2 = 1f - Math.pow(beta2.toDouble(), adamT.toDouble()).toFloat()
                val gradsArr = listOf(total.g1, total.gb1, total.g2, total.gb2, total.g3, total.gb3)
                for (k in opts.indices) {
                    val o = opts[k]; val grad = gradsArr[k]
                    for (i in o.p.indices) {
                        val g = grad[i] * inv
                        o.m[i] = beta1 * o.m[i] + (1 - beta1) * g
                        o.v[i] = beta2 * o.v[i] + (1 - beta2) * g * g
                        o.p[i] -= lrNow * (o.m[i] / bc1) / (sqrt(o.v[i] / bc2) + eps)
                    }
                }
            }
            val psnr = valPsnr()
            if (psnr > bestPsnr) { bestPsnr = psnr; best.copyWeightsFrom(model) }
            onProgress(ep, epochs, (epLoss / maxOf(nBatches, 1)).toFloat(), psnr)
        }

        model.copyWeightsFrom(best)
        bestPsnr
    }
}
