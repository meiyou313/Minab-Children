/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * BulkImportActivity: bulk import of .ovpn/.conf files and .zip files.
 *
 * Flow:
 *   1. User picks many files at once (ACTION_OPEN_DOCUMENT +
 *      EXTRA_ALLOW_MULTIPLE, MIME application/x-openvpn-profile and
 *      any MIME type, so file managers that do not know the .ovpn MIME
 *      type can still pick them) or a single .zip.
 *   2. One username + one password + "apply to all" checkbox. When
 *      checked, BulkImportHelper writes them into VpnProfile.mUsername /
 *      mPassword of every imported profile that uses auth-user-pass —
 *      the same fields the profile editor writes, persisted by
 *      ProfileManager into the (optionally encrypted) profile file.
 *      The Activity never stores the password anywhere else and clears
 *      the fields when it finishes.
 *   3. Import runs on a background thread (Dispatchers.IO); the UI
 *      shows live progress ("Imported X of N…" + progress bar) and
 *      then the summary (imported / duplicates skipped / failed,
 *      with failed names). The summary is stored in
 *      ImportSummaryStore and shown again when the screen re-opens
 *      (v0.3 bug fix 1 — in v0.2 it was only a transient Toast).
 *   4. Auto-categorisation runs AFTER the import, also in the
 *      background, in two batched passes with no per-profile delay
 *      (v0.3 bug fix 1 — v0.2 paused ~1 s per profile): first the
 *      offline heuristic for every profile at once, then the online
 *      lookup for the rest, in chunks of 8 in parallel. It never
 *      blocks or fails the import: a profile keeps its manual
 *      category when no country could be determined.
 *   5. Shared credentials (v0.3 bug fix 2): the Apply button saves
 *      the username/password (EncryptedSharedPreferences, see
 *      SharedCredentials) and writes them into ALL profiles that
 *      need a username/password — already-imported ones included —
 *      so connecting stops asking per profile. Profiles imported
 *      later get the saved credentials automatically, without
 *      re-typing. The fields are pre-filled from the saved values.
 */

