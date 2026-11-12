/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * ConfigsFragment (v0.4): the CONFIGS tab — a dedicated place to
 * manage configs. Group filter tabs on top, buttons for credentials
 * (this group / all) and backup, then the config list. Tap a config
 * for rename, credentials, category, ping or delete.
 *
 * v0.5: minimal rows (checkbox, country flag, small name) like
 * Home; ticking boxes selects configs for Delete selected and
 * Share as zip; the Share button shares the selection, or the
 * current group, or all configs as one zip file (Android share
 * intent via the app FileProvider); the credentials buttons sit
 * under a plain "VPN username & password" explanation.
 */

package de.blinkt.openvpn.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.ListFragment
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.FlagHelper
import de.blinkt.openvpn.minab.PingTester
import de.blinkt.openvpn.minab.RealTester
import de.blinkt.openvpn.minab.SharedCredentials
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.TreeSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConfigsFragment : ListFragment() {
    private var selectedCategory: String? = null
    private var mArrayadapter: ArrayAdapter<VpnProfile>? = null
    private var chipsContainer: LinearLayout? = null
    private lateinit var backupLauncher: ActivityResultLauncher<String>
    /** UUIDs of the configs ticked for Delete / Share. */
    private val selectedUuids = mutableSetOf<String>()
    private var selectionRow: View? = null
    private var shareButton: Button? = null
    // --- Minab v0.6: the two server tests (Test ping / Real test) ---
    // The Real test runner itself is minab.RealTester (shared with
    // Home); this fragment only owns the UI state and the connect
    // action (startVPN). Ping tests are independent (they do not
    // touch the tunnel); real tests are exclusive app-wide.
    private var pingTester: PingTester? = null
    private var pingDoneCount: Int = 0
    private var pingTotalCount: Int = 0
    private var testProgressRow: View? = null
    private var testProgressBar: ProgressBar? = null
    private var testStatusView: TextView? = null
    private var testCancelButton: Button? = null
    /** True while our real test runs. */
    private var realTestActive: Boolean = false
    private var realTester: RealTester? = null
    /** Bumped on every start/cancel so listener callbacks from a
        superseded run are ignored. */
    private var realTestRunId = 0
    private val testPauseHandler = Handler(Looper.getMainLooper())
    private val testPauseStop = Runnable {
        if (realTestActive) {
            cancelRealTest()
            try {
                Toast.makeText(requireContext(), R.string.minab_real_cancelled, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Fragment gone meanwhile: nothing to tell.
            }
        }
    }

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
        shareButton = v.findViewById(R.id.minab_configs_share)
        shareButton?.setOnClickListener { shareCurrentScope() }
        selectionRow = v.findViewById(R.id.minab_configs_selection_row)
        v.findViewById<Button>(R.id.minab_configs_delete_selected)?.setOnClickListener {
            showDeleteSelectedDialog()
        }
        v.findViewById<Button>(R.id.minab_configs_share_selected)?.setOnClickListener {
            shareProfiles(selectedProfiles())
        }
        // Minab v0.6: the two server tests. Test ping is the fast
        // parallel check; Real test actually connects each server
        // and opens Telegram Web through it (see startRealTest).
        v.findViewById<Button>(R.id.minab_configs_test_ping)?.setOnClickListener {
            startPingTest(visibleProfiles())
        }
        v.findViewById<Button>(R.id.minab_configs_test_real)?.setOnClickListener {
            startRealTest(visibleProfiles())
        }
        testProgressRow = v.findViewById(R.id.minab_configs_test_progress_row)
        testProgressBar = v.findViewById(R.id.minab_configs_test_progress)
        testStatusView = v.findViewById(R.id.minab_configs_test_status)
        testCancelButton = v.findViewById(R.id.minab_configs_test_cancel_btn)
        testCancelButton?.setOnClickListener {
            // The same Cancel button stops whichever test runs.
            if (realTestActive) {
                cancelRealTest()
                Toast.makeText(requireContext(), R.string.minab_real_cancelled, Toast.LENGTH_SHORT).show()
            } else {
                cancelPingTest()
                Toast.makeText(requireContext(), R.string.minab_ping_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
        v.findViewById<ListView>(android.R.id.list).setOnItemLongClickListener { _, _, position, _ ->
            val profile = mArrayadapter?.getItem(position) ?: return@setOnItemLongClickListener false
            toggleSelection(profile)
            true
        }
        return v
    }

    override fun onResume() {
        super.onResume()
        // Minab v0.6: we are back (e.g. after our own LaunchVPN
        // round-trip) — do not stop a running real test for it.
        testPauseHandler.removeCallbacks(testPauseStop)
        refreshList()
    }

    override fun onPause() {
        super.onPause()
        // Minab v0.6: leaving Configs mid-run stops the real test —
        // but launching LaunchVPN pauses us briefly too, so wait
        // 8 s: a quick round-trip (our own connect/disconnect)
        // resumes in time and the run survives it.
        if (realTestActive) testPauseHandler.postDelayed(testPauseStop, 8000)
    }

    override fun onDestroy() {
        // Stop the tests when the fragment is really gone. Results
        // already saved on profiles are kept.
        testPauseHandler.removeCallbacks(testPauseStop)
        cancelPingTest()
        cancelRealTest()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun refreshList() {
        if (!isAdded) return
        val context = requireContext()
        val pm = ProfileManager.getInstance(context)
        val all = pm.getProfiles().toList()
        // Drop selections for configs that no longer exist.
        selectedUuids.retainAll(all.map { it.getUUIDString() }.toSet())
        val filtered = if (selectedCategory == null) all
        else all.filter { it.getCategory() == selectedCategory }
        val sorted = TreeSet<VpnProfile>(Comparator { a, b -> (a.mName ?: "").compareTo(b.mName ?: "") })
        sorted.addAll(filtered)
        if (mArrayadapter == null) {
            mArrayadapter = object : ArrayAdapter<VpnProfile>(
                context, R.layout.minab_config_row, ArrayList(sorted)
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView
                        ?: LayoutInflater.from(context).inflate(R.layout.minab_config_row, parent, false)
                    val profile = getItem(position)!!
                    (view.findViewById<TextView>(R.id.minab_config_name)).text = profile.mName
                    // Minab v0.6: the subtitle also carries the last
                    // test results when present, so the Configs list
                    // shows them without opening Home.
                    val subtitle = StringBuilder()
                    subtitle.append("[").append(profile.getCategory()).append("]")
                    if (profile.mLastPingResult.isNotEmpty()) {
                        subtitle.append(" · ").append(profile.mLastPingResult)
                    }
                    if (profile.mLastRealResult.isNotEmpty()) {
                        subtitle.append(" · ").append(profile.mLastRealResult)
                    }
                    if (profile.isUserPWAuth()) {
                        subtitle.append(" · ").append(profile.mUsername)
                    }
                    (view.findViewById<TextView>(R.id.minab_config_subtitle)).text =
                        subtitle.toString()
                    val flagView = view.findViewById<ImageView>(R.id.minab_config_flag)
                    val flagRes = FlagHelper.flagResForCategory(profile.getCategory())
                    if (flagRes != 0) {
                        flagView.setImageResource(flagRes)
                        flagView.visibility = View.VISIBLE
                    } else {
                        flagView.visibility = View.GONE
                    }
                    val checkBox = view.findViewById<CheckBox>(R.id.minab_config_checkbox)
                    checkBox.setOnCheckedChangeListener(null)
                    checkBox.isChecked = selectedUuids.contains(profile.getUUIDString())
                    checkBox.setOnCheckedChangeListener { _, _ -> toggleSelection(profile) }
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
        updateSelectionBar()
    }

    // --- Minab v0.5: selection (delete / share) ---

    private fun selectedProfiles(): List<VpnProfile> {
        val all = ProfileManager.getInstance(requireContext()).getProfiles().toList()
        return all.filter { selectedUuids.contains(it.getUUIDString()) }
    }

    private fun toggleSelection(profile: VpnProfile) {
        val uuid = profile.getUUIDString()
        if (!selectedUuids.remove(uuid)) selectedUuids.add(uuid)
        mArrayadapter?.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun clearSelection() {
        if (selectedUuids.isEmpty()) return
        selectedUuids.clear()
        mArrayadapter?.notifyDataSetChanged()
        updateSelectionBar()
    }

    /** Show the Delete/Share row while something is ticked, and
        keep the Share button's label truthful about its scope. */
    private fun updateSelectionBar() {
        if (!isAdded) return
        val count = selectedUuids.size
        selectionRow?.visibility = if (count > 0) View.VISIBLE else View.GONE
        val shareSelected = view?.findViewById<Button>(R.id.minab_configs_share_selected)
        if (count > 0) shareSelected?.text = getString(R.string.minab_share_selected, count)
        shareButton?.text = when {
            count > 0 -> getString(R.string.minab_share_selected, count)
            selectedCategory != null -> getString(R.string.minab_share_group)
            else -> getString(R.string.minab_share_all)
        }
    }

    private fun showDeleteSelectedDialog() {
        val count = selectedUuids.size
        if (count == 0) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_delete_selected)
            .setMessage(getString(R.string.minab_delete_selected_confirm, count))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pm = ProfileManager.getInstance(requireContext())
                val context = requireContext()
                for (profile in selectedProfiles()) {
                    pm.removeProfile(context, profile)
                }
                val deleted = count
                clearSelection()
                Toast.makeText(
                    context,
                    getString(R.string.minab_configs_deleted, deleted),
                    Toast.LENGTH_SHORT
                ).show()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Minab v0.5: share configs as one zip ---

    /** Share button scope: the ticked configs, else the current
        group, else every config. */
    private fun shareCurrentScope() {
        val context = requireContext()
        val profiles = when {
            selectedUuids.isNotEmpty() -> selectedProfiles()
            selectedCategory != null -> ProfileManager.getInstance(context).getProfiles()
                .toList().filter { it.getCategory() == selectedCategory }
            else -> ProfileManager.getInstance(context).getProfiles().toList()
        }
        if (profiles.isEmpty()) {
            Toast.makeText(context, R.string.minab_backup_none, Toast.LENGTH_SHORT).show()
            return
        }
        shareProfiles(profiles)
    }

    /** Write the profiles as .ovpn files into one zip in the cache
        dir, then hand it to the Android share sheet. The zip goes
        through the app FileProvider (see FileProvider.java: .zip
        files in the cache dir are served), so no storage permission
        is needed. */
    private fun shareProfiles(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) return
        val context = requireContext()
        Toast.makeText(context, R.string.minab_share_creating, Toast.LENGTH_SHORT).show()
        Thread {
            var count = 0
            var file: File? = null
            try {
                file = File(context.cacheDir, "minab-share-" + System.currentTimeMillis() + ".zip")
                FileOutputStream(file).use { out ->
                    count = writeProfilesZip(context, profiles, out)
                }
                if (count == 0) file = null
            } catch (ignored: Exception) {
                file = null
            }
            val outFile = file
            val doneCount = count
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (outFile == null || doneCount == 0) {
                    Toast.makeText(
                        requireContext(),
                        R.string.minab_share_failed,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val uri = Uri.parse(
                        "content://" + requireContext().packageName +
                            ".FileProvider/" + outFile.name
                    )
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "application/zip"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(
                        Intent.createChooser(intent, getString(R.string.minab_share_zip))
                    )
                    clearSelection()
                }
            }
        }.start()
    }

    /** Write each profile's .ovpn text as one zip entry. Returns
        the number of entries written. Shared by backup and share. */
    private fun writeProfilesZip(
        context: Context,
        profiles: List<VpnProfile>,
        out: OutputStream
    ): Int {
        var count = 0
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
        return count
    }

    private fun rebuildChips() {
        val container = chipsContainer ?: return
        container.removeAllViews()
        val context = requireContext()
        val categories = mutableListOf<String?>(null)
        categories.addAll(CategoryStore.getCategories(context))
        for (category in categories) {
            // v0.5: the first tab reads just "all", like Home.
            val label = category ?: getString(R.string.minab_all_tab)
            val selected = category == selectedCategory
            val chip = TextView(context)
            chip.text = label
            chip.textSize = 11f
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
            chip.setPadding(dp(10), dp(5), dp(10), dp(5))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(6)
            chip.layoutParams = params
            chip.setOnClickListener {
                selectedCategory = category
                refreshList()
            }
            container.addView(chip)
        }
        val add = TextView(context)
        add.text = "+ " + getString(R.string.minab_new_group)
        add.textSize = 11f
        add.setTextColor(resources.getColor(R.color.minab_teal, context.theme))
        add.setBackgroundResource(R.drawable.minab_chip_bg)
        add.setPadding(dp(10), dp(5), dp(10), dp(5))
        add.setOnClickListener { showNewCategoryDialog { refreshList() } }
        container.addView(add)
        // Trailing peek spacer (see Home): the fade shows the bar scrolls.
        val peek = View(context)
        peek.layoutParams = LinearLayout.LayoutParams(dp(20), dp(1))
        container.addView(peek)
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val profile = mArrayadapter?.getItem(position) ?: return
        if (selectedUuids.isNotEmpty()) {
            // Selection mode: tapping toggles instead of opening the dialog.
            toggleSelection(profile)
        } else {
            showConfigActionsDialog(profile)
        }
    }

    private fun showConfigActionsDialog(profile: VpnProfile) {
        val actions = arrayOf(
            getString(R.string.minab_rename_config),
            getString(R.string.minab_change_credentials),
            getString(R.string.minab_assign_category),
            getString(R.string.minab_ping_one),
            getString(R.string.minab_share_config),
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
                    4 -> shareProfiles(listOf(profile))
                    5 -> showDeleteDialog(profile)
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

    // --- Minab v0.6: the two server tests ---

    /** Configs currently shown (same filter as the list): the whole
        list, or one group when a group tab is selected. */
    private fun visibleProfiles(): List<VpnProfile> {
        val all = ProfileManager.getInstance(requireContext()).getProfiles().toList()
        val wanted = selectedCategory
        return if (wanted == null) all else all.filter { it.getCategory() == wanted }
    }

    /** Fast parallel ping over the visible configs. Ping tests are
        independent: they do not touch the tunnel, so they are not
        blocked by a real test running on Home. Within this tab the
        two tests share one progress row, so starting one cancels
        the other (same as Home). */
    private fun startPingTest(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_in_category, Toast.LENGTH_SHORT).show()
            return
        }
        cancelRealTest()
        cancelPingTest()
        if (VpnStatus.isVPNActive()) {
            Toast.makeText(requireContext(), R.string.minab_ping_while_vpn, Toast.LENGTH_LONG).show()
        }
        val tester = PingTester()
        pingTester = tester
        pingDoneCount = 0
        pingTotalCount = profiles.size
        updateTestPingStatus()
        tester.testAll(profiles, object : PingTester.PingListener {
            override fun onResult(result: PingTester.Result) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    result.profile.mLastPingMs =
                        if (result.status == PingTester.Status.OK) result.ms else -1
                    result.profile.mLastPingTime = result.timestampMs
                    result.profile.mLastPingResult = result.displayText()
                    ProfileManager.saveProfile(requireContext(), result.profile)
                    pingDoneCount++
                    updateTestPingStatus()
                    mArrayadapter?.notifyDataSetChanged()
                }
            }

            override fun onFinished(cancelled: Boolean) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    pingTester = null
                    if (cancelled) {
                        Toast.makeText(requireContext(), R.string.minab_ping_cancelled, Toast.LENGTH_SHORT).show()
                    } else {
                        refreshList()
                        Toast.makeText(requireContext(), R.string.minab_ping_done, Toast.LENGTH_SHORT).show()
                    }
                    testProgressRow?.visibility = View.GONE
                }
            }
        })
    }

    private fun updateTestPingStatus() {
        val row = testProgressRow ?: return
        if (pingTester == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        testCancelButton?.visibility = View.VISIBLE
        testProgressBar?.progress =
            if (pingTotalCount > 0) pingDoneCount * 100 / pingTotalCount else 0
        testStatusView?.text = getString(R.string.minab_ping_running, pingTotalCount, pingDoneCount)
    }

    private fun cancelPingTest() {
        pingTester?.cancel()
        pingTester = null
        if (!realTestActive) testProgressRow?.visibility = View.GONE
    }

    /** Real test: the same two-stage run as Home (ping first when
        stale, then connect each server in ranked order and fetch
        Telegram Web through it, stopping after 3 passes) — but
        with a 20 s wait per server instead of Home's 12 s. The
        runner is shared, so a real test running on Home blocks
        starting one here (and vice versa). */
    private fun startRealTest(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_in_category, Toast.LENGTH_SHORT).show()
            return
        }
        cancelPingTest()
        cancelRealTest()
        realTestRunId++
        val runId = realTestRunId
        val tester = RealTester(
            requireContext().applicationContext,
            CONFIGS_REAL_CONNECT_TIMEOUT_MS,
            RealTester.REAL_TEST_TARGET,
            object : RealTester.Listener {
                override fun onPingProgress(done: Int, total: Int) {
                    if (!isAdded || runId != realTestRunId) return
                    pingDoneCount = done
                    pingTotalCount = total
                    testProgressRow?.visibility = View.VISIBLE
                    testCancelButton?.visibility = View.VISIBLE
                    testProgressBar?.progress =
                        if (total > 0) done * 100 / total else 0
                    testStatusView?.text =
                        getString(R.string.minab_ping_running, total, done)
                }

                override fun onRealProgress(
                    done: Int, total: Int, currentName: String?, working: Int
                ) {
                    if (!isAdded || runId != realTestRunId) return
                    testProgressRow?.visibility = View.VISIBLE
                    testCancelButton?.visibility = View.VISIBLE
                    testProgressBar?.progress =
                        if (total > 0) done * 100 / total else 0
                    testStatusView?.text = getString(
                        R.string.minab_real_running,
                        done, total, currentName ?: "", working
                    )
                }

                override fun onProfileResult(profile: VpnProfile) {
                    if (!isAdded || runId != realTestRunId) return
                    refreshList()
                }

                override fun onFinished(cancelled: Boolean, working: Int) {
                    if (!isAdded || runId != realTestRunId) return
                    realTester = null
                    realTestActive = false
                    testProgressRow?.visibility = View.GONE
                    if (!cancelled) {
                        refreshList()
                        Toast.makeText(requireContext(), R.string.minab_real_done, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onConnectRequested(profile: VpnProfile) {
                    if (!isAdded || runId != realTestRunId) return
                    startVPN(profile)
                }
            }
        )
        if (!tester.start(profiles)) {
            Toast.makeText(requireContext(), R.string.minab_real_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        realTester = tester
        realTestActive = true
    }

    /** Stop our real test: the runner drops the VPN under test (if
        any), stops scheduling further servers, leaves already-
        recorded results. No toast here — the caller toasts when
        the cancel is the user's own tap. Safe to call when
        nothing runs. */
    private fun cancelRealTest() {
        realTestRunId++
        realTester?.cancel()
        realTester = null
        realTestActive = false
        testProgressRow?.visibility = View.GONE
    }

    /** Connect a profile — the same LaunchVPN path as Home's
        startVPN, so the real test can drive it from this tab. */
    private fun startVPN(profile: VpnProfile) {
        ProfileManager.saveProfile(requireContext(), profile)
        val intent = Intent(requireContext(), LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "configs real test")
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
        intent.action = Intent.ACTION_MAIN
        startActivity(intent)
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
                    count = writeProfilesZip(context, profiles, out)
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

    companion object {
        // Minab v0.6: how long the Real test waits for one server
        // to report CONNECTED from the Configs tab (Home waits
        // 12 s). Configs is the deliberate, patient choice: a
        // server that needs longer to connect still gets its
        // chance here instead of being marked Blocked.
        private const val CONFIGS_REAL_CONNECT_TIMEOUT_MS = 20_000L
    }
}
