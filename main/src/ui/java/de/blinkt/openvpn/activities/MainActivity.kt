/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import de.blinkt.openvpn.R
import de.blinkt.openvpn.activities.BulkImportActivity
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.GlobalPreferences
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.minab.MinabFailover
import de.blinkt.openvpn.minab.MinabWidgetProvider
import de.blinkt.openvpn.fragments.*
import de.blinkt.openvpn.fragments.ImportRemoteConfig.Companion.newInstance
import de.blinkt.openvpn.minab.MinabPagerSensitivity
import de.blinkt.openvpn.views.ScreenSlidePagerAdapter

class MainActivity : BaseActivity() {
    private lateinit var mPager: ViewPager2
    private lateinit var mPagerAdapter: ScreenSlidePagerAdapter


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = layoutInflater.inflate(R.layout.main_activity, null)

        // Instantiate a ViewPager and a PagerAdapter.
        mPager = view.findViewById(R.id.pager)
        val tablayout: TabLayout = view.findViewById(R.id.tab_layout)

        mPagerAdapter = ScreenSlidePagerAdapter(supportFragmentManager, lifecycle, this)

        /* Toolbar and slider should have the same elevation */
        disableToolbarElevation()

        val minimalUi = GlobalPreferences.getMinimalUi();
        if (isAndroidTV || minimalUi) {
            mPagerAdapter.addTab(R.string.minimal_ui, MinimalUI::class.java)
        }
        if (!minimalUi) {

            // Minab Beta v0.4: the first tab is HOME (was "Profiles")
            // and CONFIGS is the dedicated config-management tab
            // between Home and Graph.
            // Minab Beta v0.5: the FAQ tab left the bottom bar — FAQ
            // now lives at the end of Settings (see showFaq()).
            mPagerAdapter.addTab(R.string.minab_home_tab, VPNProfileList::class.java)
            mPagerAdapter.addTab(R.string.minab_configs_tab, ConfigsFragment::class.java)
            mPagerAdapter.addTab(R.string.graph, GraphFragment::class.java)
            mPagerAdapter.addTab(R.string.generalsettings, GeneralSettings::class.java)
            if (SendDumpFragment.getLatestDump(this) != null) {
                mPagerAdapter.addTab(R.string.crashdump, SendDumpFragment::class.java)
            }

        }
        if (isAndroidTV || minimalUi)
            mPagerAdapter.addTab(R.string.openvpn_log, LogFragment::class.java)

        mPagerAdapter.addTab(R.string.about, AboutFragment::class.java)
        mPager.setAdapter(mPagerAdapter)

        // Minab Beta v0.8 — tab swiping was too eager; a longer
        // drag is needed to change pages now (tab taps unaffected).
        MinabPagerSensitivity.reduceSensitivity(mPager)

        TabLayoutMediator(tablayout, mPager) { tab, position ->
            tab.text = mPagerAdapter.getPageTitle(position)
        }.attach()

        setUpEdgeEdgeInsetsListener(view, R.id.root_linear_layout)
        // Minab Beta v0.9 — the FAQ overlay is a sibling of the tab
        // content, so it needs the same top inset: without it the
        // overlay starts under the status bar / action bar and the
        // first question card's title is clipped (v0.8 screenshot).
        // setupBottom=false: the FAQ's own RecyclerView handles its
        // bottom padding (Utils.applyInsetListener).
        setUpEdgeEdgeInsetsListener(view, R.id.minab_faq_container, setupBottom = false)
        setContentView(view)

        // Minab Beta v0.9.1 — the backpack icon in the blue toolbar,
        // left of "Minab OpenVPN" (fix #12): the Home header used to
        // show the same icon + title again, so the title was shown
        // twice. The header row is now just the memorial line.
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayUseLogoEnabled(true)
        supportActionBar?.setLogo(R.drawable.ic_logo_bunt)

