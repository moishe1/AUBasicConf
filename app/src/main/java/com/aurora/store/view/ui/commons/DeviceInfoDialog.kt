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

package com.aurora.store.view.ui.commons

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.aurora.extensions.copyToClipBoard
import com.aurora.extensions.toast
import com.aurora.store.R
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_DEVICE_PHONE_NUMBER
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

class DeviceInfoDialog : DialogFragment() {

    private val deviceSerial: String
        get() = try {
            Settings.Secure.getString(
                requireContext().contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    /**
     * IMEI is only readable by privileged system apps (READ_PRIVILEGED_PHONE_STATE) or
     * platform-signed apps; otherwise this shows "unavailable".
     */
    @get:android.annotation.SuppressLint("MissingPermission", "HardwareIds")
    private val deviceImei: String
        get() = try {
            val tm = requireContext().getSystemService(android.content.Context.TELEPHONY_SERVICE)
                as? android.telephony.TelephonyManager
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm?.imei
            } else {
                @Suppress("DEPRECATION") tm?.deviceId
            }
            imei?.takeIf { it.isNotBlank() } ?: "unavailable"
        } catch (e: Exception) {
            "unavailable"
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_device_info, null)

        val phoneInputLayout = view.findViewById<TextInputLayout>(R.id.phoneInputLayout)
        val serialValue = view.findViewById<TextView>(R.id.serialValue)
        val deviceValue = view.findViewById<TextView>(R.id.deviceValue)
        val imeiValue = view.findViewById<TextView>(R.id.imeiValue)
        val copySerialButton = view.findViewById<MaterialButton>(R.id.copySerialButton)

        phoneInputLayout.editText?.setText(
            Preferences.getString(requireContext(), PREFERENCE_DEVICE_PHONE_NUMBER)
        )
        serialValue.text = deviceSerial
        deviceValue.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        imeiValue.text = deviceImei

        copySerialButton.setOnClickListener {
            requireContext().copyToClipBoard(deviceSerial)
            toast(R.string.toast_clipboard_copied)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_device_info)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val phoneNumber = phoneInputLayout.editText?.text?.toString()?.trim().orEmpty()
                Preferences.putString(
                    requireContext(),
                    PREFERENCE_DEVICE_PHONE_NUMBER,
                    phoneNumber
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> dismiss() }
            .create()
    }
}
