package com.magnity.viewer.data

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Minimal NPY / NPZ reader — just enough to load the repo's shared binary assets
 * (`planck_luts.npy`, `factory_nuc_grid.npz`) so the Android port reads the exact
 * same files as the Linux app. Little-endian integer dtypes only.
 */
class NpyArray(
    val dtype: String,          // numpy descr, e.g. "<i4", "<u2", "<i8"
    val shape: IntArray,
    private val data: ByteBuffer,   // little-endian, positioned at 0
) {
    val elementCount: Int = if (shape.isEmpty()) 1 else shape.reduce { a, b -> a * b }

    fun toLongArray(): LongArray {
        val out = LongArray(elementCount)
        val b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (dtype) {
            "<i8" -> { val v = b.asLongBuffer(); for (i in out.indices) out[i] = v.get(i) }
            "<i4" -> { val v = b.asIntBuffer(); for (i in out.indices) out[i] = v.get(i).toLong() }
            "<i2" -> { val v = b.asShortBuffer(); for (i in out.indices) out[i] = v.get(i).toLong() }
            "<u2" -> { val v = b.asShortBuffer(); for (i in out.indices) out[i] = (v.get(i).toInt() and 0xFFFF).toLong() }
            "<u4" -> { val v = b.asIntBuffer(); for (i in out.indices) out[i] = v.get(i).toLong() and 0xFFFFFFFFL }
            else -> throw IllegalArgumentException("unsupported dtype $dtype")
        }
        return out
    }

    fun toIntArray(): IntArray {
        val out = IntArray(elementCount)
        val b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (dtype) {
            "<i4" -> { val v = b.asIntBuffer(); for (i in out.indices) out[i] = v.get(i) }
            "<i2" -> { val v = b.asShortBuffer(); for (i in out.indices) out[i] = v.get(i).toInt() }
            "<u2" -> { val v = b.asShortBuffer(); for (i in out.indices) out[i] = v.get(i).toInt() and 0xFFFF }
            "<i8" -> { val v = b.asLongBuffer(); for (i in out.indices) out[i] = v.get(i).toInt() }
            else -> throw IllegalArgumentException("unsupported dtype $dtype")
        }
        return out
    }

    fun scalarInt(): Int = toIntArray()[0]

    companion object {
        private val MAGIC = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
            'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())

        /** Parse one .npy stream (does not close it). */
        fun read(input: InputStream): NpyArray {
            val din = DataInputStream(input)
            val magic = ByteArray(6); din.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "not an NPY stream" }
            val major = din.readUnsignedByte(); din.readUnsignedByte() // minor
            val headerLen = if (major == 1) {
                val b = ByteArray(2); din.readFully(b)
                (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            } else {
                val b = ByteArray(4); din.readFully(b)
                ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
            }
            val headerBytes = ByteArray(headerLen); din.readFully(headerBytes)
            val header = String(headerBytes, Charsets.ISO_8859_1)

            val dtype = Regex("'descr'\\s*:\\s*'([^']+)'").find(header)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("no descr in npy header")
            val fortran = Regex("'fortran_order'\\s*:\\s*(True|False)").find(header)?.groupValues?.get(1)
            require(fortran == "False") { "fortran-order arrays unsupported" }
            val shapeStr = Regex("'shape'\\s*:\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("no shape in npy header")
            val shape = shapeStr.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()

            val elemSize = dtype.drop(2).toInt()
            val count = if (shape.isEmpty()) 1 else shape.reduce { a, b -> a * b }
            val payload = ByteArray(count * elemSize)
            din.readFully(payload)
            return NpyArray(dtype, shape, ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN))
        }

        /** Read all entries of an .npz (zip of .npy) into a map keyed without the .npy suffix. */
        fun readNpz(input: InputStream): Map<String, NpyArray> {
            val out = HashMap<String, NpyArray>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".npy")) {
                        try {
                            out[entry.name.removeSuffix(".npy")] = read(zip)
                        } catch (e: EOFException) {
                            throw IllegalStateException("truncated npz entry ${entry.name}", e)
                        }
                    }
                    zip.closeEntry()
                }
            }
            return out
        }
    }
}
