package com.googleblat.store

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import java.util.Calendar

/**
 * Device-owner policies that make the catalog the only source of apps.
 *
 * Locked (normal): sideloading and ADB are blocked, other stores are hidden, Google Play is
 * paused except during a short "Play window" or the nightly update hour, and any non-system app
 * that isn't allowed gets hidden (its data is kept).
 * Unlocked: all of that is lifted for one of [UNLOCK_CHOICES]. Everything goes through [enforce], which
 * runs on app start, boot, package installs, and an alarm.
 */
object Lockdown {
    /** Unlock durations offered after the password: label to minutes. */
    val UNLOCK_CHOICES = listOf("15 minutes" to 15, "1 hour" to 60, "3 hours" to 180, "24 hours" to 1440)
    const val PLAY_WINDOW_MINUTES = 10
    /** Play is open from this hour for one hour every night, so it can update apps. */
    const val NIGHTLY_UPDATE_HOUR = 3

    const val PLAY = "com.android.vending"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_DEBUGGING_FEATURES, // blocks `adb install`
        UserManager.DISALLOW_ADD_USER,           // Second space could otherwise bypass
        UserManager.DISALLOW_ADD_MANAGED_PROFILE,
    )

    /** Other stores that are privileged system apps, so unknown-sources rules don't apply to them. */
    private val OTHER_STORES = setOf(
        "com.xiaomi.market",    // Xiaomi GetApps
        "com.xiaomi.mipicks",
        "com.sec.android.app.samsungapps", // Samsung Galaxy Store
    )

    private const val PREFS = "lockdown"
    private const val KEY_SETUP_DONE = "setupDone"
    private const val KEY_UNTIL = "unlockedUntil"
    private const val KEY_PLAY_UNTIL = "playUntil"
    private const val KEY_PLAY_MODE = "playMode"
    private const val KEY_EXTRA_BLOCKED = "extraBlocked"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun dpm(ctx: Context): DevicePolicyManager = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun isSetupDone(ctx: Context) = prefs(ctx).getBoolean(KEY_SETUP_DONE, false)

    /** First lock, after the user reviewed which of their current apps to keep. */
    fun finishSetup(ctx: Context, keep: Set<String>) {
        keep.forEach { Guard.allow(ctx, it) }
        prefs(ctx).edit().putBoolean(KEY_SETUP_DONE, true).apply()
        lock(ctx)
    }

    /** 0 when locked, otherwise the wall-clock time the unlock window ends. */
    fun unlockedUntil(ctx: Context) = futureOrZero(prefs(ctx).getLong(KEY_UNTIL, 0))

    fun isLocked(ctx: Context) = unlockedUntil(ctx) == 0L

    /** 0 when the Play window is closed, otherwise when it closes. */
    fun playOpenUntil(ctx: Context) = futureOrZero(prefs(ctx).getLong(KEY_PLAY_UNTIL, 0))

    /** How Play is blocked while locked: "suspended" (preferred), "hidden", or null if not yet. */
    fun playMode(ctx: Context): String? = prefs(ctx).getString(KEY_PLAY_MODE, null)

    private fun futureOrZero(t: Long) = if (t > System.currentTimeMillis()) t else 0

    /** Extra packages to hide, from the catalog's "block" list. Can only add, never unhide defaults. */
    fun setExtraBlocked(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_EXTRA_BLOCKED, packages).apply()
        enforce(ctx)
    }

    fun lock(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_UNTIL, 0).putLong(KEY_PLAY_UNTIL, 0).apply()
        enforce(ctx)
    }

    fun unlock(ctx: Context, minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        prefs(ctx).edit().putLong(KEY_UNTIL, until).apply()
        enforce(ctx)
    }

    /** Opens Play on one app's page and keeps Play usable for [PLAY_WINDOW_MINUTES]. */
    fun openPlayFor(ctx: Context, pkg: String) {
        val until = System.currentTimeMillis() + PLAY_WINDOW_MINUTES * 60_000L
        prefs(ctx).edit().putLong(KEY_PLAY_UNTIL, until).apply()
        enforce(ctx)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .setPackage(PLAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun closePlay(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_PLAY_UNTIL, 0).apply()
        enforce(ctx)
    }

    /** Applies whatever the current state calls for. Safe to call any time, any number of times. */
    fun enforce(ctx: Context) {
        if (!isDeviceOwner(ctx) || !Passwords.isSet(ctx) || !isSetupDone(ctx)) return
        val locked = isLocked(ctx)
        val dpm = dpm(ctx)
        for (r in RESTRICTIONS) {
            if (locked) dpm.addUserRestriction(admin(ctx), r) else dpm.clearUserRestriction(admin(ctx), r)
        }
        setHidden(ctx, otherStores(ctx), locked)
        setPlayBlocked(ctx, locked && playOpenUntil(ctx) == 0L && !inNightlyWindow())
        if (locked) Guard.hideStrays(ctx) else Guard.showHidden(ctx)
        scheduleAlarms(ctx)
    }

    /** Removes every policy and gives up device-owner status, so the app can be uninstalled. */
    fun releaseCompletely(ctx: Context) {
        val dpm = dpm(ctx)
        RESTRICTIONS.forEach { dpm.clearUserRestriction(admin(ctx), it) }
        setHidden(ctx, otherStores(ctx), false)
        setPlayBlocked(ctx, false)
        Guard.unhideAll(ctx)
        alarms(ctx).cancel(alarmIntent(ctx, 0))
        alarms(ctx).cancel(alarmIntent(ctx, 1))
        prefs(ctx).edit().clear().apply()
        dpm.clearDeviceOwnerApp(ctx.packageName)
    }

    private fun otherStores(ctx: Context) =
        OTHER_STORES + prefs(ctx).getStringSet(KEY_EXTRA_BLOCKED, emptySet())!!

    private fun inNightlyWindow() =
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == NIGHTLY_UPDATE_HOUR

    private fun setHidden(ctx: Context, packages: Set<String>, hidden: Boolean) {
        val dpm = dpm(ctx)
        for (pkg in packages) {
            // Fails for packages that aren't on this phone - that's fine.
            runCatching { dpm.setApplicationHidden(admin(ctx), pkg, hidden) }
        }
    }

    /**
     * Suspending keeps Play's background services running (apps use them for integrity checks)
     * while stopping it from opening. If Android refuses to suspend it, hide it instead.
     */
    private fun setPlayBlocked(ctx: Context, blocked: Boolean) {
        val dpm = dpm(ctx)
        val failed = runCatching { dpm.setPackagesSuspended(admin(ctx), arrayOf(PLAY), blocked) }
            .getOrNull()
        val mode = when {
            !blocked -> {
                runCatching { dpm.setApplicationHidden(admin(ctx), PLAY, false) }
                playMode(ctx)
            }
            failed != null && PLAY !in failed -> {
                runCatching { dpm.setApplicationHidden(admin(ctx), PLAY, false) }
                "suspended"
            }
            else -> {
                runCatching { dpm.setApplicationHidden(admin(ctx), PLAY, true) }
                "hidden"
            }
        }
        prefs(ctx).edit().putString(KEY_PLAY_MODE, mode).apply()
    }

    private fun alarms(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(ctx: Context, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, requestCode,
        Intent(ctx, RelockReceiver::class.java).setAction(RelockReceiver.ACTION_RELOCK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleAlarms(ctx: Context) {
        val am = alarms(ctx)
        // Precise-ish alarm for the next deadline (unlock or Play window ending).
        val next = listOf(unlockedUntil(ctx), playOpenUntil(ctx)).filter { it > 0 }.minOrNull()
        if (next != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(ctx, 0))
        else am.cancel(alarmIntent(ctx, 0))
        // Regular sweep: catches stray installs and opens/closes the nightly Play window.
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            alarmIntent(ctx, 1),
        )
    }
}
