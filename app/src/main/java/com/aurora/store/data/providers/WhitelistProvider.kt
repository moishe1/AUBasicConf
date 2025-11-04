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
import android.content.SharedPreferences
import android.util.Log
import com.aurora.extensions.isNAndAbove
import com.aurora.store.data.model.ExternalApp
import com.aurora.store.util.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhitelistProvider @Inject constructor(
    private val json: Json,
    @ApplicationContext val context: Context,
) {

    private val PREFERENCE_WHITELIST = "PREFERENCE_WHITELIST"
    private val TAG = WhitelistProvider::class.java.simpleName

    var whitelist: MutableSet<String>
        set(value) {
            Log.d(TAG, "Setting whitelist with ${value.size} entries: ${value.take(5)}")
            Preferences.putString(
                context,
                PREFERENCE_WHITELIST,
                json.encodeToString(value)
            )
        }
        get() {
            return try {
                val rawWhitelist = if (isNAndAbove) {
                    val refMethod = Context::class.java.getDeclaredMethod(
                        "getSharedPreferences",
                        File::class.java,
                        Int::class.java
                    )
                    val refSharedPreferences = refMethod.invoke(
                        context,
                        File("/product/etc/com.aurora.store/whitelist.xml"),
                        Context.MODE_PRIVATE
                    ) as SharedPreferences

                    Preferences.getPrefs(context)
                        .getString(
                            PREFERENCE_WHITELIST,
                            refSharedPreferences.getString(PREFERENCE_WHITELIST, "")
                        )
                } else {
                    Preferences.getString(context, PREFERENCE_WHITELIST)
                }
                if (rawWhitelist!!.isEmpty()) {
                    Log.d(TAG, "No whitelist found, returning empty set")
                    mutableSetOf()
                } else {
                    val whitelistSet = json.decodeFromString<MutableSet<String>>(rawWhitelist)
                    Log.d(TAG, "Retrieved whitelist with ${whitelistSet.size} entries: ${whitelistSet.take(5)}")
                    whitelistSet
                }
            } catch (e: Exception) {
                mutableSetOf()
            }
        }

    /**
     * Extract package name from whitelist entry
     * Formats:
     * - "com.package.name"
     * - "com.package.name CategoryName"
     * - "AppName|com.package.name|version|url|icon|category"
     */
    private fun extractPackageName(entry: String): String {
        return if (ExternalApp.isExternalApp(entry)) {
            // External app format: extract package name from second field
            entry.split("|").getOrNull(1)?.trim() ?: ""
        } else {
            // Standard format: package name before space
            entry.substringBefore(" ")
        }
    }

    /**
     * Extract category from whitelist entry (if present)
     * Formats:
     * - "com.package.name CategoryName"
     * - "AppName|com.package.name|version|url|icon|category"
     * Returns null if no category specified
     */
    fun extractCategory(entry: String): String? {
        return if (ExternalApp.isExternalApp(entry)) {
            // External app format: category is 6th field
            entry.split("|").getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            // Standard format: category after space
            val parts = entry.split(" ", limit = 2)
            if (parts.size > 1) parts[1] else null
        }
    }

    /**
     * Get all external apps from whitelist
     */
    fun getExternalApps(): List<ExternalApp> {
        return whitelist
            .filter { ExternalApp.isExternalApp(it) }
            .mapNotNull { ExternalApp.fromWhitelistEntry(it) }
    }

    /**
     * Get external app by package name
     */
    fun getExternalApp(packageName: String): ExternalApp? {
        return getExternalApps().firstOrNull { it.packageName == packageName }
    }

    /**
     * Get whitelist entries grouped by category
     * Returns a map of category name to list of package names
     * Uncategorized apps are in the "Other" category
     */
    fun getWhitelistByCategory(): Map<String, List<String>> {
        val categorized = mutableMapOf<String, MutableList<String>>()

        whitelist.forEach { entry ->
            val packageName = extractPackageName(entry)
            val category = extractCategory(entry) ?: "Other"
            categorized.getOrPut(category) { mutableListOf() }.add(packageName)
        }

        return categorized
    }

    /**
     * Get all package names from whitelist (ignoring categories)
     */
    fun getPackageNames(): Set<String> {
        return whitelist.map { extractPackageName(it) }.toSet()
    }

    fun isWhitelisted(packageName: String): Boolean {
        return getPackageNames().contains(packageName)
    }


    fun addToWhitelist(packageName: String) {
        whitelist = whitelist.apply {
            add(packageName)
        }
    }

    fun removeFromWhitelist(packageName: String) {
        whitelist = whitelist.apply {
            remove(packageName)
        }
    }

    /**
     * Check if whitelist contains only external apps (no Play Store apps)
     * Returns true if all apps are external format, false if any require Play Store auth
     */
    fun hasOnlyExternalApps(): Boolean {
        val allEntries = whitelist
        if (allEntries.isEmpty()) {
            // Empty whitelist = no apps = skip login
            return true
        }

        // Check if ALL entries are external apps
        return allEntries.all { ExternalApp.isExternalApp(it) }
    }
}
