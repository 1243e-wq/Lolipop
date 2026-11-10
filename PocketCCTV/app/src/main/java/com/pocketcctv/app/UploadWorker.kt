package com.pocketcctv.app

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object UploadScheduler {
    private fun constraints(ctx: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (Prefs(ctx).wifiOnlyUpload) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Start a backup run now (or as soon as there is internet). */
    fun kick(ctx: Context, replace: Boolean = false) {
        val app = ctx.applicationContext
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(app))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "upload-now",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            req
        )
    }

    /** Safety net: every 30 minutes retry leftovers and clean up old clips. */
    fun schedulePeriodic(ctx: Context) {
        val app = ctx.applicationContext
        val req = PeriodicWorkRequestBuilder<UploadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints(app))
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "upload-periodic", ExistingPeriodicWorkPolicy.UPDATE, req
        )
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private val mutex = Mutex()
        private const val NOTIF_ID = 2
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        mutex.withLock { doUpload() }
    }

    private suspend fun doUpload(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)

        if (DriveAuth.account(ctx) == null) {
            CctvState.uploadStatus.value = "Sign in with Google to start backups"
            return Result.success()
        }

        val drive = DriveClient(ctx, prefs)
        var failed = false
        var foreground = false
        try {
            while (true) {
                val ready = Storage.pending(ctx)
                if (ready.isEmpty()) break
                val f = ready.first()
                if (f.length() == 0L) { f.delete(); continue }

                if (!foreground) {
                    try { setForeground(info("Preparing backup…")); foreground = true } catch (_: Exception) {}
                }
                CctvState.uploadStatus.value = "Uploading ${f.name}…"
                notify("Uploading ${f.name}")
                val folder = drive.ensureFolder()
                var lastPct = -10
                val id = drive.uploadVerified(f, folder) { pct ->
                    if (pct >= lastPct + 5) {
                        lastPct = pct
                        CctvState.uploadStatus.value = "Uploading… $pct%"
                        notify("Uploading… $pct%")
                    }
                }
                if (id != null) {
                    f.delete() // only now, after Google confirmed an identical copy
                    prefs.lastUploadMillis = System.currentTimeMillis()
                    CctvState.uploadStatus.value = "✓ Backed up and verified"
                } else {
                    CctvState.uploadStatus.value = "Copy did not match, will try again"
                    failed = true
                    break
                }
            }
            drive.cleanupOld(prefs.retentionDays)
            Storage.cleanLocal(ctx, prefs.retentionDays)
        } catch (e: Exception) {
            failed = true
            CctvState.uploadStatus.value = "Waiting to retry: ${e.message}"
        }
        return if (failed) Result.retry() else Result.success()
    }

    private fun build(text: String) = NotificationCompat.Builder(applicationContext, RecorderService.CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Backing up to Google Drive")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun info(text: String): ForegroundInfo {
        RecorderService.ensureChannel(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIF_ID, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, build(text))
    }

    private fun notify(text: String) {
        try {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, build(text))
        } catch (_: Exception) {}
    }
}
