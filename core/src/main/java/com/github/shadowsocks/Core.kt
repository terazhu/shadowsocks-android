/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2018 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2018 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.persistableBundleOf
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.core.BuildConfig
import com.github.shadowsocks.core.R
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.subscription.SubscriptionService
import com.github.shadowsocks.utils.Action
import com.github.shadowsocks.utils.DeviceStorageApp
import com.github.shadowsocks.utils.DirectBoot
import com.github.shadowsocks.utils.Key
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.reflect.KClass

object Core {
    const val externalAccessNotificationId = 2004
    private const val externalAccessAllowWindowMs = 10 * 60 * 1000L
    private const val externalAccessPromptIntervalMs = 60 * 1000L
    lateinit var app: Application
        @VisibleForTesting set
    lateinit var configureIntent: (Context) -> PendingIntent
    val activity by lazy { app.getSystemService<ActivityManager>()!! }
    val clipboard by lazy { app.getSystemService<ClipboardManager>()!! }
    val connectivity by lazy { app.getSystemService<ConnectivityManager>()!! }
    val notification by lazy { app.getSystemService<NotificationManager>()!! }
    val user by lazy { app.getSystemService<UserManager>()!! }
    val packageInfo: PackageInfo by lazy { getPackageInfo(app.packageName) }
    val deviceStorage by lazy { if (Build.VERSION.SDK_INT < 24) app else DeviceStorageApp(app) }
    val directBootSupported by lazy {
        Build.VERSION.SDK_INT >= 24 && try {
            app.getSystemService<DevicePolicyManager>()?.storageEncryptionStatus ==
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
        } catch (_: RuntimeException) {
            false
        }
    }

    val activeProfileIds get() = ProfileManager.getProfile(DataStore.profileId).let {
        if (it == null) emptyList() else listOfNotNull(it.id, it.udpFallback)
    }
    val currentProfile: ProfileManager.ExpandedProfile? get() {
        if (DataStore.directBootAware) DirectBoot.getDeviceProfile()?.apply { return this }
        return ProfileManager.expand(ProfileManager.getProfile(DataStore.profileId) ?: return null)
    }

    fun switchProfile(id: Long): Profile {
        val result = ProfileManager.getProfile(id) ?: ProfileManager.createProfile()
        DataStore.profileId = result.id
        return result
    }

