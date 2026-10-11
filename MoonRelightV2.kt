package com.moonlens   // <-- আপনার প্রজেক্টের package-এর সাথে মিলিয়ে বদলান

import android.content.Context
import android.graphics.*
import kotlin.math.*

/**
 * MoonRelight V2: উচ্চতা-ম্যাপ (DEM) দিয়ে চাঁদের আলো-ছায়া নতুন করে বানিয়ে আপনার ছবিতে ডিটেইল বসানো।
 * V1 থেকে যা নতুন:
 *  ১) অটো libration: তারিখ-সময় থেকে চাঁদের দোলন নিজে হিসাব (সাইন অনিশ্চিত, তাই সব সম্ভাবনা যাচাই করে সেরাটা নেয়)
 *  ২) নিখুঁত মেলানো: ঘূর্ণন + libration + স্কেল + সরানো, ছয়টা প্যারামিটার একসাথে অপটিমাইজ (দুই ধাপে, মোটা থেকে সূক্ষ্ম)
 *  ৩) cast shadow: পাহাড়/গর্তের দেয়ালের ছায়া অন্য জায়গায় পড়ার হিসাব (ray marching)
 *  ৪) পূর্ণিমায় ভার্চুয়াল তেরছা আলো: আসল ছবিতে ছায়া নেই, তাই দুই দিক থেকে কাল্পনিক আলো ফেলে গর্ত ফোটানো
 *  ৫) ছোট গর্ত: ম্যাপে নেই এমন ছোট গর্ত কৃত্রিমভাবে বানানো (আসল না, বানানো, তাই বন্ধ করা যায়)
 *
 * assets/ লাগবে: moon_dem.jpg ও moon_albedo.jpg (equirectangular, ২:১ আয়তক্ষেত্র, লং -১৮০..১৮০)
 */
object MoonRelightV2 {
    class Result(val image: Bitmap, val matchScore: Float, val phaseDeg: Float,
                 val libLonDeg: Float, val libLatDeg: Float, val virtualLight: Boolean)

    const val DEM_MIN_KM = -9f      // DEM-এর ০ মান = কত কিমি (ম্যাপ অনুযায়ী বদলান)
    const val DEM_MAX_KM = 11f      // DEM-এর ২৫৫ মান = কত কিমি
    private const val MOON_R_KM = 1737.4f
    private val PIf = PI.toFloat()

    private class Grid(val w: Int, val h: Int, val v: FloatArray) {
        fun at(lon: Float, lat: Float): Float {
            val uu = (lon / (2 * PIf) + 0.5f) * w
            val u = ((uu % w) + w) % w
            val vv = ((0.5f - lat / PIf) * h).coerceIn(0f, h - 1.001f)
            val x0 = u.toInt() % w; val x1 = (x0 + 1) % w; val y0 = vv.toInt()
            val fx = u - u.toInt(); val fy = vv - y0
            return (v[y0 * w + x0] * (1 - fx) + v[y0 * w + x1] * fx) * (1 - fy) +
                   (v[(y0 + 1) * w + x0] * (1 - fx) + v[(y0 + 1) * w + x1] * fx) * fy
        }
    }

