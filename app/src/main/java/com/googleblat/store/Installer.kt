package com.googleblat.store

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

object Installer {
    const val EXTRA_PACKAGE = "com.googleblat.store.PACKAGE"

    class VerificationException(message: String) : Exception(message)

    /** Downloads, verifies and installs [app]. Blocking - call off the main thread. */
    fun install(ctx: Context, app: AppEntry) {
        val resolved = CatalogRepo.resolve(app)
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        val apk = File(dir, "${app.packageName}.apk")
        try {
            Http.download(resolved.url, apk)
            verify(ctx, apk, app)
            commit(ctx, apk, app.packageName)
        } finally {
            apk.delete()
        }
    }

    /** The APK must be the catalog's package and signed by one of its pinned certificates. */
    fun verify(ctx: Context, apk: File, app: AppEntry) {
        val info = archiveInfo(ctx.packageManager, apk)
            ?: throw VerificationException("Downloaded file is not a valid APK")
        if (info.packageName != app.packageName) {
            throw VerificationException("APK is ${info.packageName}, expected ${app.packageName}")
        }
        val found = signerDigests(info)
        if (found.isEmpty()) throw VerificationException("APK is not signed")
        if (found.none { it in app.certs }) {
            throw VerificationException(
                "Signer not pinned in catalog.\nAPK signer SHA-256:\n" + found.joinToString("\n")
            )
        }
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(apk.path, flags)
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs = mutableListOf<Signature>()
        info.signingInfo?.let { si ->
            if (si.hasMultipleSigners()) sigs += si.apkContentsSigners
            else si.signingCertificateHistory?.let { sigs += it }
        }
        @Suppress("DEPRECATION")
        if (sigs.isEmpty()) info.signatures?.let { sigs += it }
        return sigs.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun commit(ctx: Context, apk: File, packageName: String) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(ctx, InstallResultReceiver::class.java).putExtra(EXTRA_PACKAGE, packageName)
            // Mutable: the system adds the status extras to this intent.
            val pi = PendingIntent.getBroadcast(
                ctx, sessionId, result,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        }
    }
}
