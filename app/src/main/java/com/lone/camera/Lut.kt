package com.lone.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 3D colour LUT. data index = ((b*N + g)*N + r)*3, red fastest (same as .cube files). */
class Lut(val name: String, val size: Int, private val data: FloatArray) {

    /** [r][g][b] packed ARGB ints, the layout Media3's SingleColorLut expects. */
    fun toCube(): Array<Array<IntArray>> = Array(size) { r ->
        Array(size) { g ->
            IntArray(size) { b ->
                val i = ((b * size + g) * size + r) * 3
                val rr = (data[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val gg = (data[i + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val bb = (data[i + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    /** Trilinear lookup, returns packed ARGB. */
    private fun lookup(r: Float, g: Float, b: Float): Int {
        val n1 = size - 1
        val rf = r * n1; val gf = g * n1; val bf = b * n1
        val r0 = min(rf.toInt(), n1 - 1); val g0 = min(gf.toInt(), n1 - 1); val b0 = min(bf.toInt(), n1 - 1)
        val dr = rf - r0; val dg = gf - g0; val db = bf - b0
        var ro = 0f; var go = 0f; var bo = 0f
        for (k in 0..7) {
            val ri = r0 + (k and 1); val gi = g0 + ((k shr 1) and 1); val bi = b0 + ((k shr 2) and 1)
            val w = (if (k and 1 == 1) dr else 1 - dr) *
                (if ((k shr 1) and 1 == 1) dg else 1 - dg) *
                (if ((k shr 2) and 1 == 1) db else 1 - db)
            val i = ((bi * size + gi) * size + ri) * 3
            ro += data[i] * w; go += data[i + 1] * w; bo += data[i + 2] * w
        }
        val rr = (ro.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gg = (go.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bb = (bo.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    /** Applies the LUT in place on a mutable ARGB_8888 bitmap, row by row to keep memory low. */
    fun applyTo(bmp: Bitmap) {
        val w = bmp.width
        val row = IntArray(w)
        val inv = 1f / 255f
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                row[x] = lookup(((p shr 16) and 255) * inv, ((p shr 8) and 255) * inv, (p and 255) * inv)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** Least-squares 3x4 colour matrix (android.graphics.ColorMatrix layout) approximating this LUT, for live preview. */
    fun approxMatrix(): FloatArray {
        val s = 5
        val ata = Array(4) { DoubleArray(4) }
        val atb = Array(3) { DoubleArray(4) }
        for (bi in 0 until s) for (gi in 0 until s) for (ri in 0 until s) {
            val x = doubleArrayOf(ri / (s - 1.0), gi / (s - 1.0), bi / (s - 1.0), 1.0)
            val c = lookup(x[0].toFloat(), x[1].toFloat(), x[2].toFloat())
            val y = doubleArrayOf(((c shr 16) and 255) / 255.0, ((c shr 8) and 255) / 255.0, (c and 255) / 255.0)
            for (i in 0..3) {
                for (j in 0..3) ata[i][j] += x[i] * x[j]
                for (k in 0..2) atb[k][i] += x[i] * y[k]
            }
        }
        val m = FloatArray(20)
        for (k in 0..2) {
            val sol = solve4(ata, atb[k])
            m[k * 5] = sol[0].toFloat(); m[k * 5 + 1] = sol[1].toFloat(); m[k * 5 + 2] = sol[2].toFloat()
            m[k * 5 + 4] = (sol[3] * 255.0).toFloat()
        }
        m[18] = 1f
        return m
    }

    private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = 4
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    companion object {
        /** Parses an Adobe/Resolve .cube 3D LUT. */
        fun parseCube(name: String, text: String): Lut {
            var n = 0
            var domMin = 0f; var domMax = 1f
            val vals = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("LUT_3D_SIZE") -> n = line.split(Regex("\\s+"))[1].toInt()
                    line.startsWith("DOMAIN_MIN") -> domMin = line.split(Regex("\\s+"))[1].toFloat()
                    line.startsWith("DOMAIN_MAX") -> domMax = line.split(Regex("\\s+"))[1].toFloat()
                    line[0].isLetter() -> Unit
                    else -> line.split(Regex("\\s+")).forEach { vals.add(it.toFloat()) }
                }
            }
            require(n >= 2 && vals.size >= n * n * n * 3) { "Not a valid 3D .cube LUT" }
            val span = if (domMax - domMin == 0f) 1f else domMax - domMin
            val arr = FloatArray(n * n * n * 3) { (vals[it] - domMin) / span }
            return Lut(name, n, arr)
        }

        private fun generate(name: String, n: Int = 33, f: (Float, Float, Float) -> FloatArray): Lut {
            val d = FloatArray(n * n * n * 3)
            var i = 0
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                val o = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
                d[i++] = o[0]; d[i++] = o[1]; d[i++] = o[2]
            }
            return Lut(name, n, d)
        }

        private fun sCurve(x: Float, c: Float): Float {
            val t = x.coerceIn(0f, 1f)
            val s = t * t * (3f - 2f * t)
            return t + (s - t) * c
        }

        private fun grade(
            r: Float, g: Float, b: Float,
            contrast: Float, sat: Float,
            warm: Float = 0f, shadowTeal: Float = 0f, gamma: Float = 1f,
        ): FloatArray {
            var rr = r.pow(gamma); var gg = g.pow(gamma); var bb = b.pow(gamma)
            rr = sCurve(rr, contrast); gg = sCurve(gg, contrast); bb = sCurve(bb, contrast)
            val l = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
            rr = l + (rr - l) * sat; gg = l + (gg - l) * sat; bb = l + (bb - l) * sat
            rr += warm * 0.05f; bb -= warm * 0.05f
            val sh = (1f - l).pow(2f) * shadowTeal
            rr -= sh * 0.05f; gg += sh * 0.02f; bb += sh * 0.05f
            val hi = l.pow(2f) * shadowTeal
            rr += hi * 0.04f; bb -= hi * 0.03f
            return floatArrayOf(max(0f, min(1f, rr)), max(0f, min(1f, gg)), max(0f, min(1f, bb)))
        }

        /** Built-in looks, shaped after Apple-camera style presets. */
        val builtIn: List<Lut> by lazy {
            listOf(
                generate("Natural") { r, g, b -> grade(r, g, b, 0.35f, 1.05f) },
                generate("Vibrant") { r, g, b -> grade(r, g, b, 0.45f, 1.28f, gamma = 0.97f) },
                generate("Rich Contrast") { r, g, b -> grade(r, g, b, 0.85f, 1.12f, gamma = 1.04f) },
                generate("Warm") { r, g, b -> grade(r, g, b, 0.4f, 1.12f, warm = 1f) },
                generate("Cool") { r, g, b -> grade(r, g, b, 0.4f, 1.08f, warm = -1f) },
                generate("Cinematic") { r, g, b -> grade(r, g, b, 0.7f, 0.95f, shadowTeal = 1f, gamma = 1.05f) },
                generate("Soft Film") { r, g, b -> grade(r, g, b, 0.15f, 0.85f, warm = 0.3f, gamma = 0.92f) },
                generate("Noir") { r, g, b -> grade(r, g, b, 0.9f, 0f, gamma = 1.05f) },
            )
        }
    }
}
