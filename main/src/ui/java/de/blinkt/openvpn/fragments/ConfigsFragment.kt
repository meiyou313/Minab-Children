/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * ConfigsFragment (v0.4): the CONFIGS tab — a dedicated place to
 * manage configs. Group filter tabs on top, buttons for credentials
 * (this group / all) and backup, then the config list. Tap a config
 * for rename, credentials, category, ping or delete.
 */

package de.blinkt.openvpn.fragments

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.ListFragment
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.PingTester
import de.blinkt.openvpn.minab.SharedCredentials
import java.util.TreeSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConfigsFragment : ListFragment() {
    private var selectedCategory: String? = null
    private var mArrayadapter: ArrayAdapter<VpnProfile>? = null
    private var chipsContainer: LinearLayout? = null
    private lateinit var backupLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri != null) exportBackup(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.configs_fragment, container, false)
        chipsContainer = v.findViewById(R.id.minab_configs_chips)
        v.findViewById<Button>(R.id.minab_configs_credentials_group)?.setOnClickListener {
            showCredentialsDialog(selectedCategory)
        }
        v.findViewById<Button>(R.id.minab_configs_credentials_all)?.setOnClickListener {
            showCredentialsDialog(null)
        }
        v.findViewById<Button>(R.id.minab_configs_backup)?.setOnClickListener {
            val profiles = ProfileManager.getInstance(requireContext()).getProfiles()
            if (profiles.isEmpty()) {
                Toast.makeText(requireContext(), R.string.minab_backup_none, Toast.LENGTH_SHORT).show()
            } else {
                backupLauncher.launch("minab-backup.ovpn.zip")
            }
        }
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun refreshList() {
        if (!isAdded) return
        val context = requireContext()
        val pm = ProfileManager.getInstance(context)
        val all = pm.getProfiles().toList()
        val filtered = if (selectedCategory == null) all
        else all.filter { it.getCategory() == selectedCategory }
        val sorted = TreeSet<VpnProfile>(Comparator { a, b -> (a.mName ?: "").compareTo(b.mName ?: "") })
        sorted.addAll(filtered)
        if (mArrayadapter == null) {
            mArrayadapter = object : ArrayAdapter<VpnProfile>(
                context, android.R.layout.simple_list_item_2, android.R.id.text1,
                ArrayList(sorted)
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val profile = getItem(position)!!
                    (view.findViewById<TextView>(android.R.id.text1)).text = profile.mName
                    (view.findViewById<TextView>(android.R.id.text2)).text =
                        "[" + profile.getCategory() + "]" +
                            if (profile.isUserPWAuth()) " · " + profile.mUsername else ""
                    return view
                }
            }
            listAdapter = mArrayadapter
        } else {
            mArrayadapter!!.clear()
            mArrayadapter!!.addAll(sorted)
            mArrayadapter!!.notifyDataSetChanged()
        }
        rebuildChips()
    }

    private fun rebuildChips() {
        val container = chipsContainer ?: return
        container.removeAllViews()
        val context = requireContext()
        val categories = mutableListOf<String?>(null)
        categories.addAll(CategoryStore.getCategories(context))
        for (category in categories) {
            val label = category ?: getString(R.string.minab_all_categories)
            val selected = category == selectedCategory
            val chip = TextView(context)
            chip.text = label
            chip.textSize = 13f
            chip.setTextColor(
                resources.getColor(
                    if (selected) R.color.minab_teal_dark else R.color.minab_chip_text,
                    context.theme
                )
            )
            chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            chip.setBackgroundResource(
                if (selected) R.drawable.minab_chip_bg_selected
                else R.drawable.minab_chip_bg
            )
            chip.setPadding(dp(14), dp(7), dp(14), dp(7))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(8)
            chip.layoutParams = params
            chip.setOnClickListener {
                selectedCategory = category
                refreshList()
            }
            container.addView(chip)
        }
        val add = TextView(context)
        add.text = "+ " + getString(R.string.minab_new_group)
        add.textSize = 13f
        add.setTextColor(resources.getColor(R.color.minab_teal, context.theme))
        add.setBackgroundResource(R.drawable.minab_chip_bg)
        add.setPadding(dp(14), dp(7), dp(14), dp(7))
        add.setOnClickListener { showNewCategoryDialog { refreshList() } }
        container.addView(add)
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val profile = mArrayadapter?.getItem(position) ?: return
        showConfigActionsDialog(profile)
    }

    private fun showConfigActionsDialog(profile: VpnProfile) {
        val actions = arrayOf(
            getString(R.string.minab_rename_config),
            getString(R.string.minab_change_credentials),
            getString(R.string.minab_assign_category),
            getString(R.string.minab_ping_one),
            getString(R.string.minab_delete_config)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.minab_config_actions, profile.mName))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenameDialog(profile)
                    1 -> showProfileCredentialsDialog(profile)
                    2 -> showAssignCategoryDialog(profile)
                    3 -> pingOne(profile)
                    4 -> showDeleteDialog(profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(profile: VpnProfile) {
        val entry = EditText(requireContext())
        entry.setSingleLine()
        entry.setText(profile.mName)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_rename_config)
            .setView(entry)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = entry.text.toString().trim()
                if (name.isNotEmpty()) {
                    profile.mName = name
                    ProfileManager.saveProfile(requireContext(), profile)
                    Toast.makeText(requireContext(), R.string.minab_config_renamed, Toast.LENGTH_SHORT).show()
                    refreshList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileCredentialsDialog(profile: VpnProfile) {
        if (!profile.isUserPWAuth()) {
            Toast.makeText(requireContext(), R.string.minab_credentials_none_applied, Toast.LENGTH_SHORT).show()
            return
        }
        showCredentialsInputDialog { username, password ->
            profile.mUsername = username
            profile.mPassword = password
            ProfileManager.saveProfile(requireContext(), profile)
            Toast.makeText(
                requireContext(),
                getString(R.string.minab_credentials_applied, 1),
                Toast.LENGTH_SHORT
            ).show()
            refreshList()
        }
    }

    /** group == null means "all profiles". */
    private fun showCredentialsDialog(group: String?) {
        val title = if (group == null) getString(R.string.minab_credentials_for_all)
        else getString(R.string.minab_credentials_for_group) + " — " + group
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(R.string.minab_shared_credentials_hint)
            .setPositiveButton(R.string.minab_apply_credentials) { _, _ ->
                showCredentialsInputDialog { username, password ->
                    SharedCredentials.save(requireContext(), username, password)
                    val updated = if (group == null) {
                        SharedCredentials.applyToAllProfiles(requireContext(), username, password)
                    } else {
                        SharedCredentials.applyToCategory(requireContext(), group, username, password)
                    }
                    Toast.makeText(
                        requireContext(),
                        if (group == null) getString(R.string.minab_credentials_applied, updated)
                        else getString(R.string.minab_credentials_group_applied, updated),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCredentialsInputDialog(onApply: (String, String) -> Unit) {
        val context = requireContext()
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(20), dp(8), dp(20), dp(8))
        val userField = EditText(context)
        userField.hint = getString(R.string.minab_username)
        userField.setText(SharedCredentials.getUsername(context))
        val passField = EditText(context)
        passField.hint = getString(R.string.minab_password)
        passField.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        passField.setText(SharedCredentials.getPassword(context))
        layout.addView(userField)
        layout.addView(passField)
        AlertDialog.Builder(context)
            .setTitle(R.string.minab_change_credentials)
            .setView(layout)
            .setPositiveButton(R.string.minab_apply_credentials) { _, _ ->
                onApply(userField.text.toString(), passField.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAssignCategoryDialog(profile: VpnProfile) {
        val categories = CategoryStore.getCategories(requireContext())
        val labels = (categories + getString(R.string.minab_new_category)).toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.minab_assign_category))
            .setItems(labels) { _, which ->
                if (which < categories.size) {
                    assignCategory(profile, categories[which])
                } else {
                    showNewCategoryDialog { name -> assignCategory(profile, name) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNewCategoryDialog(onCreated: (String) -> Unit) {
        val entry = EditText(requireContext())
        entry.setSingleLine()
        entry.hint = getString(R.string.minab_group_name_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_new_group)
            .setView(entry)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = CategoryStore.normalise(entry.text.toString())
                CategoryStore.addCategory(requireContext(), name)
                onCreated(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun assignCategory(profile: VpnProfile, category: String) {
        profile.setCategory(category)
        CategoryStore.addCategory(requireContext(), category)
        ProfileManager.saveProfile(requireContext(), profile)
        refreshList()
    }

    private fun showDeleteDialog(profile: VpnProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_delete_config)
            .setMessage(getString(R.string.minab_delete_config_confirm, profile.mName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ProfileManager.getInstance(requireContext()).removeProfile(requireContext(), profile)
                Toast.makeText(requireContext(), R.string.minab_config_deleted, Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pingOne(profile: VpnProfile) {
        val tester = PingTester()
        tester.testAll(listOf(profile), object : PingTester.PingListener {
            override fun onResult(result: PingTester.Result) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    result.profile.mLastPingMs =
                        if (result.status == PingTester.Status.OK) result.ms else -1
                    result.profile.mLastPingTime = result.timestampMs
                    result.profile.mLastPingResult = result.displayText()
                    ProfileManager.saveProfile(requireContext(), result.profile)
                    refreshList()
                }
            }

            override fun onFinished(cancelled: Boolean) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(requireContext(), R.string.minab_ping_done, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** Export every profile as a .ovpn file inside one zip. Runs off
        the UI thread; the result toast is posted back to it. */
    private fun exportBackup(uri: Uri) {
        val context = requireContext().applicationContext
        Thread {
            var count = 0
            var ok = false
            try {
                val profiles = ProfileManager.getInstance(context).getProfiles().toList()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out).use { zip ->
                        for (profile in profiles) {
                            try {
                                val name = profile.mName
                                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                                    .ifEmpty { "config" } + ".ovpn"
                                zip.putNextEntry(ZipEntry(name))
                                zip.write(profile.getConfigFile(context, false).toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                                count++
                            } catch (ignored: Exception) {
                            }
                        }
                    }
                }
                ok = count > 0
            } catch (ignored: Exception) {
            }
            val doneCount = count
            val success = ok
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.minab_backup_done, doneCount)
                    else getString(R.string.minab_backup_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
