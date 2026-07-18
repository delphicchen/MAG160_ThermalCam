package com.magnity.thermalcam.data

import com.magnity.thermalcam.pipeline.Espcn
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes an on-device-trained [Espcn] as a standard ONNX file, so inference keeps
 * running through ONNX Runtime's NNAPI/XNNPACK backends exactly like the shipped
 * models. Hand-rolled protobuf encoding — the graph is tiny and fixed:
 *
 *   input[N,1,H,W] → Conv(5x5,p2) → Relu → Conv(3x3,p1) → Relu → Conv(3x3,p1)
 *                  → DepthToSpace(blocksize=scale, mode=CRD) → output
 *
 * DepthToSpace CRD == PyTorch PixelShuffle, matching both train_sr.py's export and
 * our Kotlin forward. Dynamic N/H/W dims (same as the desktop export). Weights are
 * embedded as raw little-endian float32 initializers (~30 KB total).
 */
object OnnxExport {

    // --- minimal protobuf writer -------------------------------------------------

    private class Pb {
        val out = ByteArrayOutputStream()

        fun varint(v0: Long) {
            var v = v0
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) { out.write(b); return }
                out.write(b or 0x80)
            }
        }

        fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun int64(field: Int, v: Long) { key(field, 0); varint(v) }
        fun bytes(field: Int, b: ByteArray) { key(field, 2); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun msg(field: Int, m: Pb) = bytes(field, m.out.toByteArray())
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    // --- ONNX message builders ----------------------------------------------------

    private fun attrInt(name: String, v: Long) = Pb().apply {
        str(1, name)            // name
        int64(3, v)             // i
        int64(20, 2)            // type = INT
    }

    private fun attrInts(name: String, vs: LongArray) = Pb().apply {
        str(1, name)
        for (v in vs) int64(8, v)   // ints
        int64(20, 7)                // type = INTS
    }

    private fun attrString(name: String, v: String) = Pb().apply {
        str(1, name)
        bytes(4, v.toByteArray(Charsets.UTF_8))   // s
        int64(20, 3)                              // type = STRING
    }

    private fun node(
        opType: String, inputs: List<String>, outputs: List<String>,
        attrs: List<Pb> = emptyList(),
    ) = Pb().apply {
        for (i in inputs) str(1, i)
        for (o in outputs) str(2, o)
        str(3, "${opType}_${outputs[0]}")   // node name
        str(4, opType)
        for (a in attrs) msg(5, a)
    }

    private fun tensorF32(name: String, dims: LongArray, data: FloatArray) = Pb().apply {
        for (d in dims) int64(1, d)          // dims
        int64(2, 1)                          // data_type = FLOAT
        str(8, name)
        val raw = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        raw.asFloatBuffer().put(data)
        bytes(9, raw.array())                // raw_data
    }

    private fun dimParam(p: String) = Pb().apply { str(2, p) }
    private fun dimValue(v: Long) = Pb().apply { int64(1, v) }

    private fun valueInfo(name: String, dims: List<Pb>) = Pb().apply {
        str(1, name)
        val shape = Pb().apply { for (d in dims) msg(1, d) }
        val tensor = Pb().apply { int64(1, 1); msg(2, shape) }   // elem_type FLOAT, shape
        val type = Pb().apply { msg(1, tensor) }                 // tensor_type
        msg(2, type)
    }

    // --- the ESPCN graph -----------------------------------------------------------

    fun exportEspcn(m: Espcn, out: OutputStream) {
        val s = m.scale.toLong()
        val graph = Pb().apply {
            // nodes (field 1, topological order)
            msg(1, node("Conv", listOf("input", "w1", "b1"), listOf("c1"), listOf(
                attrInts("kernel_shape", longArrayOf(5, 5)),
                attrInts("pads", longArrayOf(2, 2, 2, 2)),
            )))
            msg(1, node("Relu", listOf("c1"), listOf("r1")))
            msg(1, node("Conv", listOf("r1", "w2", "b2"), listOf("c2"), listOf(
                attrInts("kernel_shape", longArrayOf(3, 3)),
                attrInts("pads", longArrayOf(1, 1, 1, 1)),
            )))
            msg(1, node("Relu", listOf("c2"), listOf("r2")))
            msg(1, node("Conv", listOf("r2", "w3", "b3"), listOf("c3"), listOf(
                attrInts("kernel_shape", longArrayOf(3, 3)),
                attrInts("pads", longArrayOf(1, 1, 1, 1)),
            )))
            msg(1, node("DepthToSpace", listOf("c3"), listOf("output"), listOf(
                attrInt("blocksize", s),
                attrString("mode", "CRD"),
            )))
            str(2, "thermal_espcn_${m.scale}x_local")
            // initializers (field 5)
            msg(5, tensorF32("w1", longArrayOf(Espcn.C1.toLong(), 1, 5, 5), m.w1))
            msg(5, tensorF32("b1", longArrayOf(Espcn.C1.toLong()), m.b1))
            msg(5, tensorF32("w2", longArrayOf(Espcn.C2.toLong(), Espcn.C1.toLong(), 3, 3), m.w2))
            msg(5, tensorF32("b2", longArrayOf(Espcn.C2.toLong()), m.b2))
            msg(5, tensorF32("w3", longArrayOf(m.outC.toLong(), Espcn.C2.toLong(), 3, 3), m.w3))
            msg(5, tensorF32("b3", longArrayOf(m.outC.toLong()), m.b3))
            // graph input/output (fields 11/12), dynamic N/H/W like the desktop export
            msg(11, valueInfo("input", listOf(dimParam("batch"), dimValue(1), dimParam("height"), dimParam("width"))))
            msg(12, valueInfo("output", listOf(dimParam("batch"), dimValue(1), dimParam("height2"), dimParam("width2"))))
        }
        val model = Pb().apply {
            int64(1, 8)                                 // ir_version
            str(2, "magthermal-ondevice-trainer")       // producer_name
            msg(7, graph)
            msg(8, Pb().apply { str(1, ""); int64(2, 17) })   // opset_import {domain "", v17}
        }
        out.write(model.toByteArray())
        out.flush()
    }
}
