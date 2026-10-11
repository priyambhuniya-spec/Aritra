package com.example.mooncam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * বার্স্টের ফ্রেম থেকে চাঁদ খুঁজে, সেরা ফ্রেম বেছে, সাব-পিক্সেল অ্যালাইন করে স্ট্যাক করে
 * এবং শার্পেন করে। কোনো বাইরের লাইব্রেরি লাগে না।
 *
 * ধাপ: ১) প্রতি ফ্রেমে চাঁদ খোঁজা  ২) শার্পনেস স্কোর  ৩) সেরা ফ্রেম বাছাই
 *      ৪) অ্যালাইন + ড্রিজল-স্টাইল স্ট্যাকিং  ৫) মাল্টি-স্কেল শার্পেন + লেভেল
 */
object MoonStacker {

    class Result(
        val stacked: Bitmap,    // শুধু স্ট্যাক করা (প্রাকৃতিক)
        val enhanced: Bitmap,   // স্ট্যাক + শার্পেন + লেভেল
        val single: Bitmap,     // তুলনার জন্য সেরা একক ফ্রেম
        val used: Int,
        val total: Int
    )

    private class Frame(val file: File, val cx: Float, val cy: Float) {
        var pcx = cx
        var pcy = cy
        var sharp = 0.0
    }

    private class Accum(val r: FloatArray, val g: FloatArray, val b: FloatArray, val single: IntArray)

    private const val MAX_OUT = 1800

    fun process(
        files: List<File>,
        rotation: Int,
        keepRatio: Float,
        progress: (String) -> Unit
    ): Result? {
        // ধাপ ১: মোটামুটি অবস্থান
        val frames = ArrayList<Frame>()
        val extents = ArrayList<Float>()
        files.forEachIndexed { i, f ->
            progress("চাঁদ খোঁজা হচ্ছে ${i + 1}/${files.size}")
            findCoarse(f)?.let { frames.add(Frame(f, it[0], it[1])); extents.add(it[2]) }
        }
        if (frames.isEmpty()) return null
        val extent = extents.sorted()[extents.size / 2].coerceAtLeast(24f)
        val cropSide = (extent * 1.6f).toInt().coerceIn(200, 2400)

        // ধাপ ২: সূক্ষ্ম কেন্দ্র + শার্পনেস
        frames.forEachIndexed { i, fr ->
            progress("ফ্রেম বিশ্লেষণ ${i + 1}/${frames.size}")
            refine(fr, cropSide)
        }
        val usable = frames.filter { it.sharp > 0 }
        if (usable.isEmpty()) return null

        // ধাপ ৩: সেরা ফ্রেম
        val keep = ceil(usable.size * keepRatio).toInt().coerceIn(1, usable.size)
        val chosen = usable.sortedByDescending { it.sharp }.take(keep)

        // আউটপুট জ্যামিতি: ছোট চাঁদে বেশি স্কেল (ড্রিজল), বড় চাঁদে কম
        var scale = (1000f / extent).roundToInt().coerceIn(1, 4)
        val crop = (extent * 1.5f).toInt().coerceAtLeast(160)
        while (crop * scale > MAX_OUT && scale > 1) scale--
        val side = min(crop * scale, MAX_OUT)

        // ধাপ ৪ ও ৫
        val acc = accumulate(chosen, side, scale, progress)
        progress("শার্পেনিং ও কালার ঠিক করা হচ্ছে")
        return buildResult(acc, side, scale, rotation, chosen.size, files.size)
    }

    // ------------------------------------------------------------------
    // চাঁদ খোঁজা (কম রেজোলিউশনে)
    // ------------------------------------------------------------------
    private fun findCoarse(f: File): FloatArray? {
        val bmp = BitmapFactory.decodeFile(
            f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }
        ) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val luma = IntArray(w * h) { lum(px[it]) }

        // ১৬×১৬ ব্লকের গড়ে সবচেয়ে উজ্জ্বল জায়গা = চাঁদ (হট পিক্সেল গড়ে মিশে যায়)
        val blk = 16
        val bw = w / blk
        val bh = h / blk
        var best = -1f
        var bx = 0
        var by = 0
        for (j in 0 until bh) for (i in 0 until bw) {
            var s = 0
            for (y in j * blk until j * blk + blk) {
                val row = y * w
                for (x in i * blk until i * blk + blk) s += luma[row + x]
            }
            val m = s / (blk * blk).toFloat()
            if (m > best) { best = m; bx = i; by = j }
        }
        if (best < 25f) return null

        val thr = maxOf(25f, best * 0.4f)
        val half = min(w, h) / 2
        val cx0 = bx * blk + blk / 2
        val cy0 = by * blk + blk / 2
        val xa = maxOf(0, cx0 - half)
        val xb = min(w, cx0 + half)
        val ya = maxOf(0, cy0 - half)
        val yb = min(h, cy0 + half)

        val cols = IntArray(w)
        val rows = IntArray(h)
        var n = 0L
        var sx = 0L
        var sy = 0L
        for (y in ya until yb) {
            val row = y * w
            for (x in xa until xb) {
                if (luma[row + x] > thr) { cols[x]++; rows[y]++; n++; sx += x; sy += y }
            }
        }
        if (n < 15) return null

