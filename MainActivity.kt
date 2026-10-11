package com.moonlens   // <-- build.gradle.kts-এর namespace-এর সাথে একই হতে হবে

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object { const val FRAMES = 40; const val ISO = 100 }

    private lateinit var previewView: PreviewView
    private lateinit var capture: ImageCapture
    private lateinit var statusTv: TextView
    private lateinit var sharpBar: SeekBar
    private lateinit var craterBar: SeekBar
    private lateinit var expBar: SeekBar
    private lateinit var sharpVal: TextView
    private lateinit var craterVal: TextView
    private lateinit var expVal: TextView
    private lateinit var shutterBtn: View
    private lateinit var progress: ProgressBar
    private lateinit var resultLayer: View
    private lateinit var resultImg: ImageView
    private var before: Bitmap? = null
    private var after: Bitmap? = null
    private val camExec = Executors.newSingleThreadExecutor()
    private val proc by lazy { MoonProcessor(this) }

    private fun SeekBar.watch(onStop: (() -> Unit)? = null, onChange: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = onChange(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { onStop?.invoke() }
        })
    }

    /** শাটার ১/৫০০ (প্রগ্রেস ০) থেকে ১/৮০০০ (প্রগ্রেস ১০০), ডিফল্ট ১/৪০০০ */
    private fun shutterNs(): Long = (2_000_000.0 * 0.0625.pow(expBar.progress / 100.0)).toLong()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        statusTv = findViewById(R.id.statusTv)
        sharpBar = findViewById(R.id.sharpBar); craterBar = findViewById(R.id.craterBar); expBar = findViewById(R.id.expBar)
        sharpVal = findViewById(R.id.sharpVal); craterVal = findViewById(R.id.craterVal); expVal = findViewById(R.id.expVal)
        shutterBtn = findViewById(R.id.shutterBtn); progress = findViewById(R.id.progress)
        resultLayer = findViewById(R.id.resultLayer); resultImg = findViewById(R.id.resultImg)

        sharpBar.watch { sharpVal.text = "$it%" }
        craterBar.watch { craterVal.text = "$it%" }
        expBar.watch(onStop = { if (::capture.isInitialized) startCamera() }) {
            expVal.text = "1/" + (1e9 / shutterNs()).roundToInt()
        }
        sharpVal.text = "${sharpBar.progress}%"; craterVal.text = "${craterBar.progress}%"
        expVal.text = "1/" + (1e9 / shutterNs()).roundToInt()

        findViewById<View>(R.id.closeBtn).setOnClickListener { resultLayer.visibility = View.GONE }
        resultImg.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> before?.let { resultImg.setImageBitmap(it) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> after?.let { resultImg.setImageBitmap(it) }
            }
            true
        }
        shutterBtn.setOnClickListener { shoot() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else statusTv.text = "ক্যামেরা পারমিশন লাগবে"
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val b = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(100)
            Camera2Interop.Extender(b).apply {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, ISO)
                setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs())
                setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
            }
            capture = b.build()
            val p = f.get(); p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(this))
    }

    private suspend fun grab(): Bitmap? = suspendCancellableCoroutine { c ->
        capture.takePicture(camExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) { val bmp = image.toBitmap(); image.close(); c.resume(bmp) }
            override fun onError(e: ImageCaptureException) { c.resume(null) }
        })
    }

    private fun say(t: String) = runOnUiThread { statusTv.text = t }
    private fun idle() = runOnUiThread { shutterBtn.isEnabled = true; progress.visibility = View.GONE }

    private fun shoot() {
        shutterBtn.isEnabled = false; progress.visibility = View.VISIBLE
        val sharp = sharpBar.progress / 50f
        val crater = craterBar.progress / 100f
        lifecycleScope.launch(Dispatchers.Default) {
            val frames = ArrayList<IntArray>()
            for (i in 1..FRAMES) {
                say("ফ্রেম $i/$FRAMES (ফোন নাড়বেন না)")
                val bmp = grab() ?: continue
                proc.findMoon(bmp)?.let { frames.add(proc.crop(bmp, it)) }
                bmp.recycle()
            }
            if (frames.size < 5) { say("চাঁদ পাওয়া যায়নি, আবার চেষ্টা করুন"); idle(); return@launch }
            val stacked = proc.process(frames, sharp, 0f, ::say)
            var out = stacked; var method = "Real"
            if (crater > 0f) {
                MoonEnhancer.apply(this@MainActivity, stacked, crater, say = ::say)?.let { out = it.image; method = it.method }
            }
            val name = (if (method == "Real") "MoonLens_Real_" else "MoonLens_AI_") + System.currentTimeMillis() + ".jpg"
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MoonLens")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { u ->
                contentResolver.openOutputStream(u)?.use { out.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            }
            withContext(Dispatchers.Main) {
                before = stacked; after = out
                resultImg.setImageBitmap(out); resultLayer.visibility = View.VISIBLE
                statusTv.text = "সেভ হয়েছে ($method): Pictures/MoonLens"
                shutterBtn.isEnabled = true; progress.visibility = View.GONE
            }
        }
    }
}
