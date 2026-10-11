package com.moonlens   // <-- আপনার প্রজেক্টের package-এর সাথে মিলিয়ে বদলান

import android.content.Context
import android.graphics.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * ট্রেন করা নিউরাল নেটওয়ার্ক দিয়ে চাঁদের ডিটেইল তৈরি (Samsung-স্টাইল)।
 * assets/moon_enhancer.tflite লাগবে (MoonNeural_Training.ipynb চালিয়ে বানানো)।
 * app/build.gradle.kts-এ যোগ করুন:  implementation("org.tensorflow:tensorflow-lite:2.14.0")
 * মডেল না থাকলে null ফেরত দেয়।
 */
object MoonNeural {
    private const val N = 512               // মডেলের ইনপুট সাইজ (নোটবুকের S-এর সমান)
    private var interp: Interpreter? = null

    private fun lum(c: Int) = 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)

    private fun model(ctx: Context): Interpreter? {
        interp?.let { return it }
        return try {
            val bytes = ctx.assets.open("moon_enhancer.tflite").use { it.readBytes() }
            val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()); bb.put(bytes); bb.rewind()
            Interpreter(bb, Interpreter.Options().setNumThreads(4)).also { interp = it }
        } catch (e: Exception) { null }
    }

    /** strength ০..১ (১ = পুরো নিউরাল আউটপুট, ০.৫ = আপনার ছবির সাথে অর্ধেক মিশ্রণ) */
    fun apply(ctx: Context, input: Bitmap, strength: Float = 1f, size: Int = 1024, say: (String) -> Unit = {}): Bitmap? {
        val m = model(ctx) ?: run { say("assets-এ moon_enhancer.tflite নেই"); return null }
        val S = size; val n = S * S; val R = S / 2.4f; val k = S / 768f
        val mm = findMoon(input) ?: run { say("চাঁদ পাওয়া যায়নি"); return null }
        val kk = S / (mm.third * 2.4f)
        val mat = Matrix().apply { postTranslate(-mm.first, -mm.second); postScale(kk, kk); postTranslate(S / 2f, S / 2f) }
        val norm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        Canvas(norm).drawBitmap(input, mat, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(n); norm.getPixels(px, 0, S, 0, 0, S, S)

        say("নিউরাল নেটওয়ার্ক চলছে…")
        val small = Bitmap.createScaledBitmap(norm, N, N, true)
        val sp = IntArray(N * N); small.getPixels(sp, 0, N, 0, 0, N, N)
        val sl = FloatArray(N * N) { lum(sp[it]) }
        val rN = N / 2.4f
        val inside = ArrayList<Float>()
        for (y in 0 until N) for (x in 0 until N) if (hypot(x - N / 2f, y - N / 2f) < rN * 0.99f) inside.add(sl[y * N + x])
        inside.sort()
        val p = max(inside[(inside.size * 0.995f).toInt()], 1f)
        val scale = 0.94f / (p / 255f) / 255f      // ট্রেনিংয়ের নরমালাইজেশনের সাথে হুবহু এক

        val inBuf = ByteBuffer.allocateDirect(N * N * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until N * N) inBuf.putFloat((sl[i] * scale).coerceIn(0f, 1f))
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(N * N * 4).order(ByteOrder.nativeOrder())
        m.run(inBuf, outBuf); outBuf.rewind()
        val real = IntArray(N * N) { i ->
            val v = (outBuf.getFloat().coerceIn(0f, 1f) / scale).toInt().coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        val up = Bitmap.createScaledBitmap(Bitmap.createBitmap(real, N, N, Bitmap.Config.ARGB_8888), S, S, true)
        val upx = IntArray(n); up.getPixels(upx, 0, S, 0, 0, S, S)

        val out = IntArray(n)
        for (y in 0 until S) for (x in 0 until S) {
            val i = y * S + x
            val dsk = ((R * 0.97f - hypot(x - S / 2f, y - S / 2f)) / (6f * k)).coerceIn(0f, 1f)
            val d = dsk * strength * ((upx[i] and 255) - lum(px[i]))
            val c = px[i]
            fun v(sh: Int) = ((c shr sh and 255) + d).toInt().coerceIn(0, 255)
            out[i] = Color.rgb(v(16), v(8), v(0))
        }
        return Bitmap.createBitmap(out, S, S, Bitmap.Config.ARGB_8888)
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
}
