/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
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
 *   3. Import runs on a background thread (Dispatchers.IO); the UI only
 *      shows a progress line and then the summary
 *      (imported / duplicates skipped / failed, with failed names).
 *   4. Auto-categorisation runs AFTER the import, also in the
 *      background, one profile at a time with a ~1 s gap between online
 *      lookups (ip-api.com free tier is rate-limited). It never blocks
 *      or fails the import: a profile keeps its manual category when
 *      no country could be determined.
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.minab.AutoCategory
import de.blinkt.openvpn.minab.BulkImportHelper
import de.blinkt.openvpn.minab.CategoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private lateinit var importButton: Button
    private lateinit var zipButton: Button

    private var selectedCategory: String = CategoryStore.DEFAULT_CATEGORY

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
        importButton = findViewById(R.id.minab_bulk_pick)
        zipButton = findViewById(R.id.minab_bulk_pick_zip)

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
    }

    override fun onDestroy() {
        // Do not keep the bulk password in memory longer than needed.
        if (::passwordField.isInitialized) passwordField.setText("")
        super.onDestroy()
    }

    private fun options(): BulkImportHelper.ImportOptions {
        val o = BulkImportHelper.ImportOptions()
        o.applyCredentialsToAll = applyAllBox.isChecked
        o.username = usernameField.text.toString()
        o.password = passwordField.text.toString()
        // An empty password with "apply to all" would wipe per-profile
        // passwords, so treat "apply" as off when both fields are empty.
        if (o.username.isEmpty() && o.password.isEmpty()) o.applyCredentialsToAll = false
        o.category = selectedCategory
        return o
    }

    private fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.minab_no_files_picked, Toast.LENGTH_SHORT).show()
            return
        }
        runImport { result, opts ->
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
                if (result.imported + result.failed > BulkImportHelper.MAX_FILES_PER_IMPORT) break
            }
            result
        }
    }

    private fun onZipPicked(uri: Uri) {
        runImport { result, opts ->
            var stream: InputStream? = null
            try {
                stream = contentResolver.openInputStream(uri)
                if (stream == null) {
                    result.failed++
                    result.failedNames.add(displayName(uri) ?: uri.toString())
                } else {
                    val zipResult = BulkImportHelper.importZip(this, stream, opts)
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

    /** Runs the import off the UI thread, then auto-categorises, then shows the summary. */
    private fun runImport(work: (BulkImportHelper.ImportResult, BulkImportHelper.ImportOptions) -> BulkImportHelper.ImportResult) {
        setBusy(true)
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
            if (autoCategorise) {
                for (profile in result.importedProfiles) {
                    try {
                        val guessed = AutoCategory.guessCategory(
                            this@BulkImportActivity,
                            profile.mName, null, profile.primaryRemoteHost
                        )
                        if (guessed != null) {
                            profile.setCategory(guessed)
                            CategoryStore.addCategory(this@BulkImportActivity, guessed)
                            ProfileManager.saveProfile(this@BulkImportActivity, profile)
                        }
                        // Gentle pacing for the free ip-api.com tier.
                        delay(1000)
                    } catch (ignored: Exception) {
                    }
                }
            }
            withContext(Dispatchers.Main) {
                setBusy(false)
                val summary = getString(
                    R.string.minab_import_summary,
                    result.imported, result.skippedDuplicates, result.failed
                )
                val extra = if (result.failedNames.isNotEmpty()) {
                    "\n" + result.failedNames.take(10).joinToString("\n")
                } else ""
                statusView.text = summary + extra
                Toast.makeText(this@BulkImportActivity, summary, Toast.LENGTH_LONG).show()
                // Hand the bulk password back to nobody: clear the fields.
                passwordField.setText("")
                if (result.imported > 0) setResult(Activity.RESULT_OK)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        importButton.isEnabled = !busy
        zipButton.isEnabled = !busy
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