    private fun lum(c: Int) = 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)

    private fun load(ctx: Context, name: String?): Grid? {
        if (name == null) return null
        return try {
            var b = ctx.assets.open(name).use { BitmapFactory.decodeStream(it) } ?: return null
            if (b.width > 4096) b = Bitmap.createScaledBitmap(b, 4096, b.height * 4096 / b.width, true)
            val p = IntArray(b.width * b.height); b.getPixels(p, 0, b.width, 0, 0, b.width, b.height)
            Grid(b.width, b.height, FloatArray(p.size) { lum(p[it]) })
        } catch (e: Exception) { null }
    }

    /** Meeus (Astronomical Algorithms, ch. 47 ও 53) অনুযায়ী অপটিক্যাল libration: (দ্রাঘিমা, অক্ষাংশ) ডিগ্রিতে */
    fun libration(utcMillis: Long): Pair<Float, Float> {
        val jd = utcMillis / 86400000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        fun d(a: Double, b: Double) = Math.toRadians((a + b * t) % 360.0)
        val lp = 218.3164477 + 481267.88123421 * t
        val dd = d(297.8501921, 445267.1114034); val m = d(357.5291092, 35999.0502909)
        val mp = d(134.9633964, 477198.8675055); val f = d(93.2720950, 483202.0175233)
        val sl = 6288774 * sin(mp) + 1274027 * sin(2 * dd - mp) + 658314 * sin(2 * dd) + 213618 * sin(2 * mp) -
                185116 * sin(m) - 114332 * sin(2 * f) + 58793 * sin(2 * dd - 2 * mp) + 57066 * sin(2 * dd - m - mp) +
                53322 * sin(2 * dd + mp) + 45758 * sin(2 * dd - m) - 40923 * sin(m - mp) - 34720 * sin(dd) - 30383 * sin(m + mp)
        val sb = 5128122 * sin(f) + 280602 * sin(mp + f) + 277693 * sin(mp - f) + 173237 * sin(2 * dd - f) +
                55413 * sin(2 * dd - mp + f) + 46271 * sin(2 * dd - mp - f) + 32573 * sin(2 * dd + f)
        val lam = Math.toRadians((lp + sl / 1e6) % 360.0); val bet = Math.toRadians(sb / 1e6)
        val om = Math.toRadians((125.0445479 - 1934.1362891 * t) % 360.0)
        val inc = Math.toRadians(1.54242); val w = lam - om
        val a = atan2(sin(w) * cos(bet) * cos(inc) - sin(bet) * sin(inc), cos(w) * cos(bet))
        var l = Math.toDegrees(a - f) % 360.0; if (l > 180) l -= 360; if (l < -180) l += 360
        val b = Math.toDegrees(asin((-sin(w) * cos(bet) * sin(inc) - sin(bet) * cos(inc)).coerceIn(-1.0, 1.0)))
        return l.toFloat() to b.toFloat()
    }

    /** পোজ: [ঘূর্ণন°, lon0°, lat0°, স্কেল, সরানো-x px, সরানো-y px] */
    private class Pose(val p: FloatArray) {
        val ca = cos(Math.toRadians(p[0].toDouble())).toFloat(); val sa = sin(Math.toRadians(p[0].toDouble())).toFloat()
        val cl0 = cos(Math.toRadians(p[2].toDouble())).toFloat(); val sl0 = sin(Math.toRadians(p[2].toDouble())).toFloat()
        val lon0 = Math.toRadians(p[1].toDouble()).toFloat()
    }

    fun apply(ctx: Context, input: Bitmap, strength: Float = 0.8f, size: Int = 1024,
              utcMillis: Long = System.currentTimeMillis(), reliefGain: Float = 1.2f,
              smallCraters: Float = 0.5f, virtualPhaseDeg: Float = 40f, virtualAzDeg: Float = 135f,
              say: (String) -> Unit = {}): Result? {
        require(size % 256 == 0)
        val S = size; val n = S * S; val R = S / 2.4f; val k = S / 768f; val cen = S / 2f
        fun rad(v: Int) = max(1, (v * k).roundToInt())
        val names = ctx.assets.list("").orEmpty()
        val dem = load(ctx, names.firstOrNull { it.startsWith("moon_dem") })
        val alb = load(ctx, names.firstOrNull { it.startsWith("moon_albedo") })
        if (dem == null || alb == null) { say("assets-এ moon_dem.jpg ও moon_albedo.jpg দিন"); return null }

        // ---- চাঁদ খোঁজা ও নরমালাইজ ----
        val (mcx, mcy, mr) = findMoon(input) ?: run { say("চাঁদ পাওয়া যায়নি"); return null }
        val kk = S / (mr * 2.4f)
        val mat = Matrix().apply { postTranslate(-mcx, -mcy); postScale(kk, kk); postTranslate(cen, cen) }
        val norm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        Canvas(norm).drawBitmap(input, mat, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(n); norm.getPixels(px, 0, S, 0, 0, S, S)
        val base = FloatArray(n) { lum(px[it]) }
        val baseLow = blur(base, S, rad(6)); val bmax = baseLow.max()

        // ---- ফেজ ও আসল আলোর দিক ----
        say("ফেজ ও আলোর দিক মাপা হচ্ছে…")
        var disc = 0; var litN = 0; var sx = 0f; var sy = 0f
        for (y in 0 until S) for (x in 0 until S) if (hypot(x - cen, y - cen) < R * 0.97f) {
            disc++; if (baseLow[y * S + x] > bmax * 0.2f) { litN++; sx += x - cen; sy += y - cen }
        }
        if (litN < 100) { say("চাঁদের আলোকিত অংশ খুব কম"); return null }
        val alpha = acos((2f * litN / disc - 1f).coerceIn(-1f, 1f))
        val mxm = sx / litN; val mym = sy / litN; val dn = hypot(mxm, mym)
        val azReal = if (dn > 0.02f * R) atan2(-mym, mxm) else 0f
        val rs0 = sin(alpha) * cos(azReal); val rs1 = sin(alpha) * sin(azReal); val rs2 = cos(alpha)

        // ---- পোজ অপটিমাইজেশন ----
        say("ম্যাপ নিখুঁতভাবে মেলানো হচ্ছে…")
        val g = FloatArray(5)
        fun geo(x: Float, y: Float, ps: Pose, o: FloatArray): Boolean {
            val nx = (x - cen - ps.p[4]) / (R * ps.p[3]); val yy = -(y - cen - ps.p[5]) / (R * ps.p[3])
            val r2 = nx * nx + yy * yy
            if (r2 >= 0.995f) return false
            val z = sqrt(1f - r2)
            val xr = nx * ps.ca - yy * ps.sa; val yr = nx * ps.sa + yy * ps.ca
            o[0] = ps.lon0 + atan2(xr, z * ps.cl0 - yr * ps.sl0)
            o[1] = asin((yr * ps.cl0 + z * ps.sl0).coerceIn(-1f, 1f))
            o[2] = nx; o[3] = yy; o[4] = z
            return true
        }
        fun down(L: Int): FloatArray {
            val sc = S / L
            return FloatArray(L * L) { i ->
                var s = 0f; val x = (i % L) * sc; val y = (i / L) * sc
                for (j in 0 until sc) for (q in 0 until sc) s += base[(y + j) * S + x + q]
                s / (sc * sc)
            }
        }
        class Lvl(val L: Int, val ph: FloatArray) { val litT = ph.max() * 0.3f }
        val l1 = Lvl(128, down(128)); val l2 = Lvl(256, down(256))
        fun ncc(p: FloatArray, lv: Lvl): Float {
            val ps = Pose(p); val sc = S / lv.L
            var cnt = 0; var sb = 0f; var sr = 0f; var sbb = 0f; var srr = 0f; var sbr = 0f
            for (i in 0 until lv.L * lv.L) {
                if (lv.ph[i] <= lv.litT) continue
                if (!geo(((i % lv.L) + 0.5f) * sc, ((i / lv.L) + 0.5f) * sc, ps, g)) continue
                if (g[2] * g[2] + g[3] * g[3] > 0.81f) continue
                val m0 = max(0f, g[2] * rs0 + g[3] * rs1 + g[4] * rs2)
                val rv = 2f * m0 / (m0 + g[4]) * alb.at(g[0], g[1]); val bv = lv.ph[i]
                cnt++; sb += bv; sr += rv; sbb += bv * bv; srr += rv * rv; sbr += bv * rv
            }
            if (cnt < 200) return -2f
            return (sbr - sb * sr / cnt) / sqrt((sbb - sb * sb / cnt) * (srr - sr * sr / cnt) + 1e-6f)
        }
        val (lb, bb) = libration(utcMillis)
        val cands = listOf(lb to bb, -lb to bb, lb to -bb, -lb to -bb, 0f to 0f)
        var best = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f); var bestS = -2f
        for ((cl, cb) in cands) for (a in 0 until 360 step 3) {
            val p = floatArrayOf(a.toFloat(), cl, cb, 1f, 0f, 0f)
            val sc = ncc(p, l1); if (sc > bestS) { bestS = sc; best = p }
        }
        for (lv in listOf(l1, l2)) {
            val st = floatArrayOf(2f, 1.5f, 1.5f, 0.015f, 4f, 4f)
            repeat(14) {
                var improved = false
                for (d in 0 until 6) for (sg in intArrayOf(1, -1)) {
                    val p = best.copyOf(); p[d] += sg * st[d]; p[3] = p[3].coerceIn(0.94f, 1.06f)
                    val sc = ncc(p, lv); if (sc > bestS) { bestS = sc; best = p; improved = true }
                }
                if (!improved) for (d in 0 until 6) st[d] *= 0.5f
            }
        }
        if (bestS < 0.30f) { say("ম্যাপ মেলেনি, কিছু বসানো হয়নি"); return null }
        val pose = Pose(best)

        // ---- পূর্ণ রেজোলিউশনে ম্যাপ নমুনা (আলবেডো + উচ্চতা) ----
        say("৩D চাঁদ বানানো হচ্ছে…")
        val kmPx = MOON_R_KM / (R * pose.p[3])
        val A = FloatArray(n); val H = FloatArray(n); val valid = BooleanArray(n)
        val NX = FloatArray(n); val NY = FloatArray(n); val NZ = FloatArray(n)
        for (y in 0 until S) for (x in 0 until S) {
            if (!geo(x.toFloat(), y.toFloat(), pose, g)) continue
            val i = y * S + x; valid[i] = true
            A[i] = alb.at(g[0], g[1]); H[i] = DEM_MIN_KM + dem.at(g[0], g[1]) / 255f * (DEM_MAX_KM - DEM_MIN_KM)
            NX[i] = g[2]; NY[i] = g[3]; NZ[i] = g[4]
        }
        if (smallCraters > 0f) {
            val rnd = java.util.Random(12345)
            repeat(3000) {
                val u = rnd.nextFloat().coerceAtLeast(0.02f)
                val r = min(2.5f / sqrt(u), 16f) * k
                val ang = rnd.nextFloat() * 2f * PIf; val rd = sqrt(rnd.nextFloat()) * R * 0.93f
                val cx = cen + rd * cos(ang); val cy = cen + rd * sin(ang)
                val depth = smallCraters * 0.4f * r * kmPx
                val x0 = max(0, (cx - 1.7f * r).toInt()); val x1 = min(S - 1, (cx + 1.7f * r).toInt())
                val y0 = max(0, (cy - 1.7f * r).toInt()); val y1 = min(S - 1, (cy + 1.7f * r).toInt())
                for (y in y0..y1) for (x in x0..x1) {
                    val i = y * S + x; if (!valid[i]) continue
                    val rho = hypot(x - cx, y - cy) / r
                    if (rho < 1f) H[i] -= depth * (1f - rho * rho)
                    if (rho < 1.7f) { val q = (rho - 1.05f) / 0.18f; H[i] += 0.18f * depth * exp(-q * q) }
                }
            }
        }

        // ---- আলো: আসল অথবা (পূর্ণিমায়) দুই দিকের ভার্চুয়াল আলো ----
        val virtual = alpha < Math.toRadians(25.0) && virtualPhaseDeg > 0f
        val lights: List<FloatArray> = if (virtual) {
            val a = Math.toRadians(virtualPhaseDeg.toDouble()).toFloat(); val z0 = Math.toRadians(virtualAzDeg.toDouble()).toFloat()
            listOf(floatArrayOf(sin(a) * cos(z0), sin(a) * sin(z0), cos(a)),
                   floatArrayOf(-sin(a) * cos(z0), -sin(a) * sin(z0), cos(a)))
        } else listOf(floatArrayOf(rs0, rs1, rs2))

        say("আলো-ছায়া ও cast shadow হিসাব হচ্ছে…")
        val syn = FloatArray(n)
        for (ls in lights) {
            val s0 = ls[0]; val s1 = ls[1]; val s2 = ls[2]
            val sl = hypot(s0, s1); val doShadow = sl > 0.05f
            val dx = s0 / max(sl, 1e-4f); val dy = -s1 / max(sl, 1e-4f)
            for (y in 1 until S - 1) for (x in 1 until S - 1) {
                val i = y * S + x; if (!valid[i]) continue
                var hx = 0f; var hy = 0f
                if (valid[i - 1] && valid[i + 1]) hx = (H[i + 1] - H[i - 1]) / 2f / kmPx
                if (valid[i - S] && valid[i + S]) hy = (H[i + S] - H[i - S]) / 2f / kmPx
                val nx = NX[i]; val yy = NY[i]; val z = NZ[i]
                val px2 = nx - reliefGain * z * hx; val py2 = yy + reliefGain * z * hy
                val nl = sqrt(px2 * px2 + py2 * py2 + z * z)
                val m0 = max(0f, (px2 * s0 + py2 * s1 + z * s2) / nl); val mu = max(z / nl, 0.05f)
                var shadow = 0f
                val msph = nx * s0 + yy * s1 + z * s2
                if (doShadow && msph > 0f && msph < 0.8f) {
                    val tanE = tan(asin(msph.coerceIn(0.03f, 1f)))
                    var t = 1f
                    while (t <= 40f * k) {
                        val qx = (x + dx * t).roundToInt(); val qy = (y + dy * t).roundToInt()
                        if (qx !in 0 until S || qy !in 0 until S) break
                        val q = qy * S + qx; if (!valid[q]) break
                        val over = H[q] - (H[i] + t * kmPx * tanE)
                        if (over > 0f) shadow = max(shadow, (over / 0.15f).coerceIn(0f, 1f))
                        t += 1f
                    }
                }
                syn[i] += A[i] * 2f * m0 / (m0 + mu) * (1f - 0.97f * shadow) / lights.size
            }
        }

        // ---- ডিটেইল ট্রান্সফার (শুধু যেখানে আপনার ছবিতে আসল ডিটেইল কম) ----
        say("ডিটেইল বসানো হচ্ছে…")
        val synLow = blur(syn, S, rad(6))
        val energy = blur(FloatArray(n) { abs(base[it] - baseLow[it]) }, S, rad(8))
        var sbm = 0.0; var ssm = 0.0
        for (i in 0 until n) if (baseLow[i] > bmax * 0.2f && synLow[i] > 1f) { sbm += baseLow[i]; ssm += synLow[i] }
        val gain = if (ssm > 0) (sbm / ssm).toFloat() else 1f
        val out = IntArray(n)
        for (y in 0 until S) for (x in 0 until S) {
            val i = y * S + x
            val dsk = ((R * 0.97f - hypot(x - cen, y - cen)) / (6f * k)).coerceIn(0f, 1f)
            val litM = ((baseLow[i] - bmax * 0.15f) / (bmax * 0.15f)).coerceIn(0f, 1f)
            val need = (1f - energy[i] / 12f).coerceIn(0.25f, 1f)
            val d = dsk * litM * strength * need * gain * (syn[i] - synLow[i])
            val p = px[i]
            fun v(sh: Int) = ((p shr sh and 255) + d).toInt().coerceIn(0, 255)
            out[i] = Color.rgb(v(16), v(8), v(0))
        }
        return Result(Bitmap.createBitmap(out, S, S, Bitmap.Config.ARGB_8888), bestS,
            Math.toDegrees(alpha.toDouble()).toFloat(), best[1], best[2], virtual)
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
