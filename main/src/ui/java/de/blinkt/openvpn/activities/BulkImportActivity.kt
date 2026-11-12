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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.minab.AutoCategory
import de.blinkt.openvpn.minab.BulkImportHelper
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.ImportSummaryStore
import de.blinkt.openvpn.minab.SharedCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class BulkImportActivity : BaseActivity() {

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

    private var selectedCategory: String = CategoryStore.DEFAULT_CATEGORY
    /** Files the user picked for the import currently running (or 0). */
    private var selectedCount: Int = 0

    private val pickMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onFilesPicked(uris) }

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onZipPicked(uri) }

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
                            val zipResult = BulkImportHelper.importZip(this, stream, opts)
                            result.imported += zipResult.imported
                            result.skippedDuplicates += zipResult.skippedDuplicates
                            result.failed += zipResult.failed
                            result.failedNames.addAll(zipResult.failedNames)
                            result.importedProfiles.addAll(zipResult.importedProfiles)
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
                    val zipResult = BulkImportHelper.importZip(
                        this, stream, opts,
                        BulkImportHelper.ProgressListener { processed ->
                            // Zip total is unknown: indeterminate bar
                            // plus a running count (v0.3 bug fix 1).
                            runOnUiThread {
                                progressBar.isIndeterminate = true
                                statusView.text =
                                    getString(R.string.minab_importing) + " (" + processed + ")"
                            }
                        })
                    result.imported += zipResult.imported
                    result.skippedDuplicates += zipResult.skippedDuplicates
                    result.failed += zipResult.failed
                    result.failedNames.addAll(zipResult.failedNames)
                    result.importedProfiles.addAll(zipResult.importedProfiles)
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
                val summary = getString(
                    R.string.minab_import_summary,
                    result.imported, result.skippedDuplicates, result.failed
                )
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
