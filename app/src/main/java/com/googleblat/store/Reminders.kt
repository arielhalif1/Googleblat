package com.googleblat.store

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Daily encouragement: a morning goal reminder, one when the danger hours begin, and one late
 * at night. Each fires once a day; the next one is always scheduled when one fires.
 */
object Reminders {
    private data class Slot(val hour: Int, val minute: Int, val title: (Int) -> String, val lines: List<String>)

    private val SLOTS = listOf(
        Slot(9, 0, { d -> "יום ${d + 1} בדרך ל-${Streak.nextGoal(d)} 🔥" }, listOf(
            "היום אתה בוחר בשמחה. כל יום נקי מחזיר לך טעם בחיים.",
            "המטרה שלך: ${Streak.GOAL_DAYS} יום. כל יום שעובר בונה את האדם שאתה רוצה להיות.",
            "\"מצוה גדולה להיות בשמחה תמיד\" (רבי נחמן). היום הוא הזדמנות חדשה.",
            "תזכור את הבוקר שאחרי נפילה, ואת הבוקר שאחרי ניצחון. היום תבחר בשני.",
            "אתה לא נלחם נגד משהו, אתה נלחם בשביל משהו: בשביל השמחה, הכוח והחיים שלך.",
        )),
        Slot(Lockdown.DANGER_START_HOUR, 0, { _ -> "🌙 מתחילות השעות המסוכנות" }, listOf(
            "רגע של הנאה לוקח ממך ימים של שמחה. ההתגברות מחזירה לך אותם.",
            "הערב הוא המבחן. תעשה משהו טוב לעצמך: לצאת, ללמוד, לדבר עם חבר.",
            "כשהיצר מגיע, תזכור: עוד 10 דקות הוא יעבור, והגאווה בניצחון תישאר.",
            "\"איזהו גיבור? הכובש את יצרו\". הלילה אתה הגיבור.",
            "תחשוב איך תרגיש מחר בבוקר. זה שווה את זה.",
        )),
        Slot(22, 30, { d -> "יום ${d + 1}. לך לישון כמנצח 💪" }, listOf(
            "מאוחר בלילה זה הזמן הכי קשה. תניח את הטלפון ותלך לישון, מחר יהיה יום טוב יותר.",
            "עוד יום נקי כמעט מאחוריך. אל תיתן לכמה דקות לקחת אותו.",
            "השמחה של מחר נבנית מהבחירה של עכשיו.",
            "אתה שווה יותר מזה. לילה טוב, ומחר ממשיכים.",
            "\"הבא לטהר מסייעין אותו\". אתה לא לבד בזה.",
        )),
    )

    private const val CHANNEL = "reminders"
    private const val PREFS = "reminders"
    private const val KEY_NEXT_AT = "nextAt"
    private const val KEY_NEXT_SLOT = "nextSlot"
    const val EXTRA_OPEN_STREAK = "com.googleblat.store.OPEN_STREAK"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Makes sure the next reminder is set. Safe to call often; never skips a due reminder. */
    fun ensureScheduled(ctx: Context) {
        grantNotificationPermission(ctx)
        val nextAt = prefs(ctx).getLong(KEY_NEXT_AT, 0)
        val now = System.currentTimeMillis()
        when {
            nextAt > now -> setAlarm(ctx, nextAt) // e.g. after a reboot cleared alarms
            now - nextAt < 60 * 60_000L -> setAlarm(ctx, nextAt) // due now: fire it, don't skip it
            else -> scheduleNext(ctx)
        }
    }

    /** Called by the alarm: shows the due reminder and schedules the following one. */
    fun fire(ctx: Context) {
        val slot = SLOTS.getOrNull(prefs(ctx).getInt(KEY_NEXT_SLOT, -1))
        if (slot != null) show(ctx, slot)
        scheduleNext(ctx)
    }

    private fun scheduleNext(ctx: Context) {
        val now = System.currentTimeMillis()
        val (index, at) = SLOTS.indices.map { i ->
            val s = SLOTS[i]
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, s.hour)
                set(Calendar.MINUTE, s.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }
            i to c.timeInMillis
        }.minBy { it.second }
        prefs(ctx).edit().putLong(KEY_NEXT_AT, at).putInt(KEY_NEXT_SLOT, index).apply()
        setAlarm(ctx, at)
    }

    private fun setAlarm(ctx: Context, at: Long) {
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        Lockdown.setExactAlarm(ctx, at, pi)
    }

    private fun show(ctx: Context, slot: Slot) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "תזכורות יומיות", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val days = Streak.days(ctx)
        val line = slot.lines[days % slot.lines.size]
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_STREAK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(slot.title(days))
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(1, notification) }
    }

    /** As device owner, Googleblat can grant itself the notification permission without asking. */
    private fun grantNotificationPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            Lockdown.dpm(ctx).setPermissionGrantState(
                Lockdown.admin(ctx), ctx.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.fire(context)
}