package de.blinkt.openvpn.activities

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.minab.AutoCategory
import de.blinkt.openvpn.minab.BulkImportHelper
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.ImportSummaryStore
import de.blinkt.openvpn.minab.MinabBackup
import de.blinkt.openvpn.minab.SharedCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class BulkImportActivity : BaseActivity() {
    companion object {
        /** Cap for one downloaded or picked zip: MinabBackup uses the
            same 64 MB cap, so a backup picked here is never refused
            there for size. */
        private const val MAX_ZIP_BYTES = 64 * 1024 * 1024
    }

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var applyAllBox: CheckBox
    private lateinit var autoCategoryBox: CheckBox
    private lateinit var categorySpinner: Spinner
    private lateinit var statusView: TextView
    private lateinit var lastSummaryView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var importButton: Button
    private lateinit var zipButton: Button
    private lateinit var applyButton: Button
    // --- Minab v0.9: import from a link + restore a full backup ---
    private lateinit var urlField: EditText
    private lateinit var fetchButton: Button
    private lateinit var restoreButton: Button
    /** Set while a run restores a full backup: the restore summary
        replaces the import summary at the end of the run. */
    private var restoreSummary: String? = null

    private var selectedCategory: String = CategoryStore.DEFAULT_CATEGORY
    /** Files the user picked for the import currently running (or 0). */
    private var selectedCount: Int = 0

    private val pickMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onFilesPicked(uris) }

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onZipPicked(uri) }

    private val pickRestore = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onRestorePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bulk_import)

        usernameField = findViewById(R.id.minab_bulk_username)
        passwordField = findViewById(R.id.minab_bulk_password)
        applyAllBox = findViewById(R.id.minab_bulk_apply_all)
        autoCategoryBox = findViewById(R.id.minab_bulk_auto_category)
        categorySpinner = findViewById(R.id.minab_bulk_category)
        statusView = findViewById(R.id.minab_bulk_status)
        lastSummaryView = findViewById(R.id.minab_bulk_last_summary)
        progressBar = findViewById(R.id.minab_bulk_progress)
        importButton = findViewById(R.id.minab_bulk_pick)
        zipButton = findViewById(R.id.minab_bulk_pick_zip)
        applyButton = findViewById(R.id.minab_bulk_apply_credentials)
        urlField = findViewById(R.id.minab_bulk_url)
        fetchButton = findViewById(R.id.minab_bulk_fetch_url)
        restoreButton = findViewById(R.id.minab_bulk_restore)

        // Minab Beta v0.9 — edge-to-edge: this activity never
        // applied the system-bars insets, so its content started
        // under the status bar / action bar and the header text was
        // clipped (v0.8 screenshot). Push the ScrollView down by the
        // top inset and pad its bottom by the navigation-bar inset,
        // so the spinner at the end scrolls fully into view.
        // (BaseActivity.setUpEdgeEdgeInsetsListener is not usable
        // here — it assumes a statusbar_background view exists.)
        val root = findViewById<View>(R.id.minab_bulk_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }

        // v0.3 bug fix 2: pre-fill the saved shared credentials, so
        // the user sees what is stored and can Apply again or edit.
        if (SharedCredentials.hasCredentials(this)) {
            usernameField.setText(SharedCredentials.getUsername(this))
            passwordField.setText(SharedCredentials.getPassword(this))
        }

        // v0.3 bug fix 1: the last import summary survives leaving
        // the page — show it again on return.
        ImportSummaryStore.formatLastSummary(this)?.let { summary ->
            lastSummaryView.text = getString(R.string.minab_last_import, summary)
        }

        val categories = CategoryStore.getCategories(this).toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter
        categorySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedCategory = categories[pos]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        importButton.setOnClickListener {
            pickMultiple.launch(arrayOf("application/x-openvpn-profile", "application/octet-stream", "*/*"))
        }
        zipButton.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        applyButton.setOnClickListener { applySharedCredentials() }

        // Minab Beta v0.9 — import from a link: paste a URL to a
        // .ovpn file or a .zip of .ovpn files and fetch it.
        fetchButton.setOnClickListener { onFetchUrlClicked() }

        // Minab Beta v0.9 — restore a full Minab backup (configs +
        // groups + credentials + favorites + settings).
        restoreButton.setOnClickListener {
            pickRestore.launch(arrayOf("application/zip", "*/*"))
        }
    }

    override fun onDestroy() {
        // Do not keep the bulk password in memory longer than needed.
        if (::passwordField.isInitialized) passwordField.setText("")
        super.onDestroy()
    }

    /**
     * v0.3 bug fix 2 — the real Apply button. Saves the shared
     * credentials and writes them into ALL profiles that need a
     * username/password (already-imported ones included), so
     * connecting no longer asks per profile. Runs off the UI thread
     * because it saves every matching profile to disk.
     */
    private fun applySharedCredentials() {
        val username = usernameField.text.toString()
        val password = passwordField.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.minab_credentials_empty, Toast.LENGTH_SHORT).show()
            return
        }
        applyButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            SharedCredentials.save(this@BulkImportActivity, username, password)
            val count = SharedCredentials.applyToAllProfiles(
                this@BulkImportActivity, username, password)
            withContext(Dispatchers.Main) {
                applyButton.isEnabled = true
                val message = if (count > 0) {
                    getString(R.string.minab_credentials_applied, count)
                } else {
                    getString(R.string.minab_credentials_none_applied)
                }
                statusView.text = message
                Toast.makeText(this@BulkImportActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun options(): BulkImportHelper.ImportOptions {
        val o = BulkImportHelper.ImportOptions()
        val username = usernameField.text.toString()
        val password = passwordField.text.toString()
        if (username.isNotEmpty() && password.isNotEmpty()) {
            o.username = username
            o.password = password
            o.applyCredentialsToAll = applyAllBox.isChecked
            // Remember them, so profiles imported later get the same
            // credentials without re-typing (v0.3 bug fix 2).
            if (o.applyCredentialsToAll) SharedCredentials.save(this, username, password)
        } else if (SharedCredentials.hasCredentials(this)) {
            // Nothing typed now, but shared credentials were saved
            // before (Apply or an earlier import): use them for the
            // profiles imported in this batch.
            o.username = SharedCredentials.getUsername(this)
            o.password = SharedCredentials.getPassword(this)
            o.applyCredentialsToAll = true
        }
        o.category = selectedCategory
        return o
    }

    private fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.minab_no_files_picked, Toast.LENGTH_SHORT).show()
            return
        }
        selectedCount = uris.size
        runImport { result, opts ->
            var done = 0
            for (uri in uris) {
                val name = displayName(uri)
                var stream: InputStream? = null
                try {
                    stream = contentResolver.openInputStream(uri)
                    if (stream != null) {
                        if (isZipName(name)) {
                            // Minab Beta v0.9 — read the zip into
                            // memory first: a full Minab backup is
                            // detected by its metadata and restored
                            // (groups + credentials + favorites +
                            // settings), anything else is a plain
                            // import as before.
                            val bytes = readAllBytes(stream, MAX_ZIP_BYTES)
                            if (bytes == null) {
                                result.failed++
                                result.failedNames.add(name ?: uri.toString())
                            } else {
                                importZipBytes(bytes, opts, result)
                            }
                        } else {
                            BulkImportHelper.importOne(this, stream, name, opts, result)
                        }
                    } else {
                        result.failed++
                        result.failedNames.add(name ?: uri.toString())
                    }
                } catch (e: Exception) {
                    result.failed++
                    result.failedNames.add(name ?: uri.toString())
                } finally {
                    try { stream?.close() } catch (ignored: Exception) { }
                }
                done++
                // v0.3 bug fix 1: live progress, not a static line.
                withContext(Dispatchers.Main) { updateProgress(done, uris.size) }
                if (result.imported + result.failed > BulkImportHelper.MAX_FILES_PER_IMPORT) break
            }
            result
        }
    }

    private fun onZipPicked(uri: Uri) {
        selectedCount = 0 // total unknown until the zip is read
        runImport { result, opts ->
            var stream: InputStream? = null
            try {
                stream = contentResolver.openInputStream(uri)
                if (stream == null) {
                    result.failed++
                    result.failedNames.add(displayName(uri) ?: uri.toString())
                } else {
                    // Minab Beta v0.9 — read the zip into memory
                    // first (see onFilesPicked): a full Minab backup
                    // is restored, anything else is a plain import.
                    val bytes = readAllBytes(stream, MAX_ZIP_BYTES)
                    if (bytes == null) {
                        result.failed++
                        result.failedNames.add(displayName(uri) ?: uri.toString())
                    } else {
                        importZipBytes(bytes, opts, result) { processed ->
                            // Zip total is unknown: indeterminate bar
                            // plus a running count (v0.3 bug fix 1).
                            runOnUiThread {
                                progressBar.isIndeterminate = true
                                statusView.text =
                                    getString(R.string.minab_importing) + " (" + processed + ")"
                            }
                        }
                    }
                }
            } finally {
                try { stream?.close() } catch (ignored: Exception) { }
            }
            result
        }
    }

    /** Live progress: bar + "Imported X of N…" (main thread only). */
    private fun updateProgress(done: Int, total: Int) {
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = total.coerceAtLeast(1)
        progressBar.progress = done
        statusView.text = getString(R.string.minab_import_progress, done, total)
    }

    /** Runs the import off the UI thread, then auto-categorises, then shows the summary. */
    private fun runImport(work: suspend (BulkImportHelper.ImportResult, BulkImportHelper.ImportOptions) -> BulkImportHelper.ImportResult) {
        setBusy(true)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        statusView.text = getString(R.string.minab_importing)
        // Capture the options on the UI thread once; the background work
        // must never read the EditText / CheckBox views itself.
        val opts = options()
        val autoCategorise = autoCategoryBox.isChecked
        if (opts.category != null) CategoryStore.addCategory(this, opts.category)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = BulkImportHelper.ImportResult()
            try {
                work(result, opts)
            } catch (e: Exception) {
                result.failed++
                result.failedNames.add(e.message ?: "import error")
            }
            // Auto-categorisation AFTER the import, best-effort, never fatal.
            // v0.3 bug fix 1: batched, no ~1 s pause per profile.
            // Pass 1 — offline heuristic for every profile at once
            // (country code in the name/hostname or the hostname TLD).
            // Pass 2 — online lookup only for the rest, in chunks of 8
            // in parallel (ip-api.com is rate-limited; a 429 just
            // leaves that profile uncategorised).
            if (autoCategorise && result.importedProfiles.isNotEmpty()) {
                val total = result.importedProfiles.size
                val needOnline = ArrayList<VpnProfile>()
                var done = 0
                for (profile in result.importedProfiles) {
                    try {
                        val offline = AutoCategory.guessOffline(
                            profile.mName, null, profile.primaryRemoteHost)
                        if (offline != null) {
                            profile.setCategory(offline)
                            CategoryStore.addCategory(this@BulkImportActivity, offline)
                            ProfileManager.saveProfile(this@BulkImportActivity, profile)
                        } else {
                            needOnline.add(profile)
                        }
                    } catch (ignored: Exception) {
                    }
                    done++
                    val shown = done
                    withContext(Dispatchers.Main) {
                        statusView.text = getString(
                            R.string.minab_import_categorising, shown, total)
                    }
                }
                for (chunk in needOnline.chunked(8)) {
                    val guessed = chunk.map { profile ->
                        async {
                            profile to try {
                                AutoCategory.guessCategory(
                                    this@BulkImportActivity,
                                    profile.mName, null, profile.primaryRemoteHost)
                            } catch (ignored: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                    for ((profile, category) in guessed) {
                        if (category != null) {
                            try {
                                profile.setCategory(category)
                                CategoryStore.addCategory(this@BulkImportActivity, category)
                                ProfileManager.saveProfile(this@BulkImportActivity, profile)
                            } catch (ignored: Exception) {
                            }
                        }
                        done++
                        val shown = done
                        withContext(Dispatchers.Main) {
                            statusView.text = getString(
                                R.string.minab_import_categorising, shown, total)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                setBusy(false)
                progressBar.visibility = View.GONE
                // Minab Beta v0.9 — a full backup was restored:
                // show the restore summary instead of the import one.
                val summary = restoreSummary ?: getString(
                    R.string.minab_import_summary,
                    result.imported, result.skippedDuplicates, result.failed
                )
                restoreSummary = null
                val extra = if (result.failedNames.isNotEmpty()) {
                    "\n" + result.failedNames.take(10).joinToString("\n")
                } else ""
                statusView.text = summary + extra
                // v0.3 bug fix 1: persist the summary (with the number
                // of files selected) so it is still there on return —
                // the Toast alone is gone as soon as the page is left.
                ImportSummaryStore.save(
                    this@BulkImportActivity, selectedCount,
                    result.imported, result.skippedDuplicates,
                    result.failed, result.failedNames)
                ImportSummaryStore.formatLastSummary(this@BulkImportActivity)?.let { stored ->
                    lastSummaryView.text = getString(R.string.minab_last_import, stored)
                }
                Toast.makeText(this@BulkImportActivity, summary, Toast.LENGTH_LONG).show()
                if (result.imported > 0) setResult(Activity.RESULT_OK)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        importButton.isEnabled = !busy
        zipButton.isEnabled = !busy
        applyButton.isEnabled = !busy
        fetchButton.isEnabled = !busy
        restoreButton.isEnabled = !busy
    }

    /**
     * Minab Beta v0.9 — one zip, two meanings. A zip that carries a
     * Minab backup (a minab-backup.json metadata entry) is
     * RESTORED: profiles plus groups, credentials, favorites and
     * settings, exactly as they were. Anything else is a plain
     * import, as before — and restored profiles are deliberately
     * NOT added to result.importedProfiles, so the auto-categoriser
     * in runImport does not re-group them (their groups come from
     * the backup's metadata).
     */
    private fun importZipBytes(
        bytes: ByteArray,
        opts: BulkImportHelper.ImportOptions,
        result: BulkImportHelper.ImportResult,
        onProgress: ((Int) -> Unit)? = null
    ) {
        if (MinabBackup.hasMetadata(bytes)) {
            val restore = MinabBackup.restoreBackup(this, bytes)
            result.imported += restore.imported
            result.skippedDuplicates += restore.skippedDuplicates
            result.failed += restore.failed
            if (restore.error != null) result.failedNames.add(restore.error!!)
            if (restore.wasBackup) {
                restoreSummary = getString(R.string.minab_restore_done, restore.profilesRestored)
            }
            return
        }
        val listener = if (onProgress == null) null else
            BulkImportHelper.ProgressListener { processed -> onProgress(processed) }
        val zipResult = BulkImportHelper.importZip(this, ByteArrayInputStream(bytes), opts, listener)
        result.imported += zipResult.imported
        result.skippedDuplicates += zipResult.skippedDuplicates
        result.failed += zipResult.failed
        result.failedNames.addAll(zipResult.failedNames)
        result.importedProfiles.addAll(zipResult.importedProfiles)
    }

    /** Read a stream fully, capped — null when it is too large. */
    private fun readAllBytes(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n == -1) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Minab Beta v0.9 — import from a link: download the URL, then
        import it like a file picked by hand. A zip goes through the
        same backup-or-plain routing as a picked zip; anything else
        is treated as one .ovpn file (the parser rejects an HTML
        error page as a failure, so a wrong link shows as failed
        instead of importing garbage). */
    private fun onFetchUrlClicked() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.minab_link_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.minab_link_failed, Toast.LENGTH_SHORT).show()
            return
        }
        selectedCount = 0
        runImport { result, opts ->
            val bytes = downloadUrl(url)
            if (bytes == null) {
                result.failed++
                result.failedNames.add(url)
            } else if (isZipBytes(bytes)) {
                importZipBytes(bytes, opts, result)
            } else {
                BulkImportHelper.importOne(this, ByteArrayInputStream(bytes), urlName(url), opts, result)
            }
            result
        }
    }

    /** Download a URL, capped at MAX_ZIP_BYTES, following redirects.
        Null on any failure (bad URL, HTTP error, timeout, too big). */
    private fun downloadUrl(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Minab")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            return readAllBytes(conn.inputStream, MAX_ZIP_BYTES)
        } catch (e: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (ignored: Exception) { }
        }
    }

    private fun isZipBytes(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** A file name for a downloaded single config: the URL's last
        segment, or "link.ovpn" when it has none. */
    private fun urlName(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return if (last.isEmpty()) "link.ovpn" else last
    }

    /** Minab Beta v0.9 — restore a full Minab backup picked by hand.
        A zip without backup metadata is NOT imported here: the user
        asked for a restore, so they are told plainly that this file
        is not a Minab backup (plain import is the two buttons
        above). */
    private fun onRestorePicked(uri: Uri) {
        setBusy(true)
        statusView.text = getString(R.string.minab_importing)
        lifecycleScope.launch(Dispatchers.IO) {
            var message: String
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { readAllBytes(it, MAX_ZIP_BYTES) }
                when {
                    bytes == null -> message = getString(R.string.minab_restore_failed)
                    !MinabBackup.hasMetadata(bytes) -> message = getString(R.string.minab_restore_no_metadata)
                    else -> {
                        val restore = MinabBackup.restoreBackup(this@BulkImportActivity, bytes)
                        message = if (restore.error != null) getString(R.string.minab_restore_failed)
                            else getString(R.string.minab_restore_done, restore.profilesRestored)
                        if (restore.imported > 0) setResult(Activity.RESULT_OK)
                    }
                }
            } catch (e: Exception) {
                message = getString(R.string.minab_restore_failed)
            }
            withContext(Dispatchers.Main) {
                setBusy(false)
                statusView.text = message
                Toast.makeText(this@BulkImportActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) return cursor.getString(idx)
            }
        } catch (ignored: Exception) {
        } finally {
            try { cursor?.close() } catch (ignored: Exception) { }
        }
        return uri.lastPathSegment
    }

    private fun isZipName(name: String?): Boolean {
        return name != null && name.lowercase().endsWith(".zip")
    }
}
