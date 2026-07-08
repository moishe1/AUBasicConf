/*
 * Aurora Store
 *  Copyright (C) 2021, Rahul Kumar Patel <whyorean@gmail.com>
 *
 *  Aurora Store is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  Aurora Store is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Aurora Store.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.aurora.store.data.providers

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.services.IPrivilegedCallback
import com.aurora.services.IPrivilegedService
import com.aurora.store.data.helper.DownloadHelper
import com.aurora.store.data.installer.AppInstaller
import com.aurora.store.data.installer.ServiceInstaller
import com.aurora.store.data.room.suite.ExternalApk
import com.aurora.store.util.Preferences
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports device status (installed apps, installer/service state, device info) to the
 * remote fleet-management backend and executes remote commands (install/uninstall/collectLogs).
 *
 * Uses the same base URL + deviceId convention as [RemoteWhitelistProvider].
 */
@Singleton
class DeviceReportProvider @Inject constructor(
    private val json: Json,
    @ApplicationContext private val context: Context,
    private val whitelistProvider: WhitelistProvider,
    private val downloadHelper: DownloadHelper,
    // Lazy so we never resolve AppDetailsHelper (which requires valid authData) on
    // external-app-only devices that never logged into Play Store.
    private val appDetailsHelper: Lazy<AppDetailsHelper>
) {

    private val TAG = DeviceReportProvider::class.java.simpleName

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val remoteWhitelistUrl: String
        get() = Preferences.getString(
            context,
            Preferences.PREFERENCE_REMOTE_WHITELIST_URL,
            "https://tiktalkkosher.com/.netlify/functions/devices?key=tiktalk@2000!"
        )

    private val isEnabled: Boolean
        get() = Preferences.getBoolean(context, Preferences.PREFERENCE_REMOTE_WHITELIST_ENABLED, true)

    private val deviceId: String
        get() = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    /**
     * Builds a status report, POSTs it to the backend, then executes any pending commands
     * returned and reports their results back.
     */
    suspend fun reportAndSync(): Unit = withContext(Dispatchers.IO) {
        if (!isEnabled) {
            Log.d(TAG, "Device reporting is disabled")
            return@withContext
        }

        try {
            val reportBody = buildReport().toString()
            val reportUrl = "$remoteWhitelistUrl&action=report&id=$deviceId"
            Log.d(TAG, "Reporting device status to: $reportUrl")

            val request = Request.Builder()
                .url(reportUrl)
                .addHeader("User-Agent", "Aurora-Store")
                .post(reportBody.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to report device status: HTTP ${response.code}")
                return@withContext
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                Log.d(TAG, "Empty response from report endpoint")
                return@withContext
            }

            val commands = try {
                json.parseToJsonElement(responseBody).jsonObject["commands"]?.jsonArray
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse commands from report response", e)
                null
            } ?: return@withContext

            for (command in commands) {
                executeCommand(command.jsonObject)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during device report/sync", e)
        }
    }

    private fun buildReport() = buildJsonObject {
        put("installedApps", buildInstalledApps())
        putJsonObject("serviceState") {
            val hasService = AppInstaller.hasAuroraService(context)
            put("auroraServicesInstalled", hasService)
            put("auroraServicesEnabled", hasService)
            put("currentInstaller", AppInstaller.getCurrentInstaller(context).name)
        }
        putJsonObject("deviceInfo") {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("imei", readImei())
        }
    }

    /**
     * Reads the device IMEI. This requires READ_PRIVILEGED_PHONE_STATE (Android 10+), which is
     * only granted to privileged system apps (/system/priv-app + a privapp-permissions allowlist
     * entry) or platform-signed apps. Returns "unavailable" when the app isn't privileged enough
     * or the device has no IMEI (e.g. Wi-Fi-only / emulator).
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun readImei(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
                as? android.telephony.TelephonyManager ?: return "unavailable"
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.imei
            } else {
                @Suppress("DEPRECATION") tm.deviceId
            }
            imei?.takeIf { it.isNotBlank() } ?: "unavailable"
        } catch (e: Exception) {
            Log.d(TAG, "IMEI unavailable: ${e.message}")
            "unavailable"
        }
    }

    private fun buildInstalledApps(): JsonArray = buildJsonArray {
        try {
            val packages = context.packageManager.getInstalledPackages(0)
            for (packageInfo in packages) {
                val appInfo = packageInfo.applicationInfo ?: continue
                // Exclude system apps; the backend fleet only cares about user-installed
                // apps (those it can actually install/uninstall via Aurora Services).
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

                add(
                    buildJsonObject {
                        put("pkg", packageInfo.packageName)
                        put("versionName", packageInfo.versionName ?: "")
                        put("versionCode", PackageInfoCompat.getLongVersionCode(packageInfo))
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate installed packages", e)
        }
    }

    private suspend fun executeCommand(command: kotlinx.serialization.json.JsonObject) {
        val commandId = try {
            command["id"]?.jsonPrimitive?.int
        } catch (e: Exception) {
            null
        } ?: run {
            Log.w(TAG, "Skipping command without valid id: $command")
            return
        }

        val type = command["type"]?.jsonPrimitive?.content
        val pkg = command["pkg"]?.jsonPrimitive?.content

        try {
            when (type) {
                "uninstall" -> {
                    if (pkg.isNullOrEmpty()) {
                        reportCommandResult(commandId, false, "Missing pkg for uninstall")
                        return
                    }
                    val (ok, message) = uninstallPackage(pkg)
                    reportCommandResult(commandId, ok, message)
                }

                "install" -> {
                    if (pkg.isNullOrEmpty()) {
                        reportCommandResult(commandId, false, "Missing pkg for install")
                        return
                    }
                    val (ok, message) = installPackage(pkg)
                    reportCommandResult(commandId, ok, message)
                }

                "collectLogs" -> {
                    val (ok, message) = collectAndUploadLogs()
                    reportCommandResult(commandId, ok, message)
                }

                else -> {
                    reportCommandResult(commandId, false, "Unknown command type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command $commandId ($type)", e)
            reportCommandResult(commandId, false, e.localizedMessage ?: "Execution error")
        }
    }

    /**
     * Uninstalls a package, preferring fully silent paths (no user prompt):
     *   1. Direct [PackageInstaller] uninstall — silent when this app is privileged (holds the
     *      signature|privileged DELETE_PACKAGES permission as a /system/priv-app or platform-signed app).
     *   2. The Aurora Services privileged companion (AIDL) if installed.
     *   3. Fallback: the system uninstall UI (requires the user to confirm on-device).
     */
    private fun uninstallPackage(packageName: String): Pair<Boolean, String> {
        // 1. Silent self-uninstall if we're a privileged system app.
        silentUninstallViaPackageInstaller(packageName)?.let { return it }

        // 2. Aurora Services privileged companion.
        if (AppInstaller.hasAuroraService(context)) {
            return try {
                silentUninstallViaServices(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Silent uninstall via Aurora Services failed for $packageName", e)
                Pair(false, e.localizedMessage ?: "Aurora Services uninstall failed")
            }
        }

        // 3. No privileged path available: fall back to system uninstall UI (user must confirm).
        return try {
            AppInstaller.uninstall(context, packageName)
            Pair(true, "Requested uninstall via system UI (app not privileged, no Aurora Services)")
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "System uninstall failed")
        }
    }

    /**
     * Attempts a fully silent uninstall via [PackageInstaller]. Only succeeds when the app is
     * privileged (DELETE_PACKAGES). Returns null when the system would require user confirmation
     * (i.e. the app isn't privileged) so the caller can fall back to another path; we deliberately
     * do NOT launch the confirmation dialog from here.
     */
    private fun silentUninstallViaPackageInstaller(packageName: String): Pair<Boolean, String>? {
        val resultRef = AtomicReference<Pair<Boolean, String>?>(null)
        val needsUserAction = AtomicReference(false)
        val action = "com.aurora.store.UNINSTALL_RESULT.$packageName.${System.currentTimeMillis()}"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (val status =
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Not privileged: the system wants a confirmation dialog. Signal fallback.
                        needsUserAction.set(true)
                        resultRef.set(Pair(false, "pending_user_action"))
                    }

                    PackageInstaller.STATUS_SUCCESS ->
                        resultRef.set(Pair(true, "Uninstalled silently (privileged)"))

                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        resultRef.set(
                            Pair(false, "Uninstall failed (status=$status${if (msg != null) ", $msg" else ""})")
                        )
                    }
                }
            }
        }

        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                Intent(action).setPackage(context.packageName),
                flags
            )

            context.packageManager.packageInstaller.uninstall(packageName, pendingIntent.intentSender)

            var waited = 0
            while (resultRef.get() == null && waited < 60_000) {
                Thread.sleep(250)
                waited += 250
            }

            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // ignore
            }

            // Not privileged enough for a silent uninstall -> let the caller fall back.
            if (needsUserAction.get()) null
            else resultRef.get() ?: Pair(false, "Timed out waiting for silent uninstall")
        } catch (e: Exception) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e2: Exception) {
                // ignore
            }
            Log.e(TAG, "PackageInstaller silent uninstall failed for $packageName, will fall back", e)
            null
        }
    }

    /**
     * Uses the Aurora Services privileged AIDL (deletePackageX) to silently uninstall a package.
     * Blocks until the service callback returns (capped by a timeout).
     */
    private fun silentUninstallViaServices(packageName: String): Pair<Boolean, String> {
        val resultRef = AtomicReference<Pair<Boolean, String>?>(null)
        val connectionRef = AtomicReference<ServiceConnection?>(null)

        Handler(Looper.getMainLooper()).post {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    try {
                        val service = IPrivilegedService.Stub.asInterface(binder)
                        if (!service.hasPrivilegedPermissions()) {
                            resultRef.set(Pair(false, "Aurora Services lacks privileged permissions"))
                            return
                        }

                        val callback = object : IPrivilegedCallback.Stub() {
                            override fun handleResult(packageName: String, returnCode: Int) {
                                setUninstallResult(resultRef, returnCode, null)
                            }

                            override fun handleResultX(
                                packageName: String,
                                returnCode: Int,
                                extra: String?
                            ) {
                                setUninstallResult(resultRef, returnCode, extra)
                            }
                        }

                        service.deletePackageX(
                            packageName,
                            0,
                            com.aurora.store.BuildConfig.APPLICATION_ID,
                            callback
                        )
                    } catch (e: Exception) {
                        resultRef.set(Pair(false, e.localizedMessage ?: "deletePackageX failed"))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (resultRef.get() == null) {
                        resultRef.set(Pair(false, "Disconnected from Aurora Services"))
                    }
                }
            }
            connectionRef.set(connection)

            val extensionPackage = AppInstaller.getAuroraServicePackage(context)
                ?: ServiceInstaller.PRIVILEGED_EXTENSION_PACKAGE_NAME
            val intent = Intent(ServiceInstaller.PRIVILEGED_EXTENSION_SERVICE_INTENT).apply {
                setPackage(extensionPackage)
            }
            try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                resultRef.set(Pair(false, e.localizedMessage ?: "bindService failed"))
            }
        }

        // Wait for the async service callback (max ~60s).
        var waited = 0
        while (resultRef.get() == null && waited < 60_000) {
            Thread.sleep(500)
            waited += 500
        }

        connectionRef.get()?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                // ignore
            }
        }

        return resultRef.get() ?: Pair(false, "Timed out waiting for Aurora Services uninstall")
    }

    private fun setUninstallResult(
        resultRef: AtomicReference<Pair<Boolean, String>?>,
        returnCode: Int,
        extra: String?
    ) {
        if (returnCode == PackageInstaller.STATUS_SUCCESS) {
            resultRef.set(Pair(true, "Uninstalled via Aurora Services"))
        } else {
            resultRef.set(Pair(false, "Uninstall failed (code=$returnCode${if (extra != null) ", $extra" else ""})"))
        }
    }

    /**
     * Installs a package by reusing the app's existing download + install pipeline
     * (the same path used to install whitelisted apps): for whitelisted external apps
     * we enqueue an [ExternalApk]; otherwise we resolve the app via Play Store
     * [AppDetailsHelper] and enqueue it via [DownloadHelper].
     */
    private suspend fun installPackage(packageName: String): Pair<Boolean, String> {
        // 1. External app present in the whitelist (has an apkUrl) -> reuse standalone pipeline.
        val externalApp = whitelistProvider.getExternalApp(packageName)
        if (externalApp != null) {
            return try {
                val externalApk = ExternalApk(
                    packageName = externalApp.packageName,
                    versionCode = externalApp.versionCode,
                    versionName = externalApp.versionName,
                    displayName = externalApp.displayName,
                    iconURL = externalApp.iconUrl ?: "",
                    developerName = "",
                    fileList = listOf(
                        com.aurora.gplayapi.data.models.PlayFile(
                            url = externalApp.apkUrl,
                            name = "${externalApp.packageName}.apk",
                            size = 0L
                        )
                    )
                )
                downloadHelper.enqueueStandalone(externalApk)
                Pair(true, "Enqueued external app install for $packageName")
            } catch (e: Exception) {
                Pair(false, e.localizedMessage ?: "Failed to enqueue external app")
            }
        }

        // 2. Play Store app -> resolve via AppDetailsHelper and reuse the standard pipeline.
        if (!AccountProvider.isLoggedIn(context)) {
            return Pair(false, "Cannot install $packageName: not a whitelisted external app and no Play Store session")
        }

        return try {
            val app = appDetailsHelper.get().getAppByPackageName(packageName)
            if (app.displayName.isEmpty()) {
                return Pair(false, "Play Store returned no details for $packageName")
            }
            downloadHelper.enqueueApp(app)
            Pair(true, "Enqueued Play Store install for $packageName")
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "Failed to resolve/install $packageName from Play Store")
        }
    }

    /**
     * Captures this app's own logcat (a normal app only gets its own logs) and POSTs it.
     * Output is capped to the last ~300 lines / ~64KB.
     */
    private fun collectAndUploadLogs(): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            val lines = ArrayDeque<String>()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    lines.addLast(line)
                    if (lines.size > 300) lines.removeFirst()
                }
            }
            try {
                process.destroy()
            } catch (e: Exception) {
                // ignore
            }

            var logText = lines.joinToString("\n")
            // Cap to ~64KB to keep the upload reasonable.
            val maxBytes = 64 * 1024
            if (logText.toByteArray().size > maxBytes) {
                logText = logText.takeLast(maxBytes)
            }

            val body = buildJsonObject {
                put("logs", logText)
            }.toString()

            val logsUrl = "$remoteWhitelistUrl&action=logs&id=$deviceId"
            val request = Request.Builder()
                .url(logsUrl)
                .addHeader("User-Agent", "Aurora-Store")
                .post(body.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Pair(true, "Uploaded ${lines.size} log lines")
            } else {
                Pair(false, "Log upload failed: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect/upload logs", e)
            Pair(false, e.localizedMessage ?: "Log collection failed")
        }
    }

    private fun reportCommandResult(commandId: Int, success: Boolean, message: String) {
        try {
            val body = buildJsonObject {
                put("commandId", commandId)
                put("status", if (success) "done" else "failed")
                put("message", message.take(500))
            }.toString()

            val resultUrl = "$remoteWhitelistUrl&action=command-result&id=$deviceId"
            val request = Request.Builder()
                .url(resultUrl)
                .addHeader("User-Agent", "Aurora-Store")
                .post(body.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to report command result for $commandId: HTTP ${response.code}")
            } else {
                Log.d(TAG, "Reported command $commandId result: ${if (success) "done" else "failed"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST command result for $commandId", e)
        }
    }
}
