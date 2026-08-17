/*
 * Aurora Store
 *  Copyright (C) 2021, Rahul Kumar Patel <whyorean@gmail.com>
 *  Copyright (C) 2022, The Calyx Institute
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

package com.aurora.store

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.ime
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.lifecycle.lifecycleScope
import androidx.navigation.FloatingWindow
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.aurora.store.data.model.NetworkStatus
import com.aurora.store.data.receiver.MigrationReceiver
import com.aurora.store.databinding.ActivityMainBinding
import com.aurora.store.util.PackageUtil
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_DEFAULT_SELECTED_TAB
import com.aurora.store.view.ui.sheets.NetworkDialogSheet
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var B: ActivityMainBinding

    // TopLevelFragments
    private val topLevelFrags = listOf(
        R.id.appsContainerFragment,
        R.id.requestsFragment,
        R.id.updatesFragment
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Check and run migrations first if required
        // This is needed thanks to OEMs breaking the MY_PACKAGE_REPLACED API
        MigrationReceiver.runMigrationsIfRequired(this)

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        B = ActivityMainBinding.inflate(layoutInflater)
        setContentView(B.root)

        // Adjust root view's paddings for edgeToEdge display
        ViewCompat.setOnApplyWindowInsetsListener(B.root) { root, windowInsets ->
            val insets = windowInsets.getInsets(systemBars() or displayCutout() or ime())
            root.setPadding(insets.left, insets.top, insets.right, 0)
            windowInsets
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        if (!PackageUtil.isTv(this)) {
            viewModel.networkProvider.status.onEach { networkStatus ->
                when (networkStatus) {
                    NetworkStatus.AVAILABLE -> {
                        if (!supportFragmentManager.isDestroyed && isIntroDone()) {
                            val fragment = supportFragmentManager
                                .findFragmentByTag(NetworkDialogSheet.TAG)
                            fragment?.let {
                                supportFragmentManager.beginTransaction()
                                    .remove(fragment)
                                    .commitAllowingStateLoss()
                            }
                        }

                    }

                    NetworkStatus.UNAVAILABLE -> {
                        if (!supportFragmentManager.isDestroyed && isIntroDone()) {
                            supportFragmentManager.beginTransaction()
                                .add(NetworkDialogSheet.newInstance(), NetworkDialogSheet.TAG)
                                .commitAllowingStateLoss()
                        }
                    }
                }
            }.launchIn(AuroraApp.scope)
        }

        B.navView.setupWithNavController(navController)

        // Handle quick exit from back actions
        val defaultTab = when (Preferences.getInteger(this, PREFERENCE_DEFAULT_SELECTED_TAB)) {
            1 -> R.id.updatesFragment
            else -> R.id.appsContainerFragment
        }
        onBackPressedDispatcher.addCallback(this) {
            if (navController.currentDestination?.id in topLevelFrags) {
                if (navController.currentDestination?.id == defaultTab) {
                    finish()
                } else {
                    navController.navigate(defaultTab)
                }
            } else if (navHostFragment.childFragmentManager.backStackEntryCount == 0) {
                // We are on either on onboarding or splash fragment
                finish()
            } else {
                navController.navigateUp()
            }
        }

        // Handle views on fragments
        navController.addOnDestinationChangedListener { _, navDestination, _ ->
            if (navDestination !is FloatingWindow) {
                when (navDestination.id) {
                    in topLevelFrags -> B.navView.visibility = View.VISIBLE
                    else -> B.navView.visibility = View.GONE
                }
            }
        }

        // Updates
        lifecycleScope.launch {
            viewModel.updateHelper.updates.collectLatest { list ->
                B.navView.getOrCreateBadge(R.id.updatesFragment).apply {
                    isVisible = !list.isNullOrEmpty()
                    number = list?.size ?: 0
                }
            }
        }

        // Prompt for phone number on first launch if not yet saved
        promptForPhoneNumberIfRequired()
    }

    private fun promptForPhoneNumberIfRequired() {
        val savedPhoneNumber =
            Preferences.getString(this, Preferences.PREFERENCE_DEVICE_PHONE_NUMBER)
        if (savedPhoneNumber.isNotEmpty()) return

        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.device_info_phone_number_hint)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.phone_number_prompt_title)
            .setMessage(R.string.phone_number_prompt_message)
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val phoneNumber = editText.text.toString().trim()
                    if (phoneNumber.isEmpty()) {
                        editText.error = getString(R.string.phone_number_prompt_empty)
                    } else {
                        Preferences.putString(
                            this,
                            Preferences.PREFERENCE_DEVICE_PHONE_NUMBER,
                            phoneNumber
                        )
                        dialog.dismiss()
                    }
                }
        }
        dialog.show()
    }

    private fun isIntroDone(): Boolean {
        return Preferences.getBoolean(this@MainActivity, Preferences.PREFERENCE_INTRO)
    }
}
