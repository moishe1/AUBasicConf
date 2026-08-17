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

package com.aurora.store.view.ui.requests

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.aurora.store.R
import com.aurora.store.data.providers.AppRequest
import com.aurora.store.data.providers.CatalogApp
import com.aurora.store.databinding.FragmentRequestsBinding
import com.aurora.store.view.ui.commons.BaseFragment
import com.aurora.store.viewmodel.all.RequestsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RequestsFragment : BaseFragment<FragmentRequestsBinding>() {

    private val viewModel: RequestsViewModel by viewModels()

    // Current catalog choices, plus the user's selection.
    private var catalogApps: List<CatalogApp> = emptyList()
    private var selectedApp: CatalogApp? = null
    private var otherSelected = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setTitle(R.string.title_requests)

        binding.btnSubmit.setOnClickListener { submit() }

        binding.dropdownApp.setOnItemClickListener { _, _, position, _ ->
            if (position >= catalogApps.size) {
                // "Other (not listed)"
                otherSelected = true
                selectedApp = null
                binding.inputCustomName.isVisible = true
            } else {
                otherSelected = false
                selectedApp = catalogApps[position]
                binding.inputCustomName.isVisible = false
            }
            binding.inputAppPicker.error = null
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.catalog.collectLatest { setupPicker(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.requests.collectLatest { renderRequests(it) }
        }

        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun setupPicker(apps: List<CatalogApp>) {
        catalogApps = apps
        val names = apps.map { it.name.ifEmpty { it.pkg } } + getString(R.string.request_other)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        binding.dropdownApp.setAdapter(adapter)
    }

    private fun submit() {
        val note = binding.inputNote.editText?.text?.toString()?.trim().orEmpty()

        val appName: String
        val pkg: String

        if (otherSelected) {
            appName = binding.editCustomName.text?.toString()?.trim().orEmpty()
            pkg = ""
            if (appName.isEmpty()) {
                binding.inputCustomName.error = getString(R.string.request_name_required)
                return
            }
            binding.inputCustomName.error = null
        } else {
            val app = selectedApp
            if (app == null) {
                binding.inputAppPicker.error = getString(R.string.request_choose_required)
                return
            }
            appName = app.name.ifEmpty { app.pkg }
            pkg = app.pkg
        }
        binding.inputAppPicker.error = null

        binding.btnSubmit.isEnabled = false
        viewModel.submit(appName, pkg, note) { success ->
            if (!isAdded) return@submit
            binding.btnSubmit.isEnabled = true
            if (success) {
                binding.dropdownApp.setText("", false)
                binding.editCustomName.setText("")
                binding.inputCustomName.isVisible = false
                binding.inputNote.editText?.setText("")
                selectedApp = null
                otherSelected = false
                Toast.makeText(requireContext(), R.string.request_sent, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.request_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderRequests(requests: List<AppRequest>) {
        val container = binding.requestsContainer
        container.removeAllViews()

        if (requests.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                setText(R.string.request_empty)
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
            }
            container.addView(empty)
            return
        }

        val inflater = layoutInflater
        for (request in requests) {
            val itemView = inflater.inflate(R.layout.item_request, container, false)

            itemView.findViewById<TextView>(R.id.txt_app_name).text =
                request.appName.ifEmpty { request.pkg }

            val pkgView = itemView.findViewById<TextView>(R.id.txt_pkg)
            if (request.pkg.isNotEmpty()) {
                pkgView.text = request.pkg
                pkgView.isVisible = true
            } else {
                pkgView.isVisible = false
            }

            val noteView = itemView.findViewById<TextView>(R.id.txt_note)
            if (request.note.isNotEmpty()) {
                noteView.text = request.note
                noteView.isVisible = true
            } else {
                noteView.isVisible = false
            }

            val statusView = itemView.findViewById<TextView>(R.id.txt_status)
            statusView.text = statusLabel(request.status)
            statusView.setBackgroundColor(statusColor(request.status))

            val adminNoteView = itemView.findViewById<TextView>(R.id.txt_admin_note)
            if (request.adminNote.isNotEmpty()) {
                adminNoteView.text = getString(R.string.request_admin_note, request.adminNote)
                adminNoteView.isVisible = true
            } else {
                adminNoteView.isVisible = false
            }

            container.addView(itemView)
        }
    }

    private fun statusLabel(status: String): String = when (status.lowercase()) {
        "approved" -> getString(R.string.request_status_approved)
        "denied" -> getString(R.string.request_status_denied)
        else -> getString(R.string.request_status_pending)
    }

    private fun statusColor(status: String): Int = when (status.lowercase()) {
        "approved" -> Color.parseColor("#43A047") // green
        "denied" -> Color.parseColor("#E53935") // red
        else -> Color.parseColor("#FB8C00") // amber/orange
    }
}
