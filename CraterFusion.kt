package com.moonlens   // <-- আপনার প্রজেক্টের package-এর সাথে মিলিয়ে বদলান

import android.content.Context
import android.graphics.*
import kotlin.math.*

/**
 * Samsung-স্টাইল AI ক্রেটার ফিউশন (স্বয়ংসম্পূর্ণ, একটাই ফাইল)।
 * ইনপুট: স্ট্যাক করা চাঁদের Bitmap (যেকোনো সাইজ, চাঁদ ফ্রেমে থাকলেই হবে)।
 * assets/ ফোল্ডারে moon_ref1.jpg, moon_ref2.jpg ... লাগবে (চাঁদের গোল ডিস্ক স্কোয়ার ছবির চার কিনারা ছুঁয়ে থাকবে)।
 *
 * কাজের ধাপ:
 * 1) চাঁদ খুঁজে মাঝখানে এনে নরমালাইজ  2) সব রেফারেন্সের সাথে ঘূর্ণন (০–৩৫৮°) + স্কেল (±৫%) মিলিয়ে সেরা মিল বাছা
 * 3) রেফারেন্সের হাই-ফ্রিকোয়েন্সি ডিটেইল (গর্ত, রশ্মি) আলাদা করা  4) যেখানে আসল ডিটেইল কম সেখানে বেশি, আলো-ছায়া মিলিয়ে বসানো
 * মিল খুব কম (<0.3) হলে null ফেরত দেয়, জোর করে ভুল গর্ত বসায় না।
 */
object CraterFusion {
    class Result(val image: Bitmap, val matchScore: Float, val refName: String)

