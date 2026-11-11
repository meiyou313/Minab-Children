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
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Html
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
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
import de.blinkt.openvpn.minab.PingTester
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
    /** When true the list is sorted by last ping, fastest first. */
    private var sortByPing: Boolean = false
    private var pingTester: PingTester? = null
    private var pingDoneCount: Int = 0
    private var pingTotalCount: Int = 0
    private var pingStatusView: TextView? = null
    private var categoryFilterView: TextView? = null

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
        setListAdapter()

        registerPermissionReceiver()
    }

    private fun registerPermissionReceiver() {
        mPermReceiver = registerForActivityResult<String, Boolean>(
            RequestPermission(),
            ActivityResultCallback { result: Boolean? -> checkForNotificationPermission(requireView()) })
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
        setListAdapter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            updateDynamicShortcuts()
        }
        VpnStatus.addStateListener(this)
        defaultVPN = ProfileManager.getAlwaysOnVPN(requireContext())
    }

    override fun onPause() {
        super.onPause()
        VpnStatus.removeStateListener(this)
    }

    override fun onDestroy() {
        // Stop the ping pool when the fragment is really gone. Results
        // already saved on profiles are kept.
        cancelPingTest()
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

        // Minab Children: category filter bar + long-press menu.
        categoryFilterView = v.findViewById<View?>(R.id.minab_category_filter) as TextView?
        pingStatusView = v.findViewById<View?>(R.id.minab_ping_status) as TextView?
        categoryFilterView?.setOnClickListener { showCategoryFilterDialog() }
        pingStatusView?.setOnClickListener { cancelPingTest() }
        updateCategoryFilterLabel()
        registerForContextMenu(v.findViewById(android.R.id.list))

        // TV builds show the minimal UI that already have the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !((activity as BaseActivity).isAndroidTV))
            checkForNotificationPermission(v)

        return v
    }

    private fun checkForNotificationPermission(v: View) {
        mPermissionView = v.findViewById<View>(R.id.notification_permission)
        val permissionGranted =
            (requireActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        mPermissionView.setVisibility(if (permissionGranted) View.GONE else View.VISIBLE)

        mPermissionView.setOnClickListener { view: View? ->
            mPermReceiver.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
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
        val sortedset: TreeSet<VpnProfile?>?
        if (sortByPing) sortedset = TreeSet<VpnProfile?>(VpnProfilePingComparator())
        else if (sortByLRU) sortedset = TreeSet<VpnProfile?>(VpnProfileLRUComparator())
        else sortedset = TreeSet<VpnProfile?>(VpnProfileNameComparator())

        sortedset.addAll(filtered)
        mArrayadapter!!.clear()
        mArrayadapter!!.addAll(sortedset)

        setListAdapter(mArrayadapter)
        mArrayadapter!!.notifyDataSetChanged()
        updateCategoryFilterLabel()
    }

    // --- Minab Children: categories ---

    private fun updateCategoryFilterLabel() {
        categoryFilterView?.text = selectedCategory
            ?: getString(R.string.minab_all_categories)
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
                sortByPing = false
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
        val tester = PingTester()
        pingTester = tester
        pingDoneCount = 0
        pingTotalCount = profiles.size
        sortByPing = false
        updatePingStatus()
        if (VpnStatus.isVPNActive()) {
            Toast.makeText(requireContext(), R.string.minab_ping_while_vpn, Toast.LENGTH_LONG).show()
        }
        tester.testAll(profiles, object : PingTester.PingListener {
            override fun onResult(result: PingTester.Result) {
                // Post to the UI thread; the fragment may be gone by then.
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    result.profile.mLastPingMs =
                        if (result.status == PingTester.Status.OK) result.ms else -1
                    result.profile.mLastPingTime = result.timestampMs
                    result.profile.mLastPingResult = result.displayText()
                    ProfileManager.saveProfile(requireContext(), result.profile)
                    pingDoneCount++
                    updatePingStatus()
                    mArrayadapter?.notifyDataSetChanged()
                }
            }

            override fun onFinished(cancelled: Boolean) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    pingTester = null
                    if (cancelled) {
                        Toast.makeText(requireContext(), R.string.minab_ping_cancelled, Toast.LENGTH_SHORT).show()
                        pingStatusView?.visibility = View.GONE
                    } else {
                        sortByPing = true
                        populateVpnList()
                        Toast.makeText(requireContext(), R.string.minab_ping_done, Toast.LENGTH_SHORT).show()
                        pingStatusView?.text = getString(R.string.minab_sort_by_ping)
                    }
                }
            }
        })
    }

    private fun updatePingStatus() {
        val view = pingStatusView ?: return
        if (pingTester == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = getString(R.string.minab_ping_running, pingTotalCount, pingDoneCount) +
            " — " + getString(R.string.minab_ping_cancel)
    }

    private fun cancelPingTest() {
        pingTester?.cancel()
        pingTester = null
        pingStatusView?.visibility = View.GONE
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
        menu.add(0, MENU_ADD_PROFILE, 0, R.string.menu_add_profile)
            .setIcon(R.drawable.ic_menu_add)
            .setAlphabeticShortcut('a')
            .setTitleCondensed(getActivity()!!.getString(R.string.add))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        menu.add(0, MENU_IMPORT_PROFILE, 0, R.string.menu_import)
            .setIcon(R.drawable.ic_menu_import)
            .setAlphabeticShortcut('i')
            .setTitleCondensed(getActivity()!!.getString(R.string.menu_import_short))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

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
        menu.add(0, MENU_BULK_IMPORT, 0, R.string.minab_bulk_import)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_PING_ALL, 0, R.string.minab_ping_all)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_PING_CATEGORY, 0, R.string.minab_ping_category)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_FILTER_CATEGORY, 0, R.string.minab_filter_category)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.getItemId()
        if (itemId == MENU_ADD_PROFILE) {
            onAddOrDuplicateProfile(null)
            return true
        } else if (itemId == MENU_IMPORT_PROFILE) {
            return startImportConfigFilePicker()
        } else if (itemId == MENU_CHANGE_SORTING) {
            return changeSorting()
        } else if (itemId == MENU_IMPORT_AS) {
            return startASProfileImport()
        } else if (itemId == MENU_BULK_IMPORT) {
            startActivity(Intent(requireContext(), BulkImportActivity::class.java))
            return true
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
        intent.setAction(Intent.ACTION_MAIN)
        startActivity(intent)
    }

    internal class VpnProfilePingComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs) return 0
            if (lhs == null) return -1
            if (rhs == null) return 1
            // Tested profiles first, fastest first; untested / failed last.
            val l = if (lhs.mLastPingMs >= 0) lhs.mLastPingMs else Long.MAX_VALUE
            val r = if (rhs.mLastPingMs >= 0) rhs.mLastPingMs else Long.MAX_VALUE
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
                // Minab Children: always show category + last ping under
                // the name, even when there is no warning to show.
                val minabInfo = StringBuilder()
                minabInfo.append("[").append(profile.getCategory()).append("]")
                if (profile.mLastPingResult.isNotEmpty()) {
                    minabInfo.append(" • ").append(profile.mLastPingResult)
                }
                if (warningText.length > 0) {
                    warningText.append(" — ").append(minabInfo)
                    subtitle.setText(warningText)
                } else {
                    subtitle.setText(minabInfo.toString())
                }
                subtitle.setVisibility(View.VISIBLE)
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
        private val MENU_ADD_PROFILE = Menu.FIRST
        private const val EDIT_VPN_CONFIG = 92
        private const val SELECT_PROFILE = 43
        private const val IMPORT_PROFILE = 231
        private const val FILE_PICKER_RESULT_KITKAT = 392
        private val MENU_IMPORT_PROFILE = Menu.FIRST + 1
        private val MENU_CHANGE_SORTING = Menu.FIRST + 2
        private val MENU_IMPORT_AS = Menu.FIRST + 3
        // Minab Children menu ids
        private val MENU_BULK_IMPORT = Menu.FIRST + 4
        private val MENU_PING_ALL = Menu.FIRST + 5
        private val MENU_PING_CATEGORY = Menu.FIRST + 6
        private val MENU_FILTER_CATEGORY = Menu.FIRST + 7
        private val MENU_ASSIGN_CATEGORY = Menu.FIRST + 8
        private val MENU_PING_ONE = Menu.FIRST + 9
        private const val PREF_SORT_BY_LRU = "sortProfilesByLRU"
    }
}
