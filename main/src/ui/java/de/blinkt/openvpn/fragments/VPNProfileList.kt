/*
 * Copyright (c) 2012-2019 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.fragments

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.text.Html
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.RequiresApi
import androidx.fragment.app.ListFragment
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.activities.BaseActivity
import de.blinkt.openvpn.activities.BulkImportActivity
import de.blinkt.openvpn.activities.ConfigConverter
import de.blinkt.openvpn.activities.DisconnectVPN
import de.blinkt.openvpn.activities.FileSelect
import de.blinkt.openvpn.activities.VPNPreferences
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.PasswordDialogFragment.Companion.newInstance
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.VpnStatus.StateListener
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.FlagHelper
import de.blinkt.openvpn.minab.MinabUpdateChecker
import de.blinkt.openvpn.minab.PingTester
import de.blinkt.openvpn.minab.RealTester
import de.blinkt.openvpn.fragments.ImportRemoteConfig.Companion.newInstance
import de.blinkt.openvpn.fragments.Utils.alwaysUseOldFileChooser
import de.blinkt.openvpn.fragments.Utils.getWarningText
import java.util.LinkedList
import java.util.TreeSet
import kotlin.math.min

class VPNProfileList : ListFragment(), View.OnClickListener, StateListener {
    protected var mEditProfile: VpnProfile? = null
    private var mLastStatusMessage: String? = null
    private var mArrayadapter: ArrayAdapter<VpnProfile>? = null
    private var mLastIntent: Intent? = null
    private var defaultVPN: VpnProfile? = null
    private lateinit var mPermissionView: View
    private lateinit var mPermReceiver: ActivityResultLauncher<String>

    // --- Minab Children state ---
    /** Null = show all categories; otherwise only this category is listed. */
    private var selectedCategory: String? = null
    /** Minab v0.7: ONE sort mode (NONE / PING / REAL) instead of
        two booleans, persisted in "MinabPrefs" so it survives the
        fragment being recreated (e.g. switching tabs) and process
        death. The last completed test wins; every repopulate
        respects the current mode. Why the v0.6 sort "did not
        stick": the flags were in-memory only (lost on fragment
        recreation), tapping a group tab or the filter dialog reset
        them, and a test finishing while Home was not visible never
        applied its sort at all. */
    private var sortMode: Int = SORT_NONE
    @Volatile
    private var pingTester: PingTester? = null
    private var pingDoneCount: Int = 0
    private var pingTotalCount: Int = 0
    // --- Minab v0.6: Real test (connect + fetch web.telegram.org) ---
    // The runner itself lives in minab.RealTester (shared with
    // Configs); this fragment only owns the UI state and the
    // connect action (startVPN). See startRealTest below.
    /** True from the tap on "Real test" until the run ends/cancels. */
    @Volatile
    private var realTestActive: Boolean = false
    /** The runner of our own run; null when no run is owned. */
    private var realTester: RealTester? = null
    /** Bumped on every start/cancel so listener callbacks from a
        superseded run are ignored. */
    private var realTestRunId = 0
    @Volatile
    private var realDoneCount: Int = 0
    @Volatile
    private var realTotalCount: Int = 0
    @Volatile
    private var realWorkingCount: Int = 0
    @Volatile
    private var realTestCurrentName: String? = null
    private val realTestPauseHandler = Handler(Looper.getMainLooper())
    private val realTestPauseStop = Runnable {
        if (realTestActive) {
            cancelRealTest()
            try {
                Toast.makeText(requireContext(), R.string.minab_real_cancelled, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Fragment gone meanwhile: nothing to tell.
            }
        }
    }
    private var pingStatusView: TextView? = null
    private var pingProgressRow: View? = null
    private var pingProgressBar: ProgressBar? = null
    private var pingCancelButton: Button? = null
    private var categoryChipsContainer: LinearLayout? = null
    // --- Minab v0.3: Namaki-style PowerOrb (home connect button) ---
    private var orbButton: android.widget.ImageButton? = null
    private var orbStatusView: TextView? = null
    // --- Minab v0.7: the connected server's ping pill next to the
    // status line (tap = re-test that server's ping now) ---
    private var connectedPingView: TextView? = null
    @Volatile
    private var connectedPingTesting: Boolean = false
    // --- Minab v0.4: update notice banner ---
    private var updateBanner: TextView? = null

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        requireActivity().runOnUiThread(Runnable {
            mLastStatusMessage = VpnStatus.getLastCleanLogMessage(getActivity())
            mLastIntent = intent
            mArrayadapter!!.notifyDataSetChanged()
            updateOrbState()
            showUserRequestDialogIfNeeded(level, intent)
        })
    }

    private fun showUserRequestDialogIfNeeded(level: ConnectionStatus?, intent: Intent?): Boolean {
        if (level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT) {
            if (intent != null && intent.getStringExtra(OpenVPNService.EXTRA_CHALLENGE_TXT) != null) {
                val pwInputFrag = newInstance(intent, false)

                pwInputFrag!!.show(getParentFragmentManager(), "dialog")
                return true
            }
        }
        return false
    }

    override fun setConnectedVPN(uuid: String?) {
    }

    private fun startOrStopVPN(profile: VpnProfile) {
        if (VpnStatus.isVPNActive() && profile.getUUIDString() == VpnStatus.getLastConnectedVPNProfile()) {
            if (mLastIntent != null) {
                startActivity(mLastIntent!!)
            } else {
                val disconnectVPN = Intent(getActivity(), DisconnectVPN::class.java)
                startActivity(disconnectVPN)
            }
        } else {
            startVPN(profile)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        // Minab v0.7: restore the persisted sort mode BEFORE the
        // first populate, so the list comes back sorted the way
        // the last test left it.
        try {
            sortMode = requireContext().getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE)
                .getInt(PREF_SORT_MODE, SORT_NONE)
        } catch (e: Exception) {
            sortMode = SORT_NONE
        }
        setListAdapter()

        registerPermissionReceiver()
    }

    private fun registerPermissionReceiver() {
        mPermReceiver = registerForActivityResult<String, Boolean>(
            RequestPermission(),
            ActivityResultCallback { result: Boolean? -> updateNotifBanner() })
    }

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    fun updateDynamicShortcuts() {
        val versionExtras = PersistableBundle()
        versionExtras.putInt("version", SHORTCUT_VERSION)

        val shortcutManager =
            getContext()!!.getSystemService<ShortcutManager>(ShortcutManager::class.java)
        if (shortcutManager.isRateLimitingActive()) return

        val shortcuts = shortcutManager.getDynamicShortcuts()
        var maxvpn = shortcutManager.getMaxShortcutCountPerActivity() - 1


        val disconnectShortcut = ShortcutInfo.Builder(getContext(), "disconnectVPN")
            .setShortLabel("Disconnect")
            .setLongLabel("Disconnect VPN")
            .setIntent(
                Intent(
                    getContext(),
                    DisconnectVPN::class.java
                ).setAction(OpenVPNService.DISCONNECT_VPN)
            )
            .setIcon(Icon.createWithResource(getContext(), R.drawable.ic_shortcut_cancel))
            .setExtras(versionExtras)
            .build()

        val newShortcuts = LinkedList<ShortcutInfo>()
        val updateShortcuts = LinkedList<ShortcutInfo>()

        val removeShortcuts = LinkedList<String>()
        val disableShortcuts = LinkedList<String>()

        var addDisconnect = true


        val sortedProfilesLRU = TreeSet<VpnProfile?>(VpnProfileLRUComparator())
        val profileManager = ProfileManager.getInstance(getContext())
        sortedProfilesLRU.addAll(profileManager.getProfiles())

        val LRUProfiles = LinkedList<VpnProfile>()
        maxvpn = min(maxvpn, sortedProfilesLRU.size)

        for (i in 0..<maxvpn) {
            LRUProfiles.add(sortedProfilesLRU.pollFirst()!!)
        }

        for (shortcut in shortcuts) {
            if (shortcut.getId() == "disconnectVPN") {
                addDisconnect = false
                if (shortcut.getExtras() == null
                    || shortcut.getExtras()!!.getInt("version") != SHORTCUT_VERSION
                ) updateShortcuts.add(disconnectShortcut)
            } else {
                val p = ProfileManager.get(getContext(), shortcut.getId())
                if (p == null || p.profileDeleted) {
                    if (shortcut.isEnabled()) {
                        disableShortcuts.add(shortcut.getId())
                        removeShortcuts.add(shortcut.getId())
                    }
                    if (!shortcut.isPinned()) removeShortcuts.add(shortcut.getId())
                } else {
                    if (LRUProfiles.contains(p)) LRUProfiles.remove(p)
                    else removeShortcuts.add(p.getUUIDString())

                    if ((p.getName() != shortcut.getShortLabel()) || shortcut.getExtras() == null || shortcut.getExtras()!!
                            .getInt("version") != SHORTCUT_VERSION
                    ) updateShortcuts.add(createShortcut(p))
                }
            }
        }
        if (addDisconnect) newShortcuts.add(disconnectShortcut)
        for (p in LRUProfiles) newShortcuts.add(createShortcut(p))

        if (updateShortcuts.size > 0) shortcutManager.updateShortcuts(updateShortcuts)
        if (removeShortcuts.size > 0) shortcutManager.removeDynamicShortcuts(removeShortcuts)
        if (newShortcuts.size > 0) shortcutManager.addDynamicShortcuts(newShortcuts)
        if (disableShortcuts.size > 0) shortcutManager.disableShortcuts(
            disableShortcuts,
            "VpnProfile does not exist anymore."
        )
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun createShortcut(profile: VpnProfile): ShortcutInfo {
        val shortcutIntent = Intent(Intent.ACTION_MAIN)
        shortcutIntent.setClass(requireContext(), LaunchVPN::class.java)
        shortcutIntent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        shortcutIntent.setAction(Intent.ACTION_MAIN)
        shortcutIntent.putExtra(OpenVPNService.EXTRA_START_REASON, "shortcut")
        shortcutIntent.putExtra("EXTRA_HIDELOG", true)

        val versionExtras = PersistableBundle()
        versionExtras.putInt("version", SHORTCUT_VERSION)

        return ShortcutInfo.Builder(getContext(), profile.getUUIDString())
            .setShortLabel(profile.getName())
            .setLongLabel(getString(R.string.qs_connect, profile.getName()))
            .setIcon(Icon.createWithResource(getContext(), R.drawable.ic_shortcut_vpn_key))
            .setIntent(shortcutIntent)
            .setExtras(versionExtras)
            .build()
    }

    override fun onResume() {
        super.onResume()
        // Minab v0.6: we are back (e.g. after our own LaunchVPN
        // round-trip) — do not stop a running real test for it.
        realTestPauseHandler.removeCallbacks(realTestPauseStop)
        setListAdapter()
        updateOrbState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            updateDynamicShortcuts()
        }
        VpnStatus.addStateListener(this)
        defaultVPN = ProfileManager.getAlwaysOnVPN(requireContext())
    }

    override fun onPause() {
        super.onPause()
        VpnStatus.removeStateListener(this)
        // Minab v0.6: leaving Home mid-run stops the real test —
        // but launching LaunchVPN pauses us briefly too, so wait
        // 8 s: a quick round-trip (our own connect/disconnect)
        // resumes in time and the run survives it.
        if (realTestActive) realTestPauseHandler.postDelayed(realTestPauseStop, 8000)
    }

    override fun onDestroy() {
        // Stop the ping pool when the fragment is really gone. Results
        // already saved on profiles are kept.
        cancelPingTest()
        // Minab v0.6: same for a running real test (drops the VPN
        // under test, if any).
        realTestPauseHandler.removeCallbacks(realTestPauseStop)
        cancelRealTest()
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.vpn_profile_list, container, false)

        val newvpntext = v.findViewById<View?>(R.id.add_new_vpn_hint) as TextView
        val importvpntext = v.findViewById<View?>(R.id.import_vpn_hint) as TextView

        newvpntext.setText(
            Html.fromHtml(
                getString(R.string.add_new_vpn_hint),
                MiniImageGetter(),
                null
            )
        )
        importvpntext.setText(
            Html.fromHtml(
                getString(R.string.vpn_import_hint),
                MiniImageGetter(),
                null
            )
        )

        val fab_add = v.findViewById<View?>(R.id.fab_add) as ImageButton?
        val fab_import = v.findViewById<View?>(R.id.fab_import) as ImageButton?
        if (fab_add != null) fab_add.setOnClickListener(this)

        if (fab_import != null) fab_import.setOnClickListener(this)

        // Minab Children (v0.2): visible buttons, category chips and
        // ping progress row. In v0.1 these entry points were text-only
        // items in the overflow menu, so users could not find them.
        val bulkImportButton = v.findViewById<View?>(R.id.minab_btn_bulk_import) as Button?
        bulkImportButton?.setOnClickListener {
            startActivity(Intent(requireContext(), BulkImportActivity::class.java))
        }
        val pingButton = v.findViewById<View?>(R.id.minab_btn_ping) as Button?
        pingButton?.setOnClickListener { startPingTest(visibleProfiles()) }
        // Minab v0.6: Real test — connect each server in turn and
        // fetch web.telegram.org through the tunnel.
        val realTestButton = v.findViewById<View?>(R.id.minab_btn_real_test) as Button?
        realTestButton?.setOnClickListener { startRealTest(visibleProfiles()) }
        categoryChipsContainer = v.findViewById<View?>(R.id.minab_category_chips) as LinearLayout?
        pingProgressRow = v.findViewById<View?>(R.id.minab_ping_progress_row)
        pingProgressBar = v.findViewById<View?>(R.id.minab_ping_progress) as ProgressBar?
        pingStatusView = v.findViewById<View?>(R.id.minab_ping_status) as TextView?
        pingCancelButton = v.findViewById<View?>(R.id.minab_ping_cancel_btn) as Button?
        pingCancelButton?.setOnClickListener {
            // Minab v0.6: the same Cancel button stops whichever
            // test is running (ping or real test).
            if (realTestActive) {
                cancelRealTest()
                Toast.makeText(requireContext(), R.string.minab_real_cancelled, Toast.LENGTH_SHORT).show()
            } else {
                cancelPingTest()
            }
        }
        // Minab v0.3: Namaki-style PowerOrb — the big circular
        // connect button at the top of the home screen. Tapping it
        // connects the selected / last-used profile, or disconnects
        // while a VPN is active (same flow as tapping a profile row).
        orbButton = v.findViewById<View?>(R.id.minab_power_orb) as android.widget.ImageButton?
        orbStatusView = v.findViewById<View?>(R.id.minab_home_status) as TextView?
        orbButton?.setOnClickListener { toggleOrbConnection() }
        // Minab v0.7: the connected server's ping pill — tap it to
        // re-test that server's ping now.
        connectedPingView = v.findViewById<View?>(R.id.minab_connected_ping) as TextView?
        connectedPingView?.setOnClickListener { retestConnectedPing() }
        updateOrbState()
        rebuildCategoryChips()
        registerForContextMenu(v.findViewById(android.R.id.list))

        // Minab v0.4: update notice banner (hidden unless a newer
        // release exists on GitHub) and the notification permission
        // flow: ask at first launch, minimal banner only if declined.
        updateBanner = v.findViewById<View?>(R.id.minab_update_banner) as TextView?
        checkForUpdates()

        // TV builds show the minimal UI that already have the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !((activity as BaseActivity).isAndroidTV))
            checkForNotificationPermission(v)

        return v
    }

    /** Minab v0.4 — ask for the notification permission at first
        launch. If the user declines, only the minimal one-line
        banner (see the layout) is shown; tapping it re-asks, or
        opens the app settings when the system will no longer show
        the dialog. */
    private fun checkForNotificationPermission(v: View) {
        mPermissionView = v.findViewById<View>(R.id.notification_permission)
        if (requireActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            mPermissionView.visibility = View.GONE
            return
        }
        val prefs = requireContext().getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_perm_requested", false)) {
            prefs.edit().putBoolean("notif_perm_requested", true).apply()
            mPermReceiver.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        updateNotifBanner()
    }

    private fun updateNotifBanner() {
        if (!isAdded || !::mPermissionView.isInitialized) return
        val granted =
            (requireActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        mPermissionView.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) return
        mPermissionView.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                mPermReceiver.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Permanently declined (or "don't ask again"): offer
                // the app settings instead of a dead system dialog.
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.minab_notification_open_settings)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + requireContext().packageName)
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Minab v0.4 — update notice on Home. The check is cached for
        24 h inside MinabUpdateChecker, so this is cheap on launch. */
    private fun checkForUpdates() {
        val banner = updateBanner ?: return
        MinabUpdateChecker.check(requireContext(), false) { latestTag, available ->
            if (!isAdded) return@check
            if (available && latestTag != null) {
                banner.text = getString(R.string.minab_update_available, latestTag)
                banner.visibility = View.VISIBLE
                banner.setOnClickListener { MinabUpdateChecker.openReleases(requireContext()) }
            } else {
                banner.visibility = View.GONE
            }
        }
    }

    private fun setListAdapter() {
        if (mArrayadapter == null) {
            mArrayadapter =
                VPNArrayAdapter(getActivity()!!, R.layout.vpn_list_item, R.id.vpn_item_title)
        }
        populateVpnList()
    }

    private fun populateVpnList() {
        val sortByLRU = Preferences.getDefaultSharedPreferences(requireActivity()).getBoolean(
            PREF_SORT_BY_LRU, false
        )
        this.pM.refreshVPNList(requireContext())
        val allvpn: MutableCollection<VpnProfile?>? = this.pM.getProfiles()
        // Minab Children: filter by the selected category (null = all).
        val filtered = allvpn?.filter { profile ->
            selectedCategory == null || profile?.getCategory() == selectedCategory
        } ?: emptyList()
        val sortedset: TreeSet<VpnProfile?> = when (sortMode) {
            SORT_PING -> TreeSet<VpnProfile?>(VpnProfilePingComparator())
            SORT_REAL -> TreeSet<VpnProfile?>(VpnProfileRealComparator())
            else -> if (sortByLRU) TreeSet<VpnProfile?>(VpnProfileLRUComparator())
                else TreeSet<VpnProfile?>(VpnProfileNameComparator())
        }

        sortedset.addAll(filtered)
        mArrayadapter!!.clear()
        mArrayadapter!!.addAll(sortedset)

        setListAdapter(mArrayadapter)
        mArrayadapter!!.notifyDataSetChanged()
        rebuildCategoryChips()
        // Minab v0.5: the list is part of the page's single scroll,
        // so size the ListView to its content (no inner scroll).
        // v0.5.1 HOTFIX: populateVpnList() also runs from onCreate()
        // (via setListAdapter), where the fragment has no view yet —
        // ListFragment.listView THROWS there ("Content view not yet
        // created"), which crashed the app on every launch. Fragment
        // .view returns null instead of throwing, so post on that.
        view?.post { expandListView() }
    }

    /** Set the Home sort mode and persist it (see the sortMode
        field). The last completed test wins. */
    private fun setSortMode(mode: Int) {
        sortMode = mode
        try {
            requireContext().getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE)
                .edit().putInt(PREF_SORT_MODE, mode).apply()
        } catch (e: Exception) {
            // Prefs unavailable: the in-memory mode still applies
            // for this session.
        }
    }

    /** Re-sort the rows already in the adapter, live, while a test
        runs — without rebuilding chips or re-measuring the list.
        No-op unless the current sort mode is PING or REAL. */
    private fun resortAdapter() {
        val adapter = mArrayadapter ?: return
        val sorted = when (sortMode) {
            SORT_PING -> (0 until adapter.count).mapNotNull { adapter.getItem(it) }
                .sortedWith(VpnProfilePingComparator())
            SORT_REAL -> (0 until adapter.count).mapNotNull { adapter.getItem(it) }
                .sortedWith(VpnProfileRealComparator())
            else -> return
        }
        adapter.clear()
        adapter.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    /** Minab v0.5 — size the (non-scrolling) ListView to the total
        height of its rows, so the whole Home page scrolls as one.
        Called after the layout pass, when the width is known.
        v0.5.1: bail out when the view is gone, and never let one
        bad row kill the whole app at startup. */
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
        for (i in 0 until adapter.count) {
            try {
                val item = adapter.getView(i, null, list)
                item.measure(widthSpec, heightSpec)
                totalHeight += item.measuredHeight
            } catch (e: Exception) {
                // One bad row must not crash the app at startup;
                // assume the standard 48dp row height for it.
                totalHeight += dp(48)
            }
        }
        totalHeight += list.dividerHeight * (adapter.count - 1)
        val params = list.layoutParams
        params.height = totalHeight
        list.layoutParams = params
    }

    // --- Minab Children: categories ---

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Rebuilds the group tabs (v0.4): All + one tab per group +
        a Manage tab + a "+" tab to create a group by hand. Groups
        are the categories: countries are assigned automatically on
        import by AutoCategory, and the user can create or rename
        groups. Tapping a tab filters the list; Ping test / Real
        test test the selected group (v0.8 — the per-group ping
        icon is gone). Replaces the plain v0.2 chips.
        v0.5: smaller and more minimal (11sp, tighter padding), the
        first tab reads just "all", the "+" tab is LAST, and a
        trailing peek spacer makes the fade at the right edge show
        that the bar scrolls. */
    private fun rebuildCategoryChips() {
        val container = categoryChipsContainer ?: return
        container.removeAllViews()
        val context = requireContext()
        addGroupTab(container, null)
        // Minab v0.7: "Uncategorized" goes LAST (just before "+"),
        // after the country groups and Manage — it is the catch-all,
        // not a country.
        for (category in CategoryStore.getCategories(context)) {
            if (category == CategoryStore.DEFAULT_CATEGORY) continue
            addGroupTab(container, category)
        }
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
        addGroupTab(container, CategoryStore.DEFAULT_CATEGORY)
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
        add.setOnClickListener { showNewCategoryDialog { populateVpnList() } }
        container.addView(add)
        // Trailing peek spacer: the last tab is cut off mid-way by
        // the fade, so the bar visibly invites scrolling right.
        val peek = View(context)
        peek.layoutParams = LinearLayout.LayoutParams(dp(20), dp(1))
        container.addView(peek)
    }

    /** One group tab: label only (tap = filter). category == null
        is "all". Minab v0.8: the small ping icon next to the label
        is gone — Ping test / Real test now test the selected group,
        so the icon had no job left. v0.5: 11sp, tighter padding. */
    private fun addGroupTab(
        container: LinearLayout,
        category: String?
    ) {
        val context = requireContext()
        val selected = category == selectedCategory
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
        val label = TextView(context)
        label.text = category ?: getString(R.string.minab_all_tab)
        label.textSize = 11f
        label.setTextColor(
            resources.getColor(
                if (selected) R.color.minab_teal_dark else R.color.minab_chip_text,
                context.theme
            )
        )
        label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        label.setOnClickListener {
            // Minab v0.7: filtering must NOT reset the sort mode —
            // switching groups used to silently drop the ping/real
            // sort, which is why the sort "did not stick".
            selectedCategory = category
            populateVpnList()
        }
        tab.addView(label)
        container.addView(tab)
    }

    private fun showCategoryFilterDialog() {
        val categories = mutableListOf<String?>(null) // null = All
        categories.addAll(CategoryStore.getCategories(requireContext()))
        val labels = categories.map {
            it ?: getString(R.string.minab_all_categories)
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_filter_category)
            .setItems(labels) { _, which ->
                selectedCategory = categories[which]
                populateVpnList()
            }
            .setNeutralButton(R.string.minab_manage_categories) { _, _ ->
                showManageCategoriesDialog()
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_new_category)
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
        populateVpnList()
    }

    private fun showManageCategoriesDialog() {
        val categories = CategoryStore.getCategories(requireContext())
            .filter { it != CategoryStore.DEFAULT_CATEGORY }
        if (categories.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_new_category, Toast.LENGTH_SHORT).show()
            showNewCategoryDialog { populateVpnList() }
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
                                    populateVpnList()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        } else {
                            CategoryStore.deleteCategory(requireContext(), oldName)
                            if (selectedCategory == oldName) selectedCategory = null
                            populateVpnList()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNeutralButton(R.string.minab_new_category) { _, _ ->
                showNewCategoryDialog { populateVpnList() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Minab v0.3: PowerOrb (Namaki-style home connect button) ---

    /** The profile the orb connects: the connected one while a VPN
        is active, else the most recently used, else the first. */
    private fun orbTargetProfile(): VpnProfile? {
        val all = pM.getProfiles().toList()
        if (all.isEmpty()) return null
        if (VpnStatus.isVPNActive()) {
            val connected = VpnStatus.getLastConnectedVPNProfile()
            all.firstOrNull { it.getUUIDString() == connected }?.let { return it }
        }
        return all.maxByOrNull { it.mLastUsed } ?: all.firstOrNull()
    }

    private fun toggleOrbConnection() {
        val target = orbTargetProfile()
        if (target == null) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_yet, Toast.LENGTH_LONG).show()
            startActivity(Intent(requireContext(), BulkImportActivity::class.java))
            return
        }
        startOrStopVPN(target)
        updateOrbState()
    }

    /** Disc colour + state line under the orb, like Namaki's home:
        green disc + "Connected — <name>" while connected, indigo
        disc + "Tap to connect" otherwise. Safe to call before the
        view exists (onResume / state updates). v0.7: the connected
        line is small and minimal (12sp, one line) and the connected
        server's ping sits next to it as a tappable pill. */
    private fun updateOrbState() {
        val button = orbButton ?: return
        val status = orbStatusView ?: return
        if (!isAdded) return
        val active = VpnStatus.isVPNActive()
        button.setBackgroundResource(
            if (active) R.drawable.minab_orb_disc_connected
            else R.drawable.minab_orb_disc)
        if (active) {
            val uuid = VpnStatus.getLastConnectedVPNProfile()
            val profile = uuid?.let { ProfileManager.get(requireContext(), it) }
            val name = profile?.mName
            status.text = if (name.isNullOrEmpty()) {
                getString(R.string.minab_status_connected)
            } else {
                getString(R.string.minab_status_connected) + " — " + name
            }
            status.textSize = 12f
            status.setTextColor(resources.getColor(R.color.minab_connected, requireContext().theme))
            val pill = connectedPingView
            if (profile != null && pill != null) {
                pill.visibility = View.VISIBLE
                if (!connectedPingTesting) pill.text = connectedPingLabel(profile)
            } else {
                pill?.visibility = View.GONE
            }
        } else {
            status.text = getString(R.string.minab_tap_to_connect)
            status.textSize = 18f
            status.setTextColor(resources.getColor(R.color.minab_text_primary, requireContext().theme))
            connectedPingView?.visibility = View.GONE
        }
    }

    /** The pill text for the connected server: its last ping
        ("94 ms" / "Timeout"), or "—" when never tested (tap the
        pill to test now). */
    private fun connectedPingLabel(profile: VpnProfile): String {
        if (profile.mLastPingResult.isNotEmpty()) return profile.mLastPingResult
        if (profile.mLastPingMs > 0) return profile.mLastPingMs.toString() + " ms"
        return "—"
    }

    /** Minab v0.7: re-test the ping of the currently connected
        server only (like Namaki: tap the ping to re-check it).
        Runs on a worker thread; updates the pill and the row
        badge when the result arrives. */
    private fun retestConnectedPing() {
        if (connectedPingTesting) return
        if (!VpnStatus.isVPNActive()) return
        val uuid = VpnStatus.getLastConnectedVPNProfile() ?: return
        val profile = try {
            ProfileManager.get(requireContext(), uuid)
        } catch (e: Exception) {
            null
        } ?: return
        val pill = connectedPingView ?: return
        connectedPingTesting = true
        pill.text = "…"
        Thread {
            val result = try {
                PingTester.testOne(profile)
            } catch (e: Exception) {
                null
            }
            activity?.runOnUiThread {
                connectedPingTesting = false
                if (!isAdded) return@runOnUiThread
                if (result != null) {
                    profile.mLastPingMs =
                        if (result.status == PingTester.Status.OK) result.ms else -1
                    profile.mLastPingTime = result.timestampMs
                    profile.mLastPingResult = result.displayText()
                    try {
                        ProfileManager.saveProfile(requireContext(), profile)
                    } catch (e: Exception) {
                        // Saving must not hide the result on screen.
                    }
                }
                updateOrbState()
                mArrayadapter?.notifyDataSetChanged()
            }
        }.start()
    }

    // --- Minab Children: bulk ping ---

    private fun visibleProfiles(): List<VpnProfile> {
        val all = pM.getProfiles().toList()
        val wanted = selectedCategory
        return if (wanted == null) all else all.filter { it.getCategory() == wanted }
    }

    private fun startPingTest(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_in_category, Toast.LENGTH_SHORT).show()
            return
        }
        cancelPingTest()
        cancelRealTest()
        // Minab v0.7: do NOT reset the sort here — the list keeps
        // its previous order during the test; the finish sets the
        // new mode (last completed test wins).
        if (VpnStatus.isVPNActive()) {
            Toast.makeText(requireContext(), R.string.minab_ping_while_vpn, Toast.LENGTH_LONG).show()
        }
        runPingPhase(profiles) { cancelled ->
            // Runs on the UI thread (runPingPhase posts it).
            if (!isAdded) return@runPingPhase
            if (cancelled) {
                Toast.makeText(requireContext(), R.string.minab_ping_cancelled, Toast.LENGTH_SHORT).show()
                pingProgressRow?.visibility = View.GONE
            } else {
                setSortMode(SORT_PING)
                populateVpnList()
                Toast.makeText(requireContext(), R.string.minab_ping_done, Toast.LENGTH_SHORT).show()
                // Keep the progress row visible as a "done" line:
                // full bar, no Cancel button, sorted-fastest note.
                pingProgressRow?.visibility = View.VISIBLE
                pingProgressBar?.progress = 100
                pingCancelButton?.visibility = View.GONE
                pingStatusView?.text = getString(R.string.minab_sort_by_ping)
            }
        }
    }

    /** Run the fast parallel ping over these profiles, recording each
        result like the standalone ping test. onFinished runs on the
        UI thread with true when the run was cancelled. The caller
        owns what happens after (sort, toast, or the real test's
        next phase). */
    private fun runPingPhase(profiles: List<VpnProfile>, onFinished: (Boolean) -> Unit) {
        val tester = PingTester()
        pingTester = tester
        pingDoneCount = 0
        pingTotalCount = profiles.size
        updatePingStatus()
        tester.testAll(profiles, object : PingTester.PingListener {
            override fun onResult(result: PingTester.Result) {
                // Post to the UI thread; the fragment may be gone by then.
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    recordPingResult(result)
                }
            }

            override fun onFinished(cancelled: Boolean) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    pingTester = null
                    onFinished(cancelled)
                }
            }
        })
    }

    /** Save one ping result on its profile and advance the progress
        row. Must run on the UI thread. */
    private fun recordPingResult(result: PingTester.Result) {
        result.profile.mLastPingMs =
            if (result.status == PingTester.Status.OK) result.ms else -1
        result.profile.mLastPingTime = result.timestampMs
        result.profile.mLastPingResult = result.displayText()
        ProfileManager.saveProfile(requireContext(), result.profile)
        pingDoneCount++
        updatePingStatus()
        // Minab v0.7: re-sort live as results arrive (when the
        // list is ping-sorted), so the fastest server floats to
        // the top during the test, not only at the end.
        if (sortMode == SORT_PING) resortAdapter()
        else mArrayadapter?.notifyDataSetChanged()
    }

    private fun updatePingStatus() {
        val row = pingProgressRow ?: return
        if (pingTester == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        pingCancelButton?.visibility = View.VISIBLE
        pingProgressBar?.progress =
            if (pingTotalCount > 0) pingDoneCount * 100 / pingTotalCount else 0
        pingStatusView?.text = getString(R.string.minab_ping_running, pingTotalCount, pingDoneCount)
    }

    private fun cancelPingTest() {
        pingTester?.cancel()
        pingTester = null
        pingProgressRow?.visibility = View.GONE
    }

    // --- Minab v0.6: Real test ---
    //
    // One tap, two stages (the runner is minab.RealTester, shared
    // with Configs): a fast ping phase first when the ping results
    // are stale, then the real phase — connect each server in
    // ranked order, fetch https://web.telegram.org/ through the
    // tunnel, stop early after REAL_TEST_TARGET passes. Home waits
    // HOME_REAL_CONNECT_TIMEOUT_MS per server; Configs waits 20 s.
    // Cancel (or leaving Home for more than a few seconds) stops
    // the run and drops the VPN under test. Starting a ping test
    // cancels a real test and vice versa; a real test running in
    // Configs blocks starting one here (shared RealTester slot).

    private fun startRealTest(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_in_category, Toast.LENGTH_SHORT).show()
            return
        }
        cancelPingTest()
        cancelRealTest()
        // Minab v0.7: do NOT reset the sort here — the finish sets
        // the new mode (last completed test wins).
        realTestRunId++
        val runId = realTestRunId
        val tester = RealTester(
            requireContext().applicationContext,
            HOME_REAL_CONNECT_TIMEOUT_MS,
            RealTester.REAL_TEST_TARGET,
            object : RealTester.Listener {
                override fun onPingProgress(done: Int, total: Int) {
                    if (!isAdded || runId != realTestRunId) return
                    pingDoneCount = done
                    pingTotalCount = total
                    pingProgressRow?.visibility = View.VISIBLE
                    pingCancelButton?.visibility = View.VISIBLE
                    pingProgressBar?.progress =
                        if (total > 0) done * 100 / total else 0
                    pingStatusView?.text =
                        getString(R.string.minab_ping_running, total, done)
                }

                override fun onRealProgress(
                    done: Int, total: Int, currentName: String?, working: Int
                ) {
                    if (!isAdded || runId != realTestRunId) return
                    realDoneCount = done
                    realTotalCount = total
                    realWorkingCount = working
                    realTestCurrentName = currentName
                    updateRealStatus()
                }

                override fun onProfileResult(profile: VpnProfile) {
                    if (!isAdded || runId != realTestRunId) return
                    // Minab v0.7: re-sort live as results arrive
                    // (when the list is real-sorted).
                    if (sortMode == SORT_REAL) resortAdapter()
                    else mArrayadapter?.notifyDataSetChanged()
                }

                override fun onFinished(cancelled: Boolean, working: Int) {
                    if (!isAdded || runId != realTestRunId) return
                    realTester = null
                    realTestActive = false
                    if (cancelled) {
                        pingProgressRow?.visibility = View.GONE
                        return
                    }
                    setSortMode(SORT_REAL)
                    populateVpnList()
                    Toast.makeText(requireContext(), R.string.minab_real_done, Toast.LENGTH_SHORT).show()
                    pingProgressRow?.visibility = View.VISIBLE
                    pingProgressBar?.progress = 100
                    pingCancelButton?.visibility = View.GONE
                    pingStatusView?.text = getString(R.string.minab_sort_by_real)
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
        pingProgressRow?.visibility = View.GONE
    }

    /** Progress row while the real phase runs: "Real test 2/5 —
        <name> (1 working)". Shared with the ping test (same row,
        same Cancel button); the two tests never run together. */
    private fun updateRealStatus() {
        val row = pingProgressRow ?: return
        if (!realTestActive) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        pingCancelButton?.visibility = View.VISIBLE
        pingProgressBar?.progress =
            if (realTotalCount > 0) realDoneCount * 100 / realTotalCount else 0
        pingStatusView?.text = getString(
            R.string.minab_real_running,
            realDoneCount, realTotalCount,
            realTestCurrentName ?: "", realWorkingCount
        )
    }

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        v: View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val info = menuInfo as? android.widget.AdapterView.AdapterContextMenuInfo ?: return
        val profile = mArrayadapter?.getItem(info.position) ?: return
        menu.setHeaderTitle(profile.name)
        menu.add(0, MENU_ASSIGN_CATEGORY, 0, R.string.minab_assign_category)
        menu.add(0, MENU_PING_ONE, 0, R.string.minab_ping_one)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as? android.widget.AdapterView.AdapterContextMenuInfo
            ?: return super.onContextItemSelected(item)
        val profile = mArrayadapter?.getItem(info.position) ?: return super.onContextItemSelected(item)
        return when (item.itemId) {
            MENU_ASSIGN_CATEGORY -> {
                showAssignCategoryDialog(profile)
                true
            }
            MENU_PING_ONE -> {
                startPingTest(listOf(profile))
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Minab Beta v0.5 — add / single import / bulk import moved
        // to the activity's top bar (MainActivity), so they are the
        // same on every tab and are not duplicated here.
        menu.add(0, MENU_CHANGE_SORTING, 0, R.string.change_sorting)
            .setIcon(R.drawable.ic_sort)
            .setAlphabeticShortcut('s')
            .setTitleCondensed(getString(R.string.sort))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        menu.add(0, MENU_IMPORT_AS, 0, R.string.import_from_as)
            .setIcon(R.drawable.ic_menu_import_download)
            .setAlphabeticShortcut('p')
            .setTitleCondensed("Import AS")
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        // Minab Children
        menu.add(0, MENU_PING_ALL, 0, R.string.minab_ping_all)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_PING_CATEGORY, 0, R.string.minab_ping_category)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_FILTER_CATEGORY, 0, R.string.minab_filter_category)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.getItemId()
        if (itemId == MENU_CHANGE_SORTING) {
            return changeSorting()
        } else if (itemId == MENU_IMPORT_AS) {
            return startASProfileImport()
        } else if (itemId == MENU_PING_ALL) {
            startPingTest(pM.getProfiles().toList())
            return true
        } else if (itemId == MENU_PING_CATEGORY) {
            startPingTest(visibleProfiles())
            return true
        } else if (itemId == MENU_FILTER_CATEGORY) {
            showCategoryFilterDialog()
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    private fun startASProfileImport(): Boolean {
        val asImportFrag = newInstance(null)
        asImportFrag.show(getParentFragmentManager(), "dialog")
        return true
    }

    private fun changeSorting(): Boolean {
        val prefs = Preferences.getDefaultSharedPreferences(requireActivity())
        val oldValue = prefs.getBoolean(PREF_SORT_BY_LRU, false)
        val prefsedit = prefs.edit()
        if (oldValue) {
            Toast.makeText(getActivity(), R.string.sorted_az, Toast.LENGTH_SHORT).show()
            prefsedit.putBoolean(PREF_SORT_BY_LRU, false)
        } else {
            prefsedit.putBoolean(PREF_SORT_BY_LRU, true)
            Toast.makeText(getActivity(), R.string.sorted_lru, Toast.LENGTH_SHORT).show()
        }
        prefsedit.apply()
        // Minab v0.7: an explicit sort change from the menu clears
        // the test sort mode, so the LRU / name sort actually shows.
        setSortMode(SORT_NONE)
        populateVpnList()
        return true
    }

    override fun onClick(v: View) {
        when (v.getId()) {
            R.id.fab_import -> startImportConfigFilePicker()
            R.id.fab_add -> onAddOrDuplicateProfile(null)
        }
    }

    private fun startImportConfigFilePicker(): Boolean {
        var startOldFileDialog = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !alwaysUseOldFileChooser(
                getActivity()
            )
        ) startOldFileDialog = !startFilePicker()

        if (startOldFileDialog) startImportConfig()

        return true
    }

    private fun startFilePicker(): Boolean {
        val i = Utils.getFilePickerIntent(getActivity()!!, Utils.FileType.OVPN_CONFIG)
        if (i != null) {
            startActivityForResult(i, FILE_PICKER_RESULT_KITKAT)
            return true
        } else return false
    }

    private fun startImportConfig() {
        val intent = Intent(getActivity(), FileSelect::class.java)
        intent.putExtra(FileSelect.NO_INLINE_SELECTION, true)
        intent.putExtra(FileSelect.WINDOW_TITLE, R.string.import_configuration_file)
        startActivityForResult(intent, SELECT_PROFILE)
    }

    /** Minab Beta v0.5 — entry point for the top-bar "+" (the
        activity switches to Home, then calls this). Opens the same
        add-one-config dialog as the old overflow item. */
    fun openAddProfile() {
        if (!isAdded) return
        onAddOrDuplicateProfile(null)
    }

    private fun onAddOrDuplicateProfile(mCopyProfile: VpnProfile?) {
        val context: Context? = getActivity()
        if (context != null) {
            val entry = EditText(context)
            entry.setSingleLine()
            entry.setContentDescription(getString(R.string.name_of_the_vpn_profile))

            val dialog = AlertDialog.Builder(context)
            if (mCopyProfile == null) dialog.setTitle(R.string.menu_add_profile)
            else {
                dialog.setTitle(
                    context.getString(
                        R.string.duplicate_profile_title,
                        mCopyProfile.mName
                    )
                )
                entry.setText(getString(R.string.copy_of_profile, mCopyProfile.mName))
            }

            dialog.setMessage(R.string.add_profile_name_prompt)
            dialog.setView(entry)

            dialog.setNeutralButton(
                R.string.menu_import_short
            ) { dialog1: DialogInterface?, which: Int -> startImportConfigFilePicker() }
            dialog.setPositiveButton(
                android.R.string.ok
            ) { dialog12: DialogInterface?, which: Int ->
                val name = entry.getText().toString()
                if (this.pM.getProfileByName(name) == null) {
                    val profile: VpnProfile
                    if (mCopyProfile != null) {
                        profile = mCopyProfile.copy(name)
                        // Remove restrictions on copy profile
                        profile.mProfileCreator = null
                        profile.mUserEditable = true
                    } else profile = VpnProfile(name)

                    addProfile(profile)
                    editVPN(profile)
                } else {
                    Toast.makeText(
                        getActivity(),
                        R.string.duplicate_profile_name,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            dialog.setNegativeButton(android.R.string.cancel, null)
            dialog.create().show()
        }
    }

    private fun addProfile(profile: VpnProfile) {
        this.pM.addProfile(profile)
        this.pM.saveProfileList(getActivity())
        profile.addChangeLogEntry("empty profile added via main profile list")
        ProfileManager.saveProfile(getActivity(), profile)
        mArrayadapter!!.add(profile)
    }

    private val pM: ProfileManager
        get() = ProfileManager.getInstance(getActivity())

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_VPN_DELETED) {
            if (mArrayadapter != null && mEditProfile != null) mArrayadapter!!.remove(mEditProfile)
        } else if (resultCode == RESULT_VPN_DUPLICATE && data != null) {
            val profileUUID = data.getStringExtra(VpnProfile.EXTRA_PROFILEUUID)
            val profile = ProfileManager.get(getActivity(), profileUUID)
            if (profile != null) onAddOrDuplicateProfile(profile)
        }

        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == EDIT_VPN_CONFIG) {
            val configuredVPN = data!!.getStringExtra(VpnProfile.EXTRA_PROFILEUUID)

            val profile = ProfileManager.get(getActivity(), configuredVPN)
            profile.addChangeLogEntry("Profile edited by user")
            ProfileManager.saveProfile(getActivity(), profile)
            // Name could be modified, reset List adapter
            setListAdapter()
        } else if (requestCode == SELECT_PROFILE) {
            val fileData = data!!.getStringExtra(FileSelect.RESULT_DATA)
            val uri = Uri.Builder().path(fileData).scheme("file").build()

            startConfigImport(uri)
        } else if (requestCode == IMPORT_PROFILE) {
            val profileUUID = data!!.getStringExtra(VpnProfile.EXTRA_PROFILEUUID)
            mArrayadapter!!.add(ProfileManager.get(getActivity(), profileUUID))
        } else if (requestCode == FILE_PICKER_RESULT_KITKAT) {
            if (data != null) {
                val uri = data.getData()
                startConfigImport(uri)
            }
        }
    }

    private fun startConfigImport(uri: Uri?) {
        val startImport = Intent(getActivity(), ConfigConverter::class.java)
        startImport.setAction(ConfigConverter.IMPORT_PROFILE)
        startImport.setData(uri)
        startActivityForResult(startImport, IMPORT_PROFILE)
    }

    private fun editVPN(profile: VpnProfile) {
        mEditProfile = profile
        val vprefintent = Intent(getActivity(), VPNPreferences::class.java)
            .putExtra(
                getActivity()!!.getPackageName() + ".profileUUID",
                profile.getUUID().toString()
            )

        startActivityForResult(vprefintent, EDIT_VPN_CONFIG)
    }

    private fun startVPN(profile: VpnProfile) {
        ProfileManager.saveProfile(getActivity(), profile)

        val intent = Intent(getActivity(), LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "main profile list")
        // Minab v0.4: stay on the home screen while connecting —
        // do not auto-open the log window (the user asked for this).
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
        intent.setAction(Intent.ACTION_MAIN)
        startActivity(intent)
    }

    internal class VpnProfileRealComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs) return 0
            if (lhs == null) return -1
            if (rhs == null) return 1
            // Working servers first, fastest real delay first.
            // Minab v0.7: 0 ms is not a measurement (see
            // VpnProfile migration guard), so "> 0" here.
            val lReal = lhs.mLastRealMs > 0
            val rReal = rhs.mLastRealMs > 0
            if (lReal != rReal) return if (lReal) -1 else 1
            if (lReal) {
                if (lhs.mLastRealMs != rhs.mLastRealMs) return lhs.mLastRealMs.compareTo(rhs.mLastRealMs)
                return (lhs.mName ?: "").compareTo(rhs.mName ?: "")
            }
            // The rest by ping: tested first by ms, then the rest by name.
            val l = if (lhs.mLastPingMs > 0) lhs.mLastPingMs else Long.MAX_VALUE
            val r = if (rhs.mLastPingMs > 0) rhs.mLastPingMs else Long.MAX_VALUE
            if (l != r) return l.compareTo(r)
            return (lhs.mName ?: "").compareTo(rhs.mName ?: "")
        }
    }

    internal class VpnProfilePingComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs) return 0
            if (lhs == null) return -1
            if (rhs == null) return 1
            // Tested profiles first, fastest first; untested / failed last.
            // Minab v0.7: 0 ms is not a measurement (see
            // VpnProfile migration guard), so "> 0" here.
            val l = if (lhs.mLastPingMs > 0) lhs.mLastPingMs else Long.MAX_VALUE
            val r = if (rhs.mLastPingMs > 0) rhs.mLastPingMs else Long.MAX_VALUE
            if (l != r) return l.compareTo(r)
            return (lhs.mName ?: "").compareTo(rhs.mName ?: "")
        }
    }

    internal class VpnProfileNameComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs)  // Catches also both null
                return 0

            if (lhs == null) return -1
            if (rhs == null) return 1

            if (lhs.mName == null) return -1
            if (rhs.mName == null) return 1

            return lhs.mName.compareTo(rhs.mName)
        }
    }

    internal class VpnProfileLRUComparator : Comparator<VpnProfile?> {
        var nameComparator: VpnProfileNameComparator = VpnProfileNameComparator()

        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs)  // Catches also both null
                return 0

            if (lhs == null) return -1
            if (rhs == null) return 1

            // Copied from Long.compare
            if (lhs.mLastUsed > rhs.mLastUsed) return -1
            if (lhs.mLastUsed < rhs.mLastUsed) return 1
            else return nameComparator.compare(lhs, rhs)
        }
    }

    private inner class VPNArrayAdapter(
        context: Context, resource: Int,
        textViewResourceId: Int
    ) : ArrayAdapter<VpnProfile>(context, resource, textViewResourceId) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)

            val profile = getListAdapter()!!.getItem(position) as VpnProfile

            // Minab v0.7: highlight the connected server's row in
            // green, so it is obvious at a glance. Both branches
            // must set the colours (rows are recycled). Updates
            // live: updateState() calls notifyDataSetChanged() on
            // every connection-state change.
            val connected = VpnStatus.isVPNActive() &&
                profile.getUUIDString() == VpnStatus.getLastConnectedVPNProfile()
            if (connected) {
                v.setBackgroundColor(
                    resources.getColor(R.color.minab_connected_bg, requireContext().theme))
            } else {
                v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            val titleText = v.findViewById<TextView>(R.id.vpn_item_title)
            titleText.setTextColor(
                resources.getColor(
                    if (connected) R.color.minab_connected else R.color.minab_text_primary,
                    requireContext().theme))

            val titleview = v.findViewById<View>(R.id.vpn_list_item_left)
            titleview.setOnClickListener(View.OnClickListener { v1: View? -> startOrStopVPN(profile) })

            val settingsview = v.findViewById<View>(R.id.quickedit_settings)
            settingsview.setOnClickListener(View.OnClickListener { view: View? -> editVPN(profile) })

            val subtitle = v.findViewById<TextView>(R.id.vpn_item_subtitle)
            val warningText = getWarningText(requireContext(), profile)

            if (profile === defaultVPN) {
                if (warningText.length > 0) warningText.append(" ")
                warningText.append(SpannableString("Default VPN"))
            }

            if (profile.getUUIDString() == VpnStatus.getLastConnectedVPNProfile()) {
                subtitle.setText(mLastStatusMessage)
                subtitle.setVisibility(View.VISIBLE)
            } else {
                // Minab Children: the category stays under the name; the
                // ping result moved to the visible badge (v0.2), so the
                // small " • 24 ms" text is no longer appended here.
                val minabInfo = StringBuilder()
                minabInfo.append("[").append(profile.getCategory()).append("]")
                if (warningText.length > 0) {
                    warningText.append(" — ").append(minabInfo)
                    subtitle.setText(warningText)
                } else {
                    subtitle.setText(minabInfo.toString())
                }
                subtitle.setVisibility(View.VISIBLE)
            }

            // Minab Children (v0.2): visible ping badge next to the name.
            // v0.3 colours follow Namaki: green < 100 ms, amber
            // 100–299 ms, grey >= 300 ms / Timeout / Failed. Hidden
            // until a profile has a ping result.
            // Minab Beta v0.5: country flag before the name (bundled
            // flags for the 36 most-used countries, globe otherwise).
            val flagView = v.findViewById<android.widget.ImageView>(R.id.minab_flag)
            val flagRes = FlagHelper.flagResForCategory(profile.getCategory())
            if (flagRes != 0) {
                flagView.setImageResource(flagRes)
                flagView.visibility = View.VISIBLE
            } else {
                flagView.visibility = View.GONE
            }
            val badge = v.findViewById<TextView>(R.id.minab_ping_badge)
            // Minab v0.7: "> 0" (not ">= 0") — a 0 ms value is not
            // a measurement (see VpnProfile migration guard), and
            // the else-branch ignores a stale "0 ms" result text.
            if (profile.mLastPingMs > 0) {
                badge.visibility = View.VISIBLE
                badge.text = profile.mLastPingResult.ifEmpty { profile.mLastPingMs.toString() + " ms" }
                badge.setBackgroundResource(
                    when {
                        profile.mLastPingMs < 100 -> R.drawable.minab_ping_badge_fast
                        profile.mLastPingMs < 300 -> R.drawable.minab_ping_badge_slow
                        else -> R.drawable.minab_ping_badge_fail
                    }
                )
            } else if (profile.mLastPingResult.isNotEmpty() && profile.mLastPingMs != 0L) {
                badge.visibility = View.VISIBLE
                badge.text = profile.mLastPingResult
                badge.setBackgroundResource(R.drawable.minab_ping_badge_fail)
            } else {
                badge.visibility = View.GONE
            }

            // Minab Beta v0.6: real-test badge next to the ping badge.
            // Shown only when a real test result exists for this
            // profile ("Real: 850 ms" when the server really bypasses
            // filtering, "Blocked" when it does not).
            val realBadge = v.findViewById<TextView>(R.id.minab_real_badge)
            // Minab v0.7: "> 0" (not ">= 0") — a 0 ms value is not
            // a measurement (see VpnProfile migration guard), and
            // the else-branch ignores a stale "Real: 0 ms" text.
            if (profile.mLastRealMs > 0) {
                realBadge.visibility = View.VISIBLE
                realBadge.text = profile.mLastRealResult.ifEmpty { getString(R.string.minab_real_ms, profile.mLastRealMs) }
                realBadge.setBackgroundResource(
                    when {
                        profile.mLastRealMs < 100 -> R.drawable.minab_ping_badge_fast
                        profile.mLastRealMs < 300 -> R.drawable.minab_ping_badge_slow
                        else -> R.drawable.minab_ping_badge_fail
                    }
                )
            } else if (profile.mLastRealResult.isNotEmpty() && profile.mLastRealMs != 0L) {
                realBadge.visibility = View.VISIBLE
                realBadge.text = profile.mLastRealResult
                realBadge.setBackgroundResource(R.drawable.minab_ping_badge_fail)
            } else {
                realBadge.visibility = View.GONE
            }

            return v
        }
    }

    internal inner class MiniImageGetter : Html.ImageGetter {
        override fun getDrawable(source: String?): Drawable? {
            var d: Drawable? = null
            if ("ic_menu_add" == source) d = requireActivity().getResources()
                .getDrawable(R.drawable.ic_menu_add_grey, requireActivity().getTheme())
            else if ("ic_menu_archive" == source) d = requireActivity().getResources()
                .getDrawable(R.drawable.ic_menu_import_grey, requireActivity().getTheme())


            if (d != null) {
                d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight())
                return d
            } else {
                return null
            }
        }
    }

    companion object {
        val RESULT_VPN_DELETED: Int = Activity.RESULT_FIRST_USER
        val RESULT_VPN_DUPLICATE: Int = Activity.RESULT_FIRST_USER + 1

        // Shortcut version is increased to refresh all shortcuts
        const val SHORTCUT_VERSION: Int = 1
        private const val EDIT_VPN_CONFIG = 92
        private const val SELECT_PROFILE = 43
        private const val IMPORT_PROFILE = 231
        private const val FILE_PICKER_RESULT_KITKAT = 392
        private val MENU_CHANGE_SORTING = Menu.FIRST + 2
        private val MENU_IMPORT_AS = Menu.FIRST + 3
        // Minab Children menu ids (v0.5: add / import / bulk import
        // moved to the activity top bar, so their ids are gone)
        private val MENU_PING_ALL = Menu.FIRST + 5
        private val MENU_PING_CATEGORY = Menu.FIRST + 6
        private val MENU_FILTER_CATEGORY = Menu.FIRST + 7
        private val MENU_ASSIGN_CATEGORY = Menu.FIRST + 8
        private val MENU_PING_ONE = Menu.FIRST + 9
        private const val PREF_SORT_BY_LRU = "sortProfilesByLRU"
        // Minab v0.6: how long the Real test waits for one server
        // to report CONNECTED on Home (Configs waits 20 s — its
        // own constant lives in ConfigsFragment). 5 s would wrongly
        // fail slow-but-working servers (and the first connect of
        // a run is slower because the VPN service starts cold),
        // while 12 s still fails dead servers much faster than 20 s.
        private const val HOME_REAL_CONNECT_TIMEOUT_MS = 12_000L
        // Minab v0.7: the Home list sort mode (see sortMode above).
        private const val SORT_NONE = 0
        private const val SORT_PING = 1
        private const val SORT_REAL = 2
        private const val PREF_SORT_MODE = "minab_sort_mode"
    }
}