        // Minab Beta v0.5 — the FAQ overlay (see main_activity.xml):
        // the close button hides it, and so does the system back
        // button while it is open.
        view.findViewById<View>(R.id.minab_faq_close)?.setOnClickListener { hideFaq() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!hideFaq()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** Minab Beta v0.5 — show the FAQ full-screen over the tabs.
        Called from the FAQ row at the end of Settings. */
    fun showFaq() {
        val container = findViewById<View>(R.id.minab_faq_container) ?: return
        if (container.visibility == View.VISIBLE) return
        if (supportFragmentManager.findFragmentById(R.id.minab_faq_content) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.minab_faq_content, FaqFragment())
                .commit()
        }
        container.visibility = View.VISIBLE
    }

    /** Hide the FAQ overlay. Returns true when it was open. */
    fun hideFaq(): Boolean {
        val container = findViewById<View>(R.id.minab_faq_container) ?: return false
        if (container.visibility != View.VISIBLE) return false
        container.visibility = View.GONE
        return true
    }

    /** Index of the Settings tab (Home 0, Configs 1, Graph 2,
        Settings 3 — unless the crash-dump tab is present, which is
        why this is looked up instead of hard-coded). */
    private fun settingsTabIndex(): Int =
        mPagerAdapter.indexOfFragment(GeneralSettings::class.java)


    private fun disableToolbarElevation() {
        supportActionBar?.elevation = 0f
    }

    override fun onResume() {
        super.onResume()
        // Minab Beta v0.9 — failover + widget refresh. The listener
        // only lives while the app is in the foreground (starting a
        // VPN from the background is blocked by Android), so
        // failover only runs while the app is open.
        VpnStatus.addStateListener(minabStateListener)
        MinabWidgetProvider.updateAllWidgets(this)
        val intent = intent
        if (intent != null) {
            val action = intent.action
            if (Intent.ACTION_VIEW == action) {
                val uri = intent.data
                uri?.let { checkUriForProfileImport(it) }
            }
            val page = intent.getStringExtra("PAGE")
            if ("graph" == page) {
                // Minab v0.4: Graph moved to index 2 (Home 0,
                // Configs 1, Graph 2) when the Configs tab was added.
                mPager.currentItem = 2
            }
            setIntent(null)
        }
    }

    override fun onPause() {
        VpnStatus.removeStateListener(minabStateListener)
        super.onPause()
    }

    /** Minab Beta v0.9 — connection-state listener for failover
        (switch to the next healthy server when the connected one
        drops) and for refreshing the home-screen widget. */
    private val minabStateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?
        ) {
            MinabFailover.onStateChanged(this@MainActivity, level)
            MinabWidgetProvider.updateAllWidgets(this@MainActivity)
        }

        override fun setConnectedVPN(uuid: String?) {}
    }

    private fun checkUriForProfileImport(uri: Uri) {
        if ("openvpn" == uri.scheme && "import-profile" == uri.host) {
            var realUrl = uri.encodedPath + "?" + uri.encodedQuery
            if (!realUrl.startsWith("/https://")) {
                Toast.makeText(
                    this,
                    "Cannot use openvpn://import-profile/ URL that does not use https://",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            realUrl = realUrl.substring(1)
            startOpenVPNUrlImport(realUrl)
        }
    }

    private fun startOpenVPNUrlImport(url: String) {
        val asImportFrag = newInstance(url)
        asImportFrag.show(supportFragmentManager, "dialog")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!GlobalPreferences.getMinimalUi())
            menuInflater.inflate(R.menu.main_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.show_log) {
            val showLog = Intent(this, LogWindow::class.java)
            startActivity(showLog)
            return true
        }
        // Minab Beta v0.5 — the top-bar actions are constant on
        // every tab (they live in the activity menu). Bulk import
        // opens the bulk importer; the gear jumps to the Settings
        // tab. (The "+" add-one-config item was removed in v0.9:
        // it opened the upstream "create an EMPTY profile by hand"
        // dialog, not an import, and it closed the app.)
        if (item.itemId == R.id.minab_top_bulk_import) {
            startActivity(Intent(this, BulkImportActivity::class.java))
            return true
        }
        if (item.itemId == R.id.minab_top_settings) {
            val index = settingsTabIndex()
            if (index >= 0) mPager.currentItem = index
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}