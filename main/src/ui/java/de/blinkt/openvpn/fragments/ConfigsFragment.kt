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
import android.text.Editable
import android.text.TextWatcher
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
import de.blinkt.openvpn.activities.VPNPreferences
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.FlagHelper
import de.blinkt.openvpn.minab.MinabBackup
import de.blinkt.openvpn.minab.MinabCategoryReorder
import de.blinkt.openvpn.minab.MinabFavorites
import de.blinkt.openvpn.minab.PingTester
import de.blinkt.openvpn.minab.RealTester
import de.blinkt.openvpn.minab.SharedCredentials
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.TreeSet

class ConfigsFragment : ListFragment() {
    private var selectedCategory: String? = null
    private var mArrayadapter: ArrayAdapter<VpnProfile>? = null
    private var chipsContainer: LinearLayout? = null
    private lateinit var backupLauncher: ActivityResultLauncher<String>
    // --- Minab Beta v0.9.1: search (fix #9) + chip cache (fix #4).
    // The search box filters by name or country, debounced 250 ms
    // like Home's; the chip bar is only rebuilt when its signature
    // (selected tab + group order) changed. ---
    private var searchQuery: String = ""
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { refreshList() }
    private var chipsSignature: String? = null
    /** UUIDs of the configs ticked for Delete / Share. */
    private val selectedUuids = mutableSetOf<String>()
    private var selectionRow: View? = null
    private var shareButton: Button? = null
    // --- Minab v0.6: the two server tests (Ping test / Real test) ---
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
        // Minab v0.8: Copy / Move the ticked configs to a group.
        v.findViewById<Button>(R.id.minab_configs_copy_selected)?.setOnClickListener {
            showCopyMoveDialog(true)
        }
        v.findViewById<Button>(R.id.minab_configs_move_selected)?.setOnClickListener {
            showCopyMoveDialog(false)
        }
        v.findViewById<Button>(R.id.minab_configs_share_selected)?.setOnClickListener {
            shareProfiles(selectedProfiles())
        }
        // Minab v0.6: the two server tests. Ping test is the fast
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
        // Minab Beta v0.9.1 — search the configs by name or country
        // (fix #9), like the search box on Home. Debounced, so
        // typing does not rebuild the list on every keystroke.
        v.findViewById<EditText>(R.id.minab_configs_search)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                searchDebounceHandler.removeCallbacks(searchDebounceRunnable)
                searchDebounceHandler.postDelayed(searchDebounceRunnable, 250)
            }
        })
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
        // Minab Beta v0.9.1 — the favorites pseudo-tab (fix #7):
        // a starred config shows in its country group AND here.
        val favorites = MinabFavorites.getAll(context)
        val filtered = when {
            selectedCategory == null -> all
            selectedCategory == CategoryStore.FAVORITES_TAB ->
                all.filter { favorites.contains(it.getUUIDString()) }
            else -> all.filter { it.getCategory() == selectedCategory }
        }
        // Minab Beta v0.9.1 — search by name or country (fix #9),
        // case-insensitive; an empty search shows everything.
        val query = searchQuery.lowercase()
        val searched = if (query.isEmpty()) filtered else filtered.filter { profile ->
            val name = profile.mName?.lowercase() ?: ""
            val category = profile.getCategory()?.lowercase() ?: ""
            name.contains(query) || category.contains(query)
        }
        val sorted = TreeSet<VpnProfile>(Comparator { a, b -> (a.mName ?: "").compareTo(b.mName ?: "") })
        sorted.addAll(searched)
        if (mArrayadapter == null) {
            mArrayadapter = object : ArrayAdapter<VpnProfile>(
                context, R.layout.minab_config_row, ArrayList(sorted)
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView
                        ?: LayoutInflater.from(context).inflate(R.layout.minab_config_row, parent, false)
                    val profile = getItem(position)!!
                    (view.findViewById<TextView>(R.id.minab_config_name)).text = profile.mName
                    // Minab Beta v0.9.1 — the subtitle is just the
                    // group (fix #6): the ping / real results live
                    // in the badges now, and the username is never
                    // shown under a config.
                    (view.findViewById<TextView>(R.id.minab_config_subtitle)).text =
                        "[" + profile.getCategory() + "]"
                    // Minab Beta v0.9.1 — every server gets a flag
                    // (fix #6): flagResForProfile falls back to the
                    // globe, so the column is never empty.
                    val flagView = view.findViewById<ImageView>(R.id.minab_config_flag)
                    flagView.setImageResource(FlagHelper.flagResForProfile(profile))
                    flagView.visibility = View.VISIBLE
                    // Minab Beta v0.9.1 — ping / real badges in neat,
                    // small, regular columns like Home (fix #6).
                    setConfigBadge(
                        view.findViewById(R.id.minab_config_ping_badge),
                        profile.mLastPingMs, profile.mLastPingResult)
                    setConfigBadge(
                        view.findViewById(R.id.minab_config_real_badge),
                        profile.mLastRealMs, profile.mLastRealResult)
                    // Minab Beta v0.9.1 — the favorite star (fix #6):
                    // filled for a favorite, outline otherwise.
                    val star = view.findViewById<ImageView>(R.id.minab_config_star)
                    val isFavorite = MinabFavorites.isFavorite(context, profile.getUUIDString())
                    star.setImageResource(
                        if (isFavorite) R.drawable.minab_star_on else R.drawable.minab_star_off
                    )
                    star.setOnClickListener {
                        MinabFavorites.toggle(context, profile.getUUIDString())
                        refreshList()
                    }
                    // Minab Beta v0.9.1 — the pencil opens the server
                    // editor directly (fix #6).
                    view.findViewById<ImageView>(R.id.minab_config_edit)
                        .setOnClickListener { editVPN(profile) }
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
        // Minab Beta v0.9 — the list now lives INSIDE this page
        // (a NestedScrollView) instead of in its own little window,
        // so the ListView must grow to its full content height.
        // Posted on the fragment view (v0.5.1 hotfix pattern), so
        // it never runs before the view exists.
        view?.post { expandListView() }
    }

    /** One test badge (ping or real): the value as a coloured pill
        (green < 100 ms, amber 100–299 ms, grey otherwise), hidden
        when the profile has no result for it. Mirrors Home's row
        badges; a 0 ms value is not a measurement (see the
        VpnProfile migration guard). */
    private fun setConfigBadge(badge: TextView, ms: Long, result: String) {
        if (ms > 0) {
            badge.visibility = View.VISIBLE
            badge.text = result.ifEmpty { "$ms ms" }
            badge.setBackgroundResource(
                when {
                    ms < 100 -> R.drawable.minab_ping_badge_fast
                    ms < 300 -> R.drawable.minab_ping_badge_slow
                    else -> R.drawable.minab_ping_badge_fail
                }
            )
        } else if (result.isNotEmpty() && ms != 0L) {
            badge.visibility = View.VISIBLE
            badge.text = result
            badge.setBackgroundResource(R.drawable.minab_ping_badge_fail)
        } else {
            badge.visibility = View.GONE
        }
    }

    /** Open the server editor for this config (fix #6: the pencil
        on the row). The list refreshes on resume after editing. */
    private fun editVPN(profile: VpnProfile) {
        val intent = Intent(requireContext(), VPNPreferences::class.java)
            .putExtra(
                requireContext().packageName + ".profileUUID",
                profile.getUUID().toString()
            )
        startActivity(intent)
    }

    /** Minab Beta v0.9 — grow the ListView to its full content
        height, so the whole Configs page scrolls as one (like Home).
        Same proven pattern as Home's expandListView: measure each
        row off the view tree, add the dividers, set the total
        height. Null-safe on the fragment view; one bad row falls
        back to a standard height instead of crashing. */
    private fun expandListView() {
        if (!isAdded || view == null) return
        val list = try { listView } catch (e: IllegalStateException) { return }
        val adapter = mArrayadapter ?: return
        if (adapter.count == 0) {
            list.layoutParams.height = 0
            list.requestLayout()
            return
        }
        val width = if (list.width > 0) list.width
            else resources.displayMetrics.widthPixels - dp(32)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var totalHeight = 0
        // Minab Beta v0.9.1 — recycle ONE convertView through the
        // rows (fix #4): measuring used to inflate a fresh row view
        // for every row on every refresh.
        var convertView: View? = null
        for (i in 0 until adapter.count) {
            try {
                val item = adapter.getView(i, convertView, list)
                convertView = item
                item.measure(widthSpec, heightSpec)
                totalHeight += item.measuredHeight
            } catch (e: Exception) {
                // One bad row must not crash the app; assume the
                // standard two-line row height for it.
                totalHeight += dp(64)
            }
        }
        totalHeight += list.dividerHeight * (adapter.count - 1)
        val params = list.layoutParams
        params.height = totalHeight
        list.layoutParams = params
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
        // Minab Beta v0.9 — the short label ("Share (3)") fits on
        // one line; the old label ("Share selected (3)") wrapped to
        // two lines and the second line was clipped (v0.8 screenshot).
        if (count > 0) shareSelected?.text = getString(R.string.minab_share_selected_short, count)
        shareButton?.text = when {
            count > 0 -> getString(R.string.minab_share_selected_short, count)
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

    // --- Minab v0.8: copy / move the ticked configs to a group ---

    /** Pick a target group, then copy or move the ticked configs
        there. The picker is the same list as "Assign category"
        (all groups + "New group"). */
    private fun showCopyMoveDialog(isCopy: Boolean) {
        val count = selectedUuids.size
        if (count == 0) return
        val categories = CategoryStore.getOrderedCategories(requireContext())
        val labels = (categories + getString(R.string.minab_new_category)).toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_copy_move_title)
            .setItems(labels) { _, which ->
                if (which < categories.size) {
                    copyMoveSelected(categories[which], isCopy)
                } else {
                    showNewCategoryDialog { name -> copyMoveSelected(name, isCopy) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Copy duplicates each profile (new UUID, same name) into the
        target group and keeps the original; Move just changes the
        group of the selected profiles. New profiles are added the
        same way bulk import does (addProfile + saveProfile +
        saveProfileList). */
    private fun copyMoveSelected(target: String, isCopy: Boolean) {
        val context = requireContext()
        val pm = ProfileManager.getInstance(context)
        CategoryStore.addCategory(context, target)
        var done = 0
        for (profile in selectedProfiles()) {
            if (isCopy) {
                val copy = profile.copy(profile.mName) ?: continue
                copy.setCategory(target)
                pm.addProfile(copy)
                ProfileManager.saveProfile(context, copy)
                pm.saveProfileList(context)
                done++
            } else {
                profile.setCategory(target)
                ProfileManager.saveProfile(context, profile)
                done++
            }
        }
        val moved = done
        clearSelection()
        Toast.makeText(
            context,
            getString(
                if (isCopy) R.string.minab_copied else R.string.minab_moved,
                moved, target
            ),
            Toast.LENGTH_SHORT
        ).show()
        refreshList()
    }

    // --- Minab v0.5: share configs as one zip ---

    /** Share button scope: the ticked configs, else the current
        group, else every config. */
    private fun shareCurrentScope() {
        val context = requireContext()
        val profiles = when {
            selectedUuids.isNotEmpty() -> selectedProfiles()
            // Minab Beta v0.9.1 — visibleProfiles() handles the
            // favorites tab too (fix #7); filtering by category
            // here would match nothing on that tab.
            else -> visibleProfiles()
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

    /** Plain .ovpn-only zip, exactly as before (the Share flow uses
        it). Now delegated to MinabBackup so there is one writer. */
    private fun writeProfilesZip(
        context: Context,
        profiles: List<VpnProfile>,
        out: OutputStream
    ): Int = MinabBackup.writeProfilesZip(context, profiles, out)

    /** The group tabs: favorites + All + one tab per group +
        Manage + a "+" tab, and a pencil tab to reorder the groups.
        Minab Beta v0.9.1 (fixes #7, #8): the favorites tab is
        FIRST (gold star + "favorites"), "manage categories" is
        second-to-last (right before "+") — Configs had no Manage
        tab before — and the pencil opens the reorder dialog (the
        custom order is saved in CategoryStore). The bar is only
        rebuilt when its signature (selected tab + group order)
        changed (fix #4). */
    private fun rebuildChips() {
        val container = chipsContainer ?: return
        val context = requireContext()
        val ordered = CategoryStore.getOrderedCategories(context)
        val signature = (selectedCategory ?: "all") + "|" + ordered.joinToString(",")
        if (signature == chipsSignature && container.childCount > 0) return
        chipsSignature = signature
        container.removeAllViews()
        // Favorites first (fix #7): gold star + "favorites".
        addFavoritesTab(container)
        // "all" + country groups in the saved order +
        // "Uncategorized" last (the catch-all, not a country).
        val tabs = mutableListOf<String?>(null)
        tabs.addAll(ordered.filter { it != CategoryStore.DEFAULT_CATEGORY })
        tabs.add(CategoryStore.DEFAULT_CATEGORY)
        for (category in tabs) {
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
        // Manage second-to-last (fix #8), right before "+".
        val manage = TextView(context)
        manage.text = getString(R.string.minab_manage_categories)
        manage.textSize = 11f
        manage.setTextColor(resources.getColor(R.color.minab_teal, context.theme))
        manage.setBackgroundResource(R.drawable.minab_chip_bg)
        manage.setPadding(dp(10), dp(5), dp(10), dp(5))
        val manageParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        manageParams.marginEnd = dp(6)
        manage.layoutParams = manageParams
        manage.setOnClickListener { showManageCategoriesDialog() }
        container.addView(manage)
        val add = TextView(context)
        add.text = "+ " + getString(R.string.minab_new_group)
        add.textSize = 11f
        add.setTextColor(resources.getColor(R.color.minab_teal, context.theme))
        add.setBackgroundResource(R.drawable.minab_chip_bg)
        add.setPadding(dp(10), dp(5), dp(10), dp(5))
        val addParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addParams.marginEnd = dp(6)
        add.layoutParams = addParams
        add.setOnClickListener { showNewCategoryDialog { refreshList() } }
        container.addView(add)
        // Reorder pencil (fix #8): opens the group-order dialog.
        val reorder = ImageView(context)
        reorder.setImageResource(R.drawable.ic_edit)
        reorder.setColorFilter(resources.getColor(R.color.minab_teal, context.theme))
        reorder.setBackgroundResource(R.drawable.minab_chip_bg)
        reorder.setPadding(dp(8), dp(5), dp(8), dp(5))
        reorder.contentDescription = getString(R.string.minab_reorder_groups)
        val reorderParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        reorderParams.marginEnd = dp(6)
        reorder.layoutParams = reorderParams
        reorder.setOnClickListener {
            MinabCategoryReorder.show(context) {
                chipsSignature = null
                rebuildChips()
                refreshList()
            }
        }
        container.addView(reorder)
        // Trailing peek spacer (see Home): the fade shows the bar scrolls.
        val peek = View(context)
        peek.layoutParams = LinearLayout.LayoutParams(dp(20), dp(1))
        container.addView(peek)
    }

    /** The favorites tab (fix #7): FIRST, before "all". A gold star
        (minab_star_on is amber) + "favorites". Tapping it shows the
        starred servers — they stay in their country groups too. */
    private fun addFavoritesTab(container: LinearLayout) {
        val context = requireContext()
        val selected = selectedCategory == CategoryStore.FAVORITES_TAB
        val tab = LinearLayout(context)
        tab.orientation = LinearLayout.HORIZONTAL
        tab.gravity = android.view.Gravity.CENTER_VERTICAL
        tab.setBackgroundResource(
            if (selected) R.drawable.minab_chip_bg_selected
            else R.drawable.minab_chip_bg
        )
        tab.setPadding(dp(10), dp(5), dp(10), dp(5))
        val tabParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tabParams.marginEnd = dp(6)
        tab.layoutParams = tabParams
        val star = ImageView(context)
        star.setImageResource(R.drawable.minab_star_on)
        val starParams = LinearLayout.LayoutParams(dp(14), dp(14))
        starParams.marginEnd = dp(4)
        star.layoutParams = starParams
        tab.addView(star)
        val label = TextView(context)
        label.text = getString(R.string.minab_favorites_tab)
        label.textSize = 11f
        label.setTextColor(
            resources.getColor(
                if (selected) R.color.minab_teal_dark else R.color.minab_chip_text,
                context.theme
            )
        )
        label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        tab.addView(label)
        tab.setOnClickListener {
            selectedCategory = CategoryStore.FAVORITES_TAB
            refreshList()
            if (MinabFavorites.getAll(context).isEmpty()) {
                Toast.makeText(context, R.string.minab_no_favorites_yet, Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(tab)
    }

    /** Rename / delete a group (the Manage tab, fix #8). Mirrors
        Home's dialog; the list refreshes after every change. */
    private fun showManageCategoriesDialog() {
        val categories = CategoryStore.getOrderedCategories(requireContext())
            .filter { it != CategoryStore.DEFAULT_CATEGORY }
        if (categories.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_new_category, Toast.LENGTH_SHORT).show()
            showNewCategoryDialog { refreshList() }
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_manage_categories)
            .setItems(categories.toTypedArray()) { _, which ->
                val oldName = categories[which]
                AlertDialog.Builder(requireContext())
                    .setTitle(oldName)
                    .setItems(
                        arrayOf(
                            getString(R.string.minab_rename_category),
                            getString(R.string.minab_delete_category)
                        )
                    ) { _, action ->
                        if (action == 0) {
                            val entry = EditText(requireContext())
                            entry.setText(oldName)
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.minab_rename_category)
                                .setView(entry)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    CategoryStore.renameCategory(
                                        requireContext(), oldName,
                                        entry.text.toString()
                                    )
                                    if (selectedCategory == oldName) {
                                        selectedCategory = CategoryStore.normalise(entry.text.toString())
                                    }
                                    refreshList()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        } else {
                            CategoryStore.deleteCategory(requireContext(), oldName)
                            if (selectedCategory == oldName) selectedCategory = null
                            refreshList()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNeutralButton(R.string.minab_new_category) { _, _ ->
                showNewCategoryDialog { refreshList() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        // Minab Beta v0.9.1 — the favorites tab is not a real group
        // (fix #7): "Set for this group" there means all configs.
        val effectiveGroup =
            if (group == CategoryStore.FAVORITES_TAB) null else group
        val title = if (effectiveGroup == null) getString(R.string.minab_credentials_for_all)
        else getString(R.string.minab_credentials_for_group) + " — " + effectiveGroup
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(R.string.minab_shared_credentials_hint)
            .setPositiveButton(R.string.minab_apply_credentials) { _, _ ->
                showCredentialsInputDialog { username, password ->
                    SharedCredentials.save(requireContext(), username, password)
                    val updated = if (effectiveGroup == null) {
                        SharedCredentials.applyToAllProfiles(requireContext(), username, password)
                    } else {
                        SharedCredentials.applyToCategory(requireContext(), effectiveGroup, username, password)
                    }
                    Toast.makeText(
                        requireContext(),
                        if (effectiveGroup == null) getString(R.string.minab_credentials_applied, updated)
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
        val categories = CategoryStore.getOrderedCategories(requireContext())
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
        list, one group when a group tab is selected, or the starred
        servers on the favorites tab (fix #7). */
    private fun visibleProfiles(): List<VpnProfile> {
        val all = ProfileManager.getInstance(requireContext()).getProfiles().toList()
        val wanted = selectedCategory
        return when {
            wanted == null -> all
            wanted == CategoryStore.FAVORITES_TAB -> {
                val favorites = MinabFavorites.getAll(requireContext())
                all.filter { favorites.contains(it.getUUIDString()) }
            }
            else -> all.filter { it.getCategory() == wanted }
        }
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
                    // Minab Beta v0.9 — this is now a FULL backup:
                    // the .ovpn files plus a minab-backup.json with
                    // groups, credentials, favorites and settings,
                    // so restoring puts everything back exactly.
                    count = MinabBackup.writeFullBackup(context, profiles, out)
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
