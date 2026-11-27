package com.astraedus.nudge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.astraedus.nudge.MainActivity
import com.astraedus.nudge.R

/**
 * Posts Walk2Unlock notifications:
 *
 * 1. **Phase-1 warnings** ("10 minutes left") in the notification shade, once per milestone per day.
 * 2. **Step-progress updates** (optional LOW-importance, used by [WalkUnlockActivity]'s ambient
 *    indicator when the screen is off).
 *
 * Uses notification IDs 5 and 6, extending the existing namespace:
 *   1 = monitor, 2 = protection alert, 3 = bounce check-in, 4 = use reminder.
 * Pinned by SharedNamespaceUniquenessTest.
 *
 * Threading: may be called from any thread; [NotificationManagerCompat.notify] is thread-safe.
 */
object Walk2UnlockNotifier {

    private const val CHANNEL_WARNINGS = "nudge_walk2unlock_warnings"
    private const val CHANNEL_PROGRESS  = "nudge_walk2unlock_progress"

    /** ID for phase-1 time-remaining warning. One per all apps (the message covers all three). */
    const val NOTIFICATION_ID_WARNING  = 5

    /** ID for step-progress update shown while the user is walking. */
    const val NOTIFICATION_ID_PROGRESS = 6

    // ── Phase-1 milestone notifications ──────────────────────────────────────

    /**
     * Post a "N minutes of free time left" notification.
     *
     * @param minutesLeft 20, 10, or 0.
     */
    fun notifyFreeTimeRemaining(context: Context, minutesLeft: Int) {
        ensureWarningChannel(context)
        val (title, body) = when (minutesLeft) {
            0    -> "Free time is up" to
                    "Walk 400 steps to unlock your next 5-minute session."
            10   -> "10 minutes left" to
                    "After this you'll need to walk to keep going."
            else -> "${minutesLeft} more minutes of free scrolling" to
                    "Instagram, Facebook, YouTube Shorts — make it count."
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(homeIntent(context, requestCode = 5))
            .build()
        postSafely(context, NOTIFICATION_ID_WARNING, notif)
    }

    // ── Step-progress notification ────────────────────────────────────────────

    /**
     * Update a LOW-importance progress notification while the user is walking.
     *
     * @param stepsWalked current step delta since the gate opened.
     * @param stepsRequired target (200, 400, or 600).
     * @param appLabel short label like "Instagram / Facebook / YouTube Shorts".
     */
    fun notifyStepProgress(
        context: Context,
        stepsWalked: Int,
        stepsRequired: Int,
        appLabel: String
    ) {
        ensureProgressChannel(context)
        val body = "Walking to unlock $appLabel — $stepsWalked / $stepsRequired steps"
        val notif = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle("Walk2Unlock in progress")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(stepsRequired, stepsWalked.coerceAtMost(stepsRequired), false)
            .setContentIntent(homeIntent(context, requestCode = 6))
            .build()
        postSafely(context, NOTIFICATION_ID_PROGRESS, notif)
    }

    fun cancelStepProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun postSafely(context: Context, id: Int, notif: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to do.
        }
    }

    private fun homeIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureWarningChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_WARNINGS,
            "Walk2Unlock time warnings",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when your free scrolling time is about to run out"
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun ensureProgressChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_PROGRESS,
            "Walk2Unlock step progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows step count while walking to unlock an app"
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