        val x0 = pctIndex(cols, n, 0.005)
        val x1 = pctIndex(cols, n, 0.995)
        val y0 = pctIndex(rows, n, 0.005)
        val y1 = pctIndex(rows, n, 0.995)
        val extent = (maxOf(x1 - x0, y1 - y0) + 1) * 2f   // পূর্ণ রেজোলিউশনের মাপে
        return floatArrayOf(sx.toFloat() / n * 2f, sy.toFloat() / n * 2f, extent)
    }

    private fun pctIndex(counts: IntArray, total: Long, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in counts.indices) {
            acc += counts[i]
            if (acc >= target) return i
        }
        return counts.size - 1
    }

    // ------------------------------------------------------------------
    // সূক্ষ্ম কেন্দ্র ও শার্পনেস (পূর্ণ রেজোলিউশনের ক্রপে)
    // ------------------------------------------------------------------
    @Suppress("DEPRECATION")
    private fun refine(fr: Frame, cropSide: Int) {
        val dec = BitmapRegionDecoder.newInstance(fr.file.absolutePath, false) ?: return
        try {
            val half = cropSide / 2
            val left = (fr.cx - half).toInt().coerceAtLeast(0)
            val right = (fr.cx + half).toInt().coerceAtMost(dec.width)
            val top = (fr.cy - half).toInt().coerceAtLeast(0)
            val bottom = (fr.cy + half).toInt().coerceAtMost(dec.height)
            if (right - left < 16 || bottom - top < 16) return

            val bmp = dec.decodeRegion(Rect(left, top, right, bottom), null) ?: return
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val luma = IntArray(w * h) { lum(px[it]) }

            val hist = IntArray(256)
            for (v in luma) hist[v]++
            val p99 = pctIndex(hist, luma.size.toLong(), 0.99)
            val thr = maxOf(20f, p99 * 0.4f)

            var n = 0L
            var sx = 0L
            var sy = 0L
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) if (luma[row + x] > thr) { n++; sx += x; sy += y }
            }
            if (n < 30) return

            // শার্পনেস = চাঁদের এলাকায় স্মুথ করা লাপলাসিয়ানের গড় বর্গ (নয়েজে ধোঁকা না খেতে ৩×৩ স্মুথ)
            var acc = 0.0
            var cnt = 0
            var y = 3
            while (y < h - 3) {
                var x = 3
                while (x < w - 3) {
                    if (luma[y * w + x] > thr) {
                        val lap = 4f * sm(luma, w, x, y) - sm(luma, w, x - 1, y) - sm(luma, w, x + 1, y) -
                                sm(luma, w, x, y - 1) - sm(luma, w, x, y + 1)
                        acc += lap * lap
                        cnt++
                    }
                    x += 2
                }
                y += 2
            }
            if (cnt == 0) return
            fr.sharp = acc / cnt
            fr.pcx = left + sx.toFloat() / n
            fr.pcy = top + sy.toFloat() / n
        } finally {
            dec.recycle()
        }
    }

    private fun sm(l: IntArray, w: Int, x: Int, y: Int): Float {
        var s = 0
        for (dy in -1..1) {
            val r = (y + dy) * w
            for (dx in -1..1) s += l[r + x + dx]
        }
        return s / 9f
    }

    // ------------------------------------------------------------------
    // অ্যালাইন + স্ট্যাকিং (বাইলিনিয়ার, স্কেল-আপ গ্রিডে)
    // ------------------------------------------------------------------
    @Suppress("DEPRECATION")
    private fun accumulate(chosen: List<Frame>, side: Int, scale: Int, progress: (String) -> Unit): Accum {
        val n = side * side
        val sr = FloatArray(n)
        val sg = FloatArray(n)
        val sb = FloatArray(n)
        val cnt = FloatArray(n)
        val single = IntArray(n) { -0x1000000 }
        val half = side / (2 * scale) + 4
        val c = side / 2f

        chosen.forEachIndexed { k, fr ->
            progress("স্ট্যাকিং ${k + 1}/${chosen.size}")
            val dec = BitmapRegionDecoder.newInstance(fr.file.absolutePath, false) ?: return@forEachIndexed
            val left = (fr.pcx - half).toInt().coerceAtLeast(0)
            val right = (fr.pcx + half).toInt().coerceAtMost(dec.width)
            val top = (fr.pcy - half).toInt().coerceAtLeast(0)
            val bottom = (fr.pcy + half).toInt().coerceAtMost(dec.height)
            val bmp = if (right - left > 8 && bottom - top > 8) dec.decodeRegion(Rect(left, top, right, bottom), null) else null
            dec.recycle()
            if (bmp == null) return@forEachIndexed

            val rw = bmp.width
            val rh = bmp.height
            val px = IntArray(rw * rh)
            bmp.getPixels(px, 0, rw, 0, 0, rw, rh)
            bmp.recycle()

            val ox = fr.pcx - left
            val oy = fr.pcy - top
            for (v in 0 until side) {
                val sy = oy + (v - c) / scale
                val iy = floor(sy).toInt()
                if (iy < 0 || iy + 1 >= rh) continue
                val fy = sy - iy
                for (u in 0 until side) {
                    val sx = ox + (u - c) / scale
                    val ix = floor(sx).toInt()
                    if (ix < 0 || ix + 1 >= rw) continue
                    val fx = sx - ix
                    val a = iy * rw + ix
                    val b = a + rw
                    val p00 = px[a]; val p10 = px[a + 1]; val p01 = px[b]; val p11 = px[b + 1]
                    val w00 = (1 - fx) * (1 - fy)
                    val w10 = fx * (1 - fy)
                    val w01 = (1 - fx) * fy
                    val w11 = fx * fy
                    val r = ((p00 shr 16) and 255) * w00 + ((p10 shr 16) and 255) * w10 +
                            ((p01 shr 16) and 255) * w01 + ((p11 shr 16) and 255) * w11
                    val g = ((p00 shr 8) and 255) * w00 + ((p10 shr 8) and 255) * w10 +
                            ((p01 shr 8) and 255) * w01 + ((p11 shr 8) and 255) * w11
                    val bl = (p00 and 255) * w00 + (p10 and 255) * w10 +
                            (p01 and 255) * w01 + (p11 and 255) * w11
                    val i = v * side + u
                    sr[i] += r; sg[i] += g; sb[i] += bl; cnt[i] += 1f
                    if (k == 0) single[i] = pack(r, g, bl)
                }
            }
        }
        for (i in 0 until n) {
            val cc = cnt[i]
            if (cc > 0f) { sr[i] /= cc; sg[i] /= cc; sb[i] /= cc }
        }
        return Accum(sr, sg, sb, single)
    }

    // ------------------------------------------------------------------
    // শার্পেন + লেভেল + বিটম্যাপ
    // ------------------------------------------------------------------
    private fun buildResult(a: Accum, side: Int, scale: Int, rotation: Int, used: Int, total: Int): Result {
        val n = side * side
        val stacked = IntArray(n) { pack(a.r[it], a.g[it], a.b[it]) }

        val y = FloatArray(n) { 0.299f * a.r[it] + 0.587f * a.g[it] + 0.114f * a.b[it] }
        val fine = blur(y, side, side, 0.9f * scale)    // সূক্ষ্ম ডিটেইল (পাহাড়, গর্তের কিনারা)
        val broad = blur(y, side, side, 4f * scale)     // মাঝারি কনট্রাস্ট (সমুদ্র বনাম উচ্চভূমি)
        val delta = FloatArray(n) { 1.2f * (y[it] - fine[it]) + 0.35f * (y[it] - broad[it]) }

        // লেভেল: আকাশ কালো, চাঁদের সবচেয়ে উজ্জ্বল অংশ ~৮৫–৯৫%
        val hist = IntArray(1024)
        for (i in 0 until n) hist[((y[i] + delta[i]).coerceIn(0f, 255.9f) * 4).toInt()]++
        val black = pctIndex(hist, n.toLong(), 0.20) / 4f
        val white = pctIndex(hist, n.toLong(), 0.997) / 4f
        val gain = (245f / (white - black).coerceAtLeast(30f)).coerceAtMost(3f)

        val enhanced = IntArray(n) {
            pack(
                (a.r[it] + delta[it] - black) * gain,
                (a.g[it] + delta[it] - black) * gain,
                (a.b[it] + delta[it] - black) * gain
            )
        }

        return Result(
            toBitmap(stacked, side, rotation),
            toBitmap(enhanced, side, rotation),
            toBitmap(a.single, side, rotation),
            used, total
        )
    }

    private fun toBitmap(px: IntArray, side: Int, rotation: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
        if (rotation % 360 == 0) return bmp
        val rotated = Bitmap.createBitmap(
            bmp, 0, 0, side, side, Matrix().apply { postRotate(rotation.toFloat()) }, true
        )
        bmp.recycle()
        return rotated
    }

    // গাউসিয়ান ব্লারের কাছাকাছি: ৩ বার বক্স ব্লার (দ্রুত, বড় সিগমাতেও)
    private fun blur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val boxW = sqrt(12f * sigma * sigma / 3f + 1f)
        val r = ((boxW - 1f) / 2f).roundToInt().coerceAtLeast(1)
        var a = src.copyOf()
        val b = FloatArray(src.size)
        repeat(3) {
            boxPass(a, b, w, h, r, true)
            boxPass(b, a, w, h, r, false)
        }
        return a
    }

    private fun boxPass(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val stride = if (horizontal) 1 else w
        val inv = 1f / (2 * r + 1)
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var acc = 0f
            for (i in -r..r) acc += src[base + i.coerceIn(0, len - 1) * stride]
            for (i in 0 until len) {
                dst[base + i * stride] = acc * inv
                val add = (i + r + 1).coerceAtMost(len - 1)
                val sub = (i - r).coerceAtLeast(0)
                acc += src[base + add * stride] - src[base + sub * stride]
            }
        }
    }

    private fun lum(c: Int): Int =
        (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
