package com.moonlens   // <-- আপনার প্রজেক্টের package-এর সাথে মিলিয়ে বদলান

import android.content.Context
import android.graphics.Bitmap

/**
 * একটাই ফাংশন, সবচেয়ে ভালো পদ্ধতি নিজে বেছে নেয়:
 * ১) MoonNeural      : assets-এ moon_enhancer.tflite থাকলে (Samsung-স্টাইল নিউরাল)
 * ২) MoonRelightV2   : assets-এ moon_dem + moon_albedo থাকলে (আসল 3D আলো-ছায়া + cast shadow + ভার্চুয়াল আলো)
 * ৩) CraterFusion    : assets-এ moon_ref*.jpg থাকলে (রেফারেন্স ছবি)
 * ৪) কিছুই না থাকলে null (Real Mode, কিছু বসানো হয়নি)। strength ০ হলেও null।
 * preferNeural = false দিলে নিউরাল বাদ যাবে। smallCraters = ০ দিলে বানানো ছোট গর্ত বন্ধ।
 */
object MoonEnhancer {
    class Result(val image: Bitmap, val method: String, val score: Float)

    fun apply(ctx: Context, stacked: Bitmap, strength: Float = 0.8f,
              utcMillis: Long = System.currentTimeMillis(), preferNeural: Boolean = true,
              smallCraters: Float = 0.5f, say: (String) -> Unit = {}): Result? {
        if (strength <= 0f) return null
        if (preferNeural) MoonNeural.apply(ctx, stacked, strength, 1024, say)
            ?.let { return Result(it, "neural", 1f) }
        MoonRelightV2.apply(ctx, stacked, strength, 1024, utcMillis, 1.2f, smallCraters, 40f, 135f, say)
            ?.let { return Result(it.image, "DEM-relight-v2", it.matchScore) }
        say("DEM পদ্ধতি হয়নি, রেফারেন্স পদ্ধতিতে যাচ্ছি…")
        CraterFusion.apply(ctx, stacked, strength, 1024, say)
            ?.let { return Result(it.image, "reference-fusion", it.matchScore) }
        return null
    }
}
