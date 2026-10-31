package com.forwardsweep.data.scan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forwardsweep.classifier.Category
import com.forwardsweep.data.DataGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

sealed interface ScanProgress {
    data object Idle : ScanProgress
    data class Running(val done: Int, val total: Int) : ScanProgress
    /** The quick first pass finished; the rest of a large library waits for the charger. */
    data object WaitingForCharging : ScanProgress
    data object Finished : ScanProgress
}

/**
 * Classifies pending images in batches. Stopping is safe: finished batches are already cached.
 *
 * When started while the app is open, the worker promotes itself to a foreground service with a
 * progress notification. Plain jobs are stopped by the system whenever the phone gets warm
 * (observed on a recent flagship: "thermal" stops every 20–100 s during a long scan); a
 * foreground service keeps going. Started from the background (the charging-only chain), the
 * promotion is not allowed and the worker simply runs as a normal job.
 */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as DataGraph.Provider).graph
        val settings = graph.settings.current()
        val maxItems = inputData.getInt(KEY_MAX_ITEMS, Int.MAX_VALUE)
        var foreground = runCatching { setForeground(foregroundInfo(0, 0, graph.notificationIcon)) }.isSuccess
        val scanned = graph.images.scan(settings, maxItems, isStopped = { isStopped }) { done, total ->
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            if (foreground) foreground = runCatching { setForeground(foregroundInfo(done, total, graph.notificationIcon)) }.isSuccess
        }
        return Result.success(workDataOf(KEY_DONE to scanned))
    }

    private fun foregroundInfo(done: Int, total: Int, icon: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "Sorting progress", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("Sorting images")
            .setContentText(if (total > 0) "%,d of %,d".format(done, total) else "Getting ready…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MAX_ITEMS = "max_items"
        private const val PROGRESS_CHANNEL = "scan-progress"
        private const val PROGRESS_NOTIFICATION_ID = 2
    }
}

/** Monthly: scan new images while charging, then nudge the user if many new greetings piled up. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as DataGraph.Provider).graph
        val settings = graph.settings.current()
        if (!settings.monthlyReminder) return Result.success()
        val since = settings.lastReminderAt
        graph.images.scan(settings, isStopped = { isStopped })
        if (isStopped) return Result.retry()
        val fresh = graph.images.countNewSince(Category.GREETING, since)
        if (fresh >= MIN_TO_NOTIFY) notify(applicationContext, fresh, graph.notificationIcon)
        graph.settings.update { it.copy(lastReminderAt = System.currentTimeMillis()) }
        return Result.success()
    }

    private fun notify(context: Context, count: Int, icon: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monthly clean-up reminder", NotificationManager.IMPORTANCE_LOW))
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("%,d new greeting images found".format(count))
            .setContentText("Review?")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "reminders"
        private const val NOTIFICATION_ID = 1
        const val MIN_TO_NOTIFY = 50
    }
}

class ScanScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context)

    /**
     * Small backlog: scan everything now. Large backlog: scan the newest [QUICK_PASS] now so the
     * user sees results, then finish the rest only while charging.
     */
    fun scanNow(pending: Int) {
        val quick = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(workDataOf(ScanWorker.KEY_MAX_ITEMS to if (pending > LARGE_LIBRARY) QUICK_PASS else Int.MAX_VALUE))
            .addTag(TAG)
            .build()
        var chain = wm.beginUniqueWork(UNIQUE_SCAN, ExistingWorkPolicy.KEEP, quick)
        if (pending > LARGE_LIBRARY) {
            val rest = OneTimeWorkRequestBuilder<ScanWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .addTag(TAG)
                .build()
            chain = chain.then(rest)
        }
        chain.enqueue()
    }

    fun cancel() = wm.cancelUniqueWork(UNIQUE_SCAN)

    fun setMonthlyReminder(enabled: Boolean) {
        if (!enabled) { wm.cancelUniqueWork(UNIQUE_REMINDER); return }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(30, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_REMINDER, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    val progress: Flow<ScanProgress> = wm.getWorkInfosForUniqueWorkFlow(UNIQUE_SCAN).map { infos ->
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        when {
            running != null -> ScanProgress.Running(
                running.progress.getInt(ScanWorker.KEY_DONE, 0),
                running.progress.getInt(ScanWorker.KEY_TOTAL, 0),
            )
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } &&
                infos.any { it.state == WorkInfo.State.SUCCEEDED } -> ScanProgress.WaitingForCharging
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> ScanProgress.Running(0, 0)
            infos.isNotEmpty() && infos.all { it.state.isFinished } -> ScanProgress.Finished
            else -> ScanProgress.Idle
        }
    }

    companion object {
        const val UNIQUE_SCAN = "scan"
        const val UNIQUE_REMINDER = "monthly-reminder"
        const val TAG = "forwardsweep-scan"
        const val LARGE_LIBRARY = 2000
        const val QUICK_PASS = 1000
    }
}
