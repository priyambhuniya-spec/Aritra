package com.moonlens

import android.content.Context
import android.graphics.*
import kotlin.math.*

/** চাঁদ খোঁজা → সেরা ফ্রেম বাছাই → সাব-এলাইন → স্ট্যাক → ওয়েভলেট শার্পেন → (ঐচ্ছিক) ক্রেটার ফিউশন → টোন */
class MoonProcessor(private val ctx: Context) {
    companion object { const val OUT = 768; const val R = 320f; const val H = OUT / 2 }
    data class Moon(val cx: Float, val cy: Float, val r: Float)

    private fun lum(c: Int) = 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)

    fun findMoon(b: Bitmap): Moon? {
        val sc = max(1, max(b.width, b.height) / 400)
        val sw = b.width / sc; val sh = b.height / sc
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh); s.getPixels(px, 0, sw, 0, 0, sw, sh); s.recycle()
        val l = FloatArray(px.size) { lum(px[it]) }
        val srt = l.sortedArray()
        val thr = srt[(srt.size * 0.999).toInt()] * 0.5f
        if (thr < 40f) return null
        val rows = IntArray(sh); val cols = IntArray(sw)
        for (y in 0 until sh) for (x in 0 until sw) if (l[y * sw + x] > thr) { rows[y]++; cols[x]++ }
        val x0 = cols.indexOfFirst { it >= 3 }; val x1 = cols.indexOfLast { it >= 3 }
        val y0 = rows.indexOfFirst { it >= 3 }; val y1 = rows.indexOfLast { it >= 3 }
        if (x0 < 0 || y0 < 0 || x1 - x0 < 10 || y1 - y0 < 10) return null
        return Moon((x0 + x1 + 1) / 2f * sc, (y0 + y1 + 1) / 2f * sc, max(x1 - x0 + 1, y1 - y0 + 1) / 2f * sc)
    }

    /** চাঁদকে মাঝখানে এনে সবসময় একই সাইজে (ব্যাসার্ধ 320px) ক্রপ */
    fun crop(b: Bitmap, m: Moon): IntArray {
        val k = OUT / (m.r * 2.4f)
        val mx = Matrix().apply { postTranslate(-m.cx, -m.cy); postScale(k, k); postTranslate(OUT / 2f, OUT / 2f) }
        val o = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        Canvas(o).drawBitmap(b, mx, Paint(Paint.FILTER_BITMAP_FLAG))
        return IntArray(OUT * OUT).also { o.getPixels(it, 0, OUT, 0, 0, OUT, OUT); o.recycle() }
    }

    private fun halfLuma(p: IntArray) = FloatArray(H * H) { i ->
        val x = (i % H) * 2; val y = (i / H) * 2
        (lum(p[y * OUT + x]) + lum(p[y * OUT + x + 1]) + lum(p[(y + 1) * OUT + x]) + lum(p[(y + 1) * OUT + x + 1])) / 4f
    }

    private fun lapVar(a: FloatArray): Float {
        var s = 0f; var n = 0
        for (y in 48 until 336) for (x in 48 until 336) {
            val l = 4 * a[y * H + x] - a[y * H + x - 1] - a[y * H + x + 1] - a[(y - 1) * H + x] - a[(y + 1) * H + x]
            s += l * l; n++
        }
        return s / n
    }

    private fun bestShift(ref: FloatArray, f: FloatArray): Pair<Int, Int> {
        var best = Float.MAX_VALUE; var bx = 0; var by = 0
        for (dy in -3..3) for (dx in -3..3) {
            var s = 0f; var y = 48
            while (y < 336) {
                var x = 48
                while (x < 336) { val d = ref[y * H + x] - f[(y + dy) * H + x + dx]; s += d * d; x += 2 }
                y += 2
            }
            if (s < best) { best = s; bx = dx; by = dy }
        }
        return bx to by
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size); val d = 2f * r + 1f
        for (y in 0 until h) {
            var s = 0f
            for (x in -r..r) s += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = s / d
                s += src[y * w + min(x + r + 1, w - 1)] - src[y * w + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / d
                s += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
        return out
    }
    private fun blur(a: FloatArray, r: Int) = boxBlur(boxBlur(a, OUT, OUT, r), OUT, OUT, r)

    fun process(frames: List<IntArray>, sharp: Float, crater: Float, say: (String) -> Unit): Bitmap {
        val n = OUT * OUT
        say("সেরা ফ্রেম বাছাই হচ্ছে…")
        val half = frames.map { halfLuma(it) }
        val score = half.map { lapVar(it) }
        val keep = frames.indices.sortedByDescending { score[it] }.take(max(5, (frames.size * 0.3f).toInt()))
        say("এলাইন ও স্ট্যাকিং (${keep.size} ফ্রেম)…")
        val ch = Array(3) { FloatArray(n) }
        for (i in keep) {
            val (dx, dy) = bestShift(half[keep[0]], half[i]); val p = frames[i]
            for (y in 0 until OUT) {
                val sy = (y + 2 * dy).coerceIn(0, OUT - 1)
                for (x in 0 until OUT) {
                    val c = p[sy * OUT + (x + 2 * dx).coerceIn(0, OUT - 1)]; val k = y * OUT + x
                    ch[0][k] += (c shr 16 and 255).toFloat(); ch[1][k] += (c shr 8 and 255).toFloat(); ch[2][k] += (c and 255).toFloat()
                }
            }
        }
        for (c in 0..2) for (k in 0 until n) ch[c][k] /= keep.size
        say("শার্পনিং…")
        for (c in 0..2) {
            val a = ch[c]; val b1 = blur(a, 1); val b2 = blur(a, 2); val b3 = blur(a, 4)
            for (k in 0 until n) a[k] += sharp * (0.9f * (a[k] - b1[k]) + 0.7f * (b1[k] - b2[k]) + 0.4f * (b2[k] - b3[k]))
        }
        if (crater > 0f) {
            say("AI ক্রেটার ফিউশন…")
            val luma = FloatArray(n) { 0.299f * ch[0][it] + 0.587f * ch[1][it] + 0.114f * ch[2][it] }
            val delta = fuse(luma, crater)
            if (delta == null) say("মানানসই moon_ref*.jpg পাওয়া যায়নি, Real Mode-এ হলো")
            else for (c in 0..2) for (k in 0 until n) ch[c][k] += delta[k]
        }
        val inside = ArrayList<Float>()
        for (y in 0 until OUT) for (x in 0 until OUT) if (hypot(x - OUT / 2f, y - OUT / 2f) < R)
            inside.add(0.299f * ch[0][y * OUT + x] + 0.587f * ch[1][y * OUT + x] + 0.114f * ch[2][y * OUT + x])
        inside.sort()
        val gain = 240f / max(inside[(inside.size * 0.995f).toInt()], 1f)
        val px = IntArray(n)
        for (y in 0 until OUT) for (x in 0 until OUT) {
            val k = y * OUT + x
            val w = ((R * 1.06f - hypot(x - OUT / 2f, y - OUT / 2f)) / (R * 0.05f)).coerceIn(0f, 1f) * gain
            fun v(c: Int) = (ch[c][k] * w).toInt().coerceIn(0, 255)
            px[k] = Color.rgb(v(0), v(1), v(2))
        }
        return Bitmap.createBitmap(px, OUT, OUT, Bitmap.Config.ARGB_8888)
    }

    // ---------- Samsung-স্টাইল ক্রেটার ফিউশন ----------
    private fun lumaOf(b: Bitmap): FloatArray {
        val p = IntArray(OUT * OUT); b.getPixels(p, 0, OUT, 0, 0, OUT, OUT); return FloatArray(p.size) { lum(p[it]) }
    }

    private fun loadRef(name: String): Bitmap {  // রেফারেন্স: চাঁদের গোল ডিস্ক পুরো স্কোয়ার ছবি ভরে থাকবে
        val src = ctx.assets.open(name).use { BitmapFactory.decodeStream(it) }
        val o = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        Canvas(o).drawBitmap(Bitmap.createScaledBitmap(src, (2 * R).toInt(), (2 * R).toInt(), true), OUT / 2 - R, OUT / 2 - R, null)
        return o
    }

    private fun fuse(base: FloatArray, strength: Float): FloatArray? {
        val names = ctx.assets.list("")?.filter { it.startsWith("moon_ref") } ?: return null
        if (names.isEmpty()) return null
        val L = 128; val sc = OUT / L
        fun down(a: FloatArray) = FloatArray(L * L) { i ->
            var s = 0f; val x = (i % L) * sc; val y = (i / L) * sc
            for (j in 0 until sc) for (k in 0 until sc) s += a[(y + j) * OUT + x + k]
            s / (sc * sc)
        }
        val low = down(base); val lit = low.max() * 0.3f
        val c = (L - 1) / 2f; val rr = R / sc
        var bestNcc = -2f; var bestA = 0; var bestRef: Bitmap? = null
        for (nm in names) {
            val rb = loadRef(nm); val rl = down(lumaOf(rb))
            for (a in 0 until 360 step 2) {
                val ca = cos(Math.toRadians(a.toDouble())).toFloat(); val sa = sin(Math.toRadians(a.toDouble())).toFloat()
                var cnt = 0; var sb = 0f; var sr = 0f; var sbb = 0f; var srr = 0f; var sbr = 0f
                for (y in 0 until L) for (x in 0 until L) {
                    val dx = x - c; val dy = y - c
                    if (dx * dx + dy * dy > rr * rr * 0.9f) continue
                    val bv = low[y * L + x]; if (bv < lit) continue
                    val sx = (c + ca * dx - sa * dy + 0.5f).toInt(); val sy = (c + sa * dx + ca * dy + 0.5f).toInt()
                    if (sx !in 0 until L || sy !in 0 until L) continue
                    val rv = rl[sy * L + sx]
                    cnt++; sb += bv; sr += rv; sbb += bv * bv; srr += rv * rv; sbr += bv * rv
                }
                if (cnt < 200) continue
                val ncc = (sbr - sb * sr / cnt) / sqrt((sbb - sb * sb / cnt) * (srr - sr * sr / cnt) + 1e-6f)
                if (ncc > bestNcc) { bestNcc = ncc; bestA = a; bestRef = rb }
            }
        }
        val ref = bestRef ?: return null
        if (bestNcc < 0.3f) return null   // মিল কম হলে জোর করে গর্ত বসাবে না
        val rot = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        Canvas(rot).apply { rotate(-bestA.toFloat(), OUT / 2f, OUT / 2f); drawBitmap(ref, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
        val rf = lumaOf(rot)
        val refLow = blur(rf, 6); val baseLow = blur(base, 6)
        val energy = blur(FloatArray(base.size) { abs(base[it] - baseLow[it]) }, 8)
        val mx = baseLow.max()
        val delta = FloatArray(base.size)
        for (y in 0 until OUT) for (x in 0 until OUT) {
            val k = y * OUT + x
            val disc = ((R * 0.97f - hypot(x - OUT / 2f, y - OUT / 2f)) / 6f).coerceIn(0f, 1f)
            val litM = ((baseLow[k] - mx * 0.15f) / (mx * 0.15f)).coerceIn(0f, 1f)
            val need = (1f - energy[k] / 12f).coerceIn(0.25f, 1f)            // আসল ডিটেইল কম যেখানে, বেশি বসবে
            val shade = (baseLow[k] / (refLow[k] + 8f)).coerceIn(0.3f, 2.5f)  // আলো-ছায়ার সাথে মেলানো
            delta[k] = disc * litM * strength * need * shade * (rf[k] - refLow[k])
        }
        return delta
    }
}