    private fun lum(c: Int) = 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)

    /** size অবশ্যই ১২৮-এর গুণিতক (৭৬৮, ১০২৪, ১৫৩৬)। strength: ০..১ */
    fun apply(ctx: Context, input: Bitmap, strength: Float = 0.7f, size: Int = 1024, say: (String) -> Unit = {}): Result? {
        require(size % 128 == 0)
        val S = size; val n = S * S; val R = S / 2.4f; val k = S / 768f
        fun rad(v: Int) = max(1, (v * k).roundToInt())

        val names = ctx.assets.list("")?.filter { it.startsWith("moon_ref") }.orEmpty()
        if (names.isEmpty()) { say("assets-এ moon_ref*.jpg নেই"); return null }

        // ---- ১) চাঁদ খোঁজা ও নরমালাইজ ----
        val (cx, cy, mr) = findMoon(input) ?: run { say("চাঁদ পাওয়া যায়নি"); return null }
        val kk = S / (mr * 2.4f)
        val mx = Matrix().apply { postTranslate(-cx, -cy); postScale(kk, kk); postTranslate(S / 2f, S / 2f) }
        val norm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        Canvas(norm).drawBitmap(input, mx, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(n); norm.getPixels(px, 0, S, 0, 0, S, S)
        val base = FloatArray(n) { lum(px[it]) }

        // ---- ২) সেরা রেফারেন্স + ঘূর্ণন + স্কেল ----
        say("রেফারেন্সের সাথে মেলানো হচ্ছে…")
        val L = 128; val sc = S / L; val c = (L - 1) / 2f; val rr = R / sc
        fun down(a: FloatArray) = FloatArray(L * L) { i ->
            var s = 0f; val x = (i % L) * sc; val y = (i / L) * sc
            for (j in 0 until sc) for (q in 0 until sc) s += a[(y + j) * S + x + q]
            s / (sc * sc)
        }
        val low = down(base); val lit = low.max() * 0.3f
        var bestNcc = -2f; var bestA = 0; var bestS = 1f; var bestRef: Bitmap? = null; var bestName = ""
        for (nm in names) {
            val src = ctx.assets.open(nm).use { BitmapFactory.decodeStream(it) } ?: continue
            val rb = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
            Canvas(rb).drawBitmap(Bitmap.createScaledBitmap(src, (2 * R).toInt(), (2 * R).toInt(), true), S / 2f - R, S / 2f - R, null)
            val rp = IntArray(n); rb.getPixels(rp, 0, S, 0, 0, S, S)
            val rl = down(FloatArray(n) { lum(rp[it]) })
            for (s in floatArrayOf(0.95f, 1f, 1.05f)) for (a in 0 until 360 step 2) {
                val ca = cos(Math.toRadians(a.toDouble())).toFloat() / s; val sa = sin(Math.toRadians(a.toDouble())).toFloat() / s
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
                if (ncc > bestNcc) { bestNcc = ncc; bestA = a; bestS = s; bestRef = rb; bestName = nm }
            }
        }
        val ref = bestRef
        if (ref == null || bestNcc < 0.3f) { say("মানানসই রেফারেন্স নেই, ক্রেটার বসানো হয়নি"); return null }

        // ---- ৩) রেফারেন্স ঘুরিয়ে বসানো ----
        val rot = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { postScale(bestS, bestS, S / 2f, S / 2f); postRotate(-bestA.toFloat(), S / 2f, S / 2f) }
        Canvas(rot).drawBitmap(ref, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val rpx = IntArray(n); rot.getPixels(rpx, 0, S, 0, 0, S, S)
        val rf = FloatArray(n) { lum(rpx[it]) }

        // ---- ৪) অ্যাডাপ্টিভ ডিটেইল ট্রান্সফার ----
        say("গর্তের ডিটেইল বসানো হচ্ছে…")
        val refLow = blur(rf, S, rad(6)); val baseLow = blur(base, S, rad(6))
        val energy = blur(FloatArray(n) { abs(base[it] - baseLow[it]) }, S, rad(8))
        val bmax = baseLow.max()
        val out = IntArray(n)
        for (y in 0 until S) for (x in 0 until S) {
            val i = y * S + x
            val disc = ((R * 0.97f - hypot(x - S / 2f, y - S / 2f)) / (6f * k)).coerceIn(0f, 1f)
            val litM = ((baseLow[i] - bmax * 0.15f) / (bmax * 0.15f)).coerceIn(0f, 1f)
            val need = (1f - energy[i] / 12f).coerceIn(0.25f, 1f)
            val shade = (baseLow[i] / (refLow[i] + 8f)).coerceIn(0.3f, 2.5f)
            val d = disc * litM * strength * need * shade * (rf[i] - refLow[i])
            val p = px[i]
            fun v(sh: Int) = ((p shr sh and 255) + d).toInt().coerceIn(0, 255)
            out[i] = Color.rgb(v(16), v(8), v(0))
        }
        return Result(Bitmap.createBitmap(out, S, S, Bitmap.Config.ARGB_8888), bestNcc, bestName)
    }

    private fun findMoon(b: Bitmap): Triple<Float, Float, Float>? {
        val sc = max(1, max(b.width, b.height) / 400)
        val sw = b.width / sc; val sh = b.height / sc
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val p = IntArray(sw * sh); s.getPixels(p, 0, sw, 0, 0, sw, sh)
        val l = FloatArray(p.size) { lum(p[it]) }
        val srt = l.sortedArray(); val thr = srt[(srt.size * 0.999).toInt()] * 0.5f
        if (thr < 40f) return null
        val rows = IntArray(sh); val cols = IntArray(sw)
        for (y in 0 until sh) for (x in 0 until sw) if (l[y * sw + x] > thr) { rows[y]++; cols[x]++ }
        val x0 = cols.indexOfFirst { it >= 3 }; val x1 = cols.indexOfLast { it >= 3 }
        val y0 = rows.indexOfFirst { it >= 3 }; val y1 = rows.indexOfLast { it >= 3 }
        if (x0 < 0 || y0 < 0 || x1 - x0 < 10 || y1 - y0 < 10) return null
        return Triple((x0 + x1 + 1) / 2f * sc, (y0 + y1 + 1) / 2f * sc, max(x1 - x0 + 1, y1 - y0 + 1) / 2f * sc)
    }

    private fun blur(a: FloatArray, w: Int, r: Int) = box(box(a, w, r), w, r)
    private fun box(src: FloatArray, w: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val o = FloatArray(src.size); val d = 2f * r + 1f
        for (y in 0 until w) {
            var s = 0f; for (x in -r..r) s += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[y * w + x] = s / d; s += src[y * w + min(x + r + 1, w - 1)] - src[y * w + max(x - r, 0)] }
        }
        for (x in 0 until w) {
            var s = 0f; for (y in -r..r) s += tmp[y.coerceIn(0, w - 1) * w + x]
            for (y in 0 until w) { o[y * w + x] = s / d; s += tmp[min(y + r + 1, w - 1) * w + x] - tmp[max(y - r, 0) * w + x] }
        }
        return o
    }
}
