package com.googleblat.store

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

/**
 * Device-owner policies that make Googleblat the only way to install apps.
 * "Unlocking" lifts them for a short window; an alarm, boot, or opening the app re-applies them.
 */
object Lockdown {
    const val UNLOCK_MINUTES = 15
    const val UNLOCK_DELAY_SECONDS = 60

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_DEBUGGING_FEATURES, // blocks `adb install`
        UserManager.DISALLOW_ADD_USER,           // Second space could otherwise bypass
        UserManager.DISALLOW_ADD_MANAGED_PROFILE,
    )

    /** Other stores that are privileged system apps, so unknown-sources rules don't apply to them. */
    private val DEFAULT_BLOCKED = setOf(
        "com.android.vending",  // Google Play Store
        "com.xiaomi.market",    // Xiaomi GetApps
        "com.xiaomi.mipicks",
    )

    private const val PREFS = "lockdown"
    private const val KEY_UNTIL = "unlockedUntil"
    private const val KEY_EXTRA_BLOCKED = "extraBlocked"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** 0 when locked, otherwise the wall-clock time the unlock window ends. */
    fun unlockedUntil(ctx: Context): Long {
        val until = prefs(ctx).getLong(KEY_UNTIL, 0)
        return if (until > System.currentTimeMillis()) until else 0
    }

    fun isLocked(ctx: Context) = unlockedUntil(ctx) == 0L

    private fun blockedPackages(ctx: Context) =
        DEFAULT_BLOCKED + prefs(ctx).getStringSet(KEY_EXTRA_BLOCKED, emptySet())!!

    /** Extra packages to hide, from the catalog's "block" list. Can only add, never unhide defaults. */
    fun setExtraBlocked(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_EXTRA_BLOCKED, packages).apply()
        if (isDeviceOwner(ctx) && isLocked(ctx)) setPackagesHidden(ctx, true)
    }

    fun lock(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        RESTRICTIONS.forEach { dpm.addUserRestriction(admin(ctx), it) }
        setPackagesHidden(ctx, true)
        prefs(ctx).edit().putLong(KEY_UNTIL, 0).apply()
        alarmManager(ctx).cancel(relockIntent(ctx))
    }

    fun unlock(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val until = System.currentTimeMillis() + UNLOCK_MINUTES * 60_000L
        prefs(ctx).edit().putLong(KEY_UNTIL, until).apply()
        liftAll(ctx)
        alarmManager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, relockIntent(ctx))
    }

    /** Called on boot, app start, and by the relock alarm. */
    fun enforce(ctx: Context) {
        if (!isDeviceOwner(ctx) || !Passwords.isSet(ctx)) return
        if (isLocked(ctx)) lock(ctx)
    }

    /** Removes every policy and gives up device-owner status, so the app can be uninstalled. */
    fun releaseCompletely(ctx: Context) {
        liftAll(ctx)
        alarmManager(ctx).cancel(relockIntent(ctx))
        dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
    }

    private fun liftAll(ctx: Context) {
        val dpm = dpm(ctx)
        RESTRICTIONS.forEach { dpm.clearUserRestriction(admin(ctx), it) }
        setPackagesHidden(ctx, false)
    }

    private fun setPackagesHidden(ctx: Context, hidden: Boolean) {
        val dpm = dpm(ctx)
        for (pkg in blockedPackages(ctx)) {
            // Returns false for packages that aren't on this phone - that's fine.
            runCatching { dpm.setApplicationHidden(admin(ctx), pkg, hidden) }
        }
    }

    private fun alarmManager(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun relockIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, RelockReceiver::class.java).setAction(RelockReceiver.ACTION_RELOCK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
