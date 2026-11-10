package com.pocketcctv.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The CCTV engine. Runs as a foreground service so Android keeps it alive with the screen off.
 *  - records 480p / 30 fps in 2-hour clips
 *  - hands every finished clip to the Google Drive uploader
 *  - serves the live picture to the monitor
 */
class RecorderService : LifecycleService() {

    companion object {
        const val CHANNEL = "pocketcctv"
        private const val ACTION_STOP = "com.pocketcctv.app.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Pocket CCTV", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var started = false
    private var stopping = false
    private var liveBound = false
    private var server: MjpegServer? = null
    private var nsd: NsdAdvertiser? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastFrameTime = 0L

    // If the 2-hour limit ever fails to fire, this forces the clip to end.
    private val watchdog = Runnable { activeRecording?.stop() }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: Exception) {
            CctvState.message.value = "Android did not allow starting in the background: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            begin()
        }
        return START_STICKY
    }

    private fun goForeground() {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CameraActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pocket CCTV is watching")
            .setContentText("Recording and backing up to Google Drive")
            .setContentIntent(open)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        )
    }

    private fun begin() {
        prefs = Prefs(this)
        stopping = false
        CctvState.message.value = ""

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketCCTV:record").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PocketCCTV:wifi")
        wl.setReferenceCounted(false)
        wl.acquire()
        wifiLock = wl

        // Half-written clips left over from a crash can't be played, so remove them.
        Storage.recordingDir(this).listFiles()?.forEach { it.delete() }

        val srv = MjpegServer(prefs) { statusJson() }
        srv.start()
        server = srv
        CctvState.port.value = srv.port
        nsd = NsdAdvertiser(this, prefs.cameraName, srv.port).also { it.register() }

        CctvState.running.value = true
        UploadScheduler.schedulePeriodic(this)
        if (Storage.pending(this).isNotEmpty()) UploadScheduler.kick(this)

        bindCamera()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()

                val rotation = if (prefs.landscape) Surface.ROTATION_90 else Surface.ROTATION_0
                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    )
                    .setTargetVideoEncodingBitRate(900_000)
                    .build()

                val vcBuilder = VideoCapture.Builder(recorder)
                vcBuilder.setTargetFrameRate(Range(30, 30))
                vcBuilder.setTargetRotation(rotation)
                if (prefs.nightVision) NightVision.applyMono(vcBuilder, provider, selector)
                val vc = vcBuilder.build()

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(if (prefs.landscape) Size(640, 480) else Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(rotation)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { onFrame(it) }

                // Try recording + live view. Some older phones can't do both: then fall back to recording only.
                liveBound = tryBind(provider, selector, listOf(vc, analysis))
                if (!liveBound && !tryBind(provider, selector, listOf(vc))) {
                    CctvState.message.value = "Could not open the camera. Is another app using it?"
                    stopSelf()
                    return@addListener
                }
                if (!liveBound) CctvState.message.value = "Recording works, but this phone cannot do live view at the same time."

                videoCapture = vc
                if (prefs.nightVision) {
                    camera?.let { c ->
                        val range = c.cameraInfo.exposureState.exposureCompensationRange
                        if (range.upper > 0) c.cameraControl.setExposureCompensationIndex(range.upper.coerceAtMost(6))
                    }
                }
                startSegment()
            } catch (e: Exception) {
                CctvState.message.value = "Camera error: ${e.message}"
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tryBind(provider: ProcessCameraProvider, selector: CameraSelector, cases: List<UseCase>): Boolean =
        try {
            camera = provider.bindToLifecycle(this, selector, *cases.toTypedArray())
            true
        } catch (e: Exception) {
            provider.unbindAll()
            false
        }

    // ---------- recording in 2-hour clips ----------

    private fun startSegment() {
        val vc = videoCapture ?: return
        if (stopping) return
        val safeName = prefs.cameraName.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "Camera" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(Storage.recordingDir(this), "${safeName}_$stamp.mp4")
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(Prefs.SEGMENT_MS)
            .build()

        activeRecording = vc.output.prepareRecording(this, options)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        CctvState.recording.value = true
                        CctvState.segmentStartMillis.value = System.currentTimeMillis()
                        handler.removeCallbacks(watchdog)
                        handler.postDelayed(watchdog, Prefs.SEGMENT_MS + 20_000)
                    }
                    is VideoRecordEvent.Finalize -> onFinalize(file, event)
                    else -> {}
                }
            }
    }

    private fun onFinalize(file: File, event: VideoRecordEvent.Finalize) {
        activeRecording = null
        handler.removeCallbacks(watchdog)

        val keepable = when (event.error) {
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> true
            else -> false
        }
        if (keepable && file.exists() && file.length() > 0) {
            val dest = File(Storage.readyDir(this), file.name)
            if (file.renameTo(dest)) UploadScheduler.kick(this) // finished clip -> Google Drive
        } else {
            file.delete()
        }

        if (stopping) {
            CctvState.recording.value = false
            stopSelf()
            return
        }
        val clean = event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
            event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
        if (!clean) {
            CctvState.message.value = "Recording hiccup (code ${event.error}), restarting…"
            CctvState.recording.value = false
        }
        handler.postDelayed({ startSegment() }, if (clean) 0L else 5000L)
    }

    private fun shutDown() {
        stopping = true
        handler.removeCallbacks(watchdog)
        val rec = activeRecording
        if (rec != null) {
            rec.stop()
            handler.postDelayed({ stopSelf() }, 8000) // in case finalizing never reports back
        } else {
            stopSelf()
        }
    }

    // ---------- live view ----------

    private fun onFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            val s = server
            if (s != null && s.hasViewers() && now - lastFrameTime >= 100) { // about 10 pictures/second
                lastFrameTime = now
                val bmp = image.toBitmap()
                val rot = image.imageInfo.rotationDegrees
                val out = if (rot != 0) {
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                } else bmp
                val baos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                s.publish(baos.toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun statusJson(): String {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("name", prefs.cameraName)
            .put("recording", CctvState.recording.value)
            .put("live", liveBound)
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .toString()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { server?.stop() } catch (_: Exception) {}
        nsd?.unregister()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        analysisExecutor.shutdown()
        CctvState.running.value = false
        CctvState.recording.value = false
        CctvState.viewers.value = 0
        started = false
        super.onDestroy()
    }
}
