package com.googleblat.store

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * The allowlist: catalog apps, apps the user chose to keep, system apps, and anything the phone
 * can't work without (keyboards, launchers, accessibility services, device admins).
 * Everything else is hidden while locked. Hiding keeps the app's data; [allow] brings it back.
 */
object Guard {
    private const val PREFS = "guard"
    private const val KEY_KEPT = "kept"
    private const val KEY_HIDDEN = "hidden"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Apps the user chose to keep even though they aren't in the catalog. */
    fun kept(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_KEPT, emptySet())!!

    /** Apps Googleblat hid. */
    fun hidden(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_HIDDEN, emptySet())!!

    /** Visible, non-system apps that aren't allowed. Empty if no catalog was ever loaded. */
    fun strays(ctx: Context): List<ApplicationInfo> {
        val catalog = CatalogRepo.cached(ctx) ?: return emptyList()
        val allowed = catalog.apps.map { it.packageName }.toSet() + kept(ctx) + essentials(ctx) + ctx.packageName
        return ctx.packageManager.getInstalledApplications(0).filter {
            it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName !in allowed
        }
    }

    fun hideStrays(ctx: Context) {
        val dpm = Lockdown.dpm(ctx)
        val admin = Lockdown.admin(ctx)
        val newlyHidden = strays(ctx).map { it.packageName }
            .filter { runCatching { dpm.setApplicationHidden(admin, it, true) }.getOrDefault(false) }
        if (newlyHidden.isNotEmpty()) {
            prefs(ctx).edit().putStringSet(KEY_HIDDEN, hidden(ctx) + newlyHidden).apply()
        }
    }

    /** Keep [pkg] from now on, and unhide it if Googleblat hid it. */
    fun allow(ctx: Context, pkg: String) {
        runCatching { Lockdown.dpm(ctx).setApplicationHidden(Lockdown.admin(ctx), pkg, false) }
        prefs(ctx).edit()
            .putStringSet(KEY_KEPT, kept(ctx) + pkg)
            .putStringSet(KEY_HIDDEN, hidden(ctx) - pkg)
            .apply()
    }

    fun disallow(ctx: Context, pkg: String) {
        prefs(ctx).edit().putStringSet(KEY_KEPT, kept(ctx) - pkg).apply()
        Lockdown.enforce(ctx)
    }

    fun unhideAll(ctx: Context) {
        for (pkg in hidden(ctx)) {
            runCatching { Lockdown.dpm(ctx).setApplicationHidden(Lockdown.admin(ctx), pkg, false) }
        }
        prefs(ctx).edit().clear().apply()
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(
            pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
        ).toString()
    }.getOrDefault(pkg)

    /** Hiding any of these could leave the phone unusable (e.g. no keyboard to type the password). */
    private fun essentials(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        val keyboards = ctx.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }
        val accessibility = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
        val admins = Lockdown.dpm(ctx).activeAdmins.orEmpty().map { it.packageName }
        return (launchers + keyboards + accessibility + admins).toSet()
    }
}