    fun init(app: Application, configureClass: KClass<out Any>) {
        this.app = app
        this.configureIntent = {
            PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        }

        if (Build.VERSION.SDK_INT >= 24) {  // migrate old files
            deviceStorage.moveDatabaseFrom(app, Key.DB_PUBLIC)
            val old = Acl.getFile(Acl.CUSTOM_RULES_USER, app)
            if (old.canRead()) {
                Acl.getFile(Acl.CUSTOM_RULES_USER).writeText(old.readText())
                old.delete()
            }
        }

        // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
        FirebaseApp.initializeApp(deviceStorage)  // multiple processes needs manual set-up
        FirebaseCrashlytics.getInstance().setCustomKey("build", Build.DISPLAY)
        updateExternalAccessCollection(isExternalAccessAllowed())
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (t == null) {
                    if (priority != Log.DEBUG || BuildConfig.DEBUG) Log.println(priority, tag, message)
                    if (isExternalAccessAllowed()) {
                        FirebaseCrashlytics.getInstance().log("${"XXVDIWEF".getOrElse(priority) { 'X' }}/$tag: $message")
                    }
                } else {
                    if (priority >= Log.WARN || priority == Log.DEBUG) Log.println(priority, tag, message)
                    if (priority >= Log.INFO && isExternalAccessAllowed()) {
                        FirebaseCrashlytics.getInstance().recordException(t)
                    }
                }
            }
        })

        // handle data restored/crash
        if (Build.VERSION.SDK_INT >= 24 && DataStore.directBootAware && user.isUserUnlocked) {
            DirectBoot.flushTrafficStats()
        }
        if (DataStore.publicStore.getLong(Key.assetUpdateTime, -1) != packageInfo.lastUpdateTime) {
            val assetManager = app.assets
            try {
                for (file in assetManager.list("acl")!!) assetManager.open("acl/$file").use { input ->
                    File(deviceStorage.noBackupFilesDir, file).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                Timber.w(e)
            }
            DataStore.publicStore.putLong(Key.assetUpdateTime, packageInfo.lastUpdateTime)
        }
        updateNotificationChannels()
    }

    fun updateNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
            notification.createNotificationChannels(listOf(
                    NotificationChannel("service-vpn", app.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW),   // #1355
                    NotificationChannel("service-proxy", app.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("service-transproxy", app.getText(R.string.service_transproxy),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("external-access", app.getText(R.string.external_access_title),
                            NotificationManager.IMPORTANCE_HIGH),
                    NotificationChannel("service-floating", app.getText(R.string.service_floating),
                            NotificationManager.IMPORTANCE_MIN),
                    SubscriptionService.notificationChannel))
            notification.deleteNotificationChannel("service-nat")   // NAT mode is gone for good
        }
    }

    fun getPackageInfo(packageName: String) = app.packageManager.getPackageInfo(packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)!!

    fun trySetPrimaryClip(clip: String, isSensitive: Boolean = false) = try {
        clipboard.setPrimaryClip(ClipData.newPlainText(null, clip).apply {
            if (isSensitive && Build.VERSION.SDK_INT >= 24) {
                description.extras = persistableBundleOf(ClipDescription.EXTRA_IS_SENSITIVE to true)
            }
        })
        true
    } catch (e: RuntimeException) {
        Timber.d(e)
        false
    }

    fun startService() = ContextCompat.startForegroundService(app, Intent(app, ShadowsocksConnection.serviceClass))
    fun reloadService() = app.sendBroadcast(Intent(Action.RELOAD).setPackage(app.packageName))
    fun stopService() = app.sendBroadcast(Intent(Action.CLOSE).setPackage(app.packageName))

    fun isExternalAccessAllowed(): Boolean {
        if (DataStore.publicStore.getBoolean(Key.externalAccessAlways) == true) return true
        val until = DataStore.publicStore.getLong(Key.externalAccessUntil) ?: 0L
        return until > System.currentTimeMillis()
    }

    fun allowExternalAccessOnce() {
        val until = System.currentTimeMillis() + externalAccessAllowWindowMs
        DataStore.publicStore.putLong(Key.externalAccessUntil, until)
        DataStore.publicStore.putBoolean(Key.externalAccessAlways, false)
        updateExternalAccessCollection(true)
    }

    fun allowExternalAccessAlways() {
        DataStore.publicStore.putBoolean(Key.externalAccessAlways, true)
        DataStore.publicStore.putLong(Key.externalAccessUntil, 0L)
        updateExternalAccessCollection(true)
    }

    fun updateExternalAccessCollection(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        FirebaseAnalytics.getInstance(app).setAnalyticsCollectionEnabled(enabled)
    }

    fun requestExternalAccess(reason: String): Boolean {
        if (isExternalAccessAllowed()) return true
        val now = System.currentTimeMillis()
        val last = DataStore.publicStore.getLong(Key.externalAccessLastPrompt) ?: 0L
        if (now - last < externalAccessPromptIntervalMs) return false
        DataStore.publicStore.putLong(Key.externalAccessLastPrompt, now)
        val allowOnceIntent = PendingIntent.getBroadcast(app, 0,
            Intent(Action.EXTERNAL_ALLOW_ONCE).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
        val allowAlwaysIntent = PendingIntent.getBroadcast(app, 1,
            Intent(Action.EXTERNAL_ALLOW_ALWAYS).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
        val accessNotification = NotificationCompat.Builder(app, "external-access")
            .setWhen(0)
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(app.getString(R.string.external_access_title))
            .setContentText(reason)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_navigation_close, app.getString(R.string.external_access_allow_once), allowOnceIntent)
            .addAction(R.drawable.ic_navigation_close, app.getString(R.string.external_access_allow_always), allowAlwaysIntent)
            .build()
        notification.notify(externalAccessNotificationId, accessNotification)
        return false
    }
}
