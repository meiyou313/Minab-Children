/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabAllowedAppsActivity (v0.9): per-app VPN (split tunneling).
 * Pick which apps go through the VPN and which go direct. The two
 * modes are the upstream VpnProfile fields: "only these apps use
 * the VPN" (mAllowedAppsVpnAreDisallowed=false) or "all apps except
 * these" (true, the default — an empty set means every app uses the
 * VPN, exactly as before). Saving writes the choice onto ALL
 * profiles and stores it, so configs imported later get the same
 * choice (see MinabAllowedApps / BulkImportHelper).
 *
 * The list is the launchable apps on the phone (their launcher
 * entries), loaded off the UI thread because loading ~100 icons
 * takes a moment. Minab itself is excluded: it must never be put
 * in either list.
 */

package de.blinkt.openvpn.activities

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import de.blinkt.openvpn.R
import de.blinkt.openvpn.minab.MinabAllowedApps

class MinabAllowedAppsActivity : BaseActivity() {

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        var checked: Boolean
    )

    private lateinit var listView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingView: TextView
    private lateinit var onlyRadio: RadioButton
    private lateinit var exceptRadio: RadioButton
    private lateinit var saveButton: Button
    private var entries: MutableList<AppEntry> = mutableListOf()
    private var adapter: ArrayAdapter<AppEntry>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.minab_allowed_apps)

        listView = findViewById(R.id.minab_allowed_apps_list)
        progressBar = findViewById(R.id.minab_allowed_apps_progress)
        loadingView = findViewById(R.id.minab_allowed_apps_loading)
        onlyRadio = findViewById(R.id.minab_allowed_apps_only)
        exceptRadio = findViewById(R.id.minab_allowed_apps_except)
        saveButton = findViewById(R.id.minab_allowed_apps_save)

        val stored = MinabAllowedApps.getStoredApps(this)
        val storedDisallowed = MinabAllowedApps.getStoredAreDisallowed(this)
        if (storedDisallowed) exceptRadio.isChecked = true else onlyRadio.isChecked = true

        saveButton.setOnClickListener { save() }
        loadApps(stored)
    }

    private fun loadApps(stored: Set<String>) {
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                emptyList()
            }
            val ownPackage = packageName
            val loaded = resolved
                .mapNotNull { info ->
                    val activityInfo = info.activityInfo ?: return@mapNotNull null
                    val pkg = activityInfo.packageName ?: return@mapNotNull null
                    if (pkg == ownPackage) return@mapNotNull null
                    val label = try {
                        info.loadLabel(pm).toString()
                    } catch (e: Exception) {
                        pkg
                    }
                    val icon = try {
                        info.loadIcon(pm)
                    } catch (e: Exception) {
                        null
                    }
                    AppEntry(pkg, label, icon, stored.contains(pkg))
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                entries = loaded.toMutableList()
                adapter = object : ArrayAdapter<AppEntry>(
                    this, R.layout.minab_allowed_app_row, entries
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = convertView ?: LayoutInflater.from(context)
                            .inflate(R.layout.minab_allowed_app_row, parent, false)
                        val entry = getItem(position)!!
                        (view.findViewById<ImageView>(R.id.minab_allowed_app_icon))
                            .setImageDrawable(entry.icon)
                        (view.findViewById<TextView>(R.id.minab_allowed_app_name)).text = entry.label
                        val box = view.findViewById<CheckBox>(R.id.minab_allowed_app_check)
                        box.setOnCheckedChangeListener(null)
                        box.isChecked = entry.checked
                        box.setOnCheckedChangeListener { _, checked -> entry.checked = checked }
                        view.setOnClickListener {
                            entry.checked = !entry.checked
                            notifyDataSetChanged()
                        }
                        return view
                    }
                }
                listView.adapter = adapter
                progressBar.visibility = View.GONE
                loadingView.visibility = View.GONE
                listView.visibility = View.VISIBLE
                saveButton.isEnabled = true
            }
        }.start()
    }

    private fun save() {
        saveButton.isEnabled = false
        val chosen = entries.filter { it.checked }.map { it.packageName }.toSet()
        val areDisallowed = exceptRadio.isChecked
        Thread {
            MinabAllowedApps.saveChoice(this, chosen, areDisallowed)
            MinabAllowedApps.applyToAllProfiles(this, chosen, areDisallowed)
            runOnUiThread {
                Toast.makeText(this, R.string.minab_per_app_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }
}
