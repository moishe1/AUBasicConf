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

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.aurora.store.util.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single app-install request submitted by this device, as returned by the backend.
 */
data class AppRequest(
    val id: String,
    val appName: String,
    val pkg: String,
    val note: String,
    val status: String,
    val adminNote: String,
    val createdAt: String,
    val updatedAt: String
)

/**
 * A catalog app the user can choose from when submitting a request. Mirrors the admin
 * "App Catalog" entries (Apps tab on the web).
 */
data class CatalogApp(
    val pkg: String,
    val name: String
)

/**
 * Submits app-install requests to the remote fleet-management backend and lists this
 * device's own requests (with their approval status + admin note).
 *
 * Uses the same base URL + deviceId convention as [DeviceReportProvider].
 */
@Singleton
class RequestProvider @Inject constructor(
    private val json: Json,
    @ApplicationContext private val context: Context
) {

    private val TAG = RequestProvider::class.java.simpleName

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

    private val deviceId: String
        get() = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    /**
     * Submits a new app-install request. Either [appName] or [pkg] must be non-empty.
     * Returns true on success.
     */
    suspend fun submitRequest(appName: String, pkg: String, note: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("appName", appName)
                    put("pkg", pkg)
                    put("note", note)
                }.toString()

                val url = "$remoteWhitelistUrl&action=request&id=$deviceId"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Aurora-Store")
                    .post(body.toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to submit request: HTTP ${response.code}")
                    return@withContext false
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    Log.e(TAG, "Empty response from request endpoint")
                    return@withContext false
                }

                val success = try {
                    json.parseToJsonElement(responseBody).jsonObject["success"]
                        ?.jsonPrimitive?.content?.toBoolean() ?: false
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse submit response", e)
                    false
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error submitting request", e)
                false
            }
        }

    /**
     * Fetches the admin app catalog (the same list shown on the web "Apps" tab) so the user
     * can pick an app name to request. Returns an empty list on any error.
     */
    suspend fun fetchCatalog(): List<CatalogApp> = withContext(Dispatchers.IO) {
        try {
            val url = "$remoteWhitelistUrl&action=catalog"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Aurora-Store")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch catalog: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) return@withContext emptyList()

            val array = try {
                json.parseToJsonElement(responseBody).jsonArray
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse catalog response", e)
                null
            } ?: return@withContext emptyList()

            array.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    fun field(name: String): String {
                        val prim = obj[name] as? JsonPrimitive ?: return ""
                        return if (prim is JsonNull) "" else prim.content
                    }
                    val pkg = field("pkg")
                    if (pkg.isEmpty()) return@mapNotNull null
                    CatalogApp(pkg = pkg, name = field("name").ifEmpty { pkg })
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error fetching catalog", e)
            emptyList()
        }
    }

    /**
     * Fetches this device's requests (newest first). Returns an empty list on any error.
     */
    suspend fun fetchMyRequests(): List<AppRequest> = withContext(Dispatchers.IO) {
        try {
            val url = "$remoteWhitelistUrl&action=requests&id=$deviceId"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Aurora-Store")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch requests: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                Log.d(TAG, "Empty response from requests endpoint")
                return@withContext emptyList()
            }

            val requests = try {
                json.parseToJsonElement(responseBody).jsonObject["requests"]?.jsonArray
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse requests response", e)
                null
            } ?: return@withContext emptyList()

            requests.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    fun field(name: String): String {
                        val prim = obj[name] as? JsonPrimitive ?: return ""
                        return if (prim is JsonNull) "" else prim.content
                    }
                    AppRequest(
                        id = field("id"),
                        appName = field("appName"),
                        pkg = field("pkg"),
                        note = field("note"),
                        status = field("status"),
                        adminNote = field("adminNote"),
                        createdAt = field("createdAt"),
                        updatedAt = field("updatedAt")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse a request entry", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error fetching requests", e)
            emptyList()
        }
    }
}
