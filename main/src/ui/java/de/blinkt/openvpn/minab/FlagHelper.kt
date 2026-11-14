/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * FlagHelper (v0.5): maps a config's country category (the English
 * country name produced by AutoCategory, e.g. "Germany", or a
 * 2-letter code) to one of the 36 bundled flag drawables. Only the
 * 30-40 most-used VPN/server countries are bundled, as tiny
 * hand-drawn vectors (~25 KB for the whole set). Unknown countries
 * return the generic globe drawable; categories that are not
 * countries at all return 0 (no flag shown). Never throws.
 *
 * v0.9.1: flagResForProfile (fix #6 / #13b) — every server gets a
 * flag, even an Uncategorized one, by also looking at the flag
 * emoji in the config name and at country names / codes in the
 * name and server host, with the globe as the fallback. It never
 * returns 0.
 */

package de.blinkt.openvpn.minab

import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import java.util.Locale

object FlagHelper {
    /** Country name (lower-case) or code -> flag drawable. */
    private val byName: Map<String, Int> = mapOf(
        "united states" to R.drawable.flag_us,
        "united states of america" to R.drawable.flag_us,
        "usa" to R.drawable.flag_us,
        "us" to R.drawable.flag_us,
        "united kingdom" to R.drawable.flag_gb,
        "great britain" to R.drawable.flag_gb,
        "uk" to R.drawable.flag_gb,
        "gb" to R.drawable.flag_gb,
        "england" to R.drawable.flag_gb,
        "germany" to R.drawable.flag_de,
        "de" to R.drawable.flag_de,
        "netherlands" to R.drawable.flag_nl,
        "holland" to R.drawable.flag_nl,
        "nl" to R.drawable.flag_nl,
        "france" to R.drawable.flag_fr,
        "fr" to R.drawable.flag_fr,
        "canada" to R.drawable.flag_ca,
        "ca" to R.drawable.flag_ca,
        "japan" to R.drawable.flag_jp,
        "jp" to R.drawable.flag_jp,
        "singapore" to R.drawable.flag_sg,
        "sg" to R.drawable.flag_sg,
        "south korea" to R.drawable.flag_kr,
        "korea" to R.drawable.flag_kr,
        "kr" to R.drawable.flag_kr,
        "australia" to R.drawable.flag_au,
        "au" to R.drawable.flag_au,
        "switzerland" to R.drawable.flag_ch,
        "ch" to R.drawable.flag_ch,
        "sweden" to R.drawable.flag_se,
        "se" to R.drawable.flag_se,
        "norway" to R.drawable.flag_no,
        "no" to R.drawable.flag_no,
        "finland" to R.drawable.flag_fi,
        "fi" to R.drawable.flag_fi,
        "denmark" to R.drawable.flag_dk,
        "dk" to R.drawable.flag_dk,
        "poland" to R.drawable.flag_pl,
        "pl" to R.drawable.flag_pl,
        "italy" to R.drawable.flag_it,
        "it" to R.drawable.flag_it,
        "spain" to R.drawable.flag_es,
        "es" to R.drawable.flag_es,
        "turkey" to R.drawable.flag_tr,
        "tr" to R.drawable.flag_tr,
        "turkiye" to R.drawable.flag_tr,
        "uae" to R.drawable.flag_ae,
        "united arab emirates" to R.drawable.flag_ae,
        "ae" to R.drawable.flag_ae,
        "india" to R.drawable.flag_in,
        "in" to R.drawable.flag_in,
        "brazil" to R.drawable.flag_br,
        "br" to R.drawable.flag_br,
        "mexico" to R.drawable.flag_mx,
        "mx" to R.drawable.flag_mx,
        "ukraine" to R.drawable.flag_ua,
        "ua" to R.drawable.flag_ua,
        "romania" to R.drawable.flag_ro,
        "ro" to R.drawable.flag_ro,
        "czechia" to R.drawable.flag_cz,
        "czech republic" to R.drawable.flag_cz,
        "cz" to R.drawable.flag_cz,
        "austria" to R.drawable.flag_at,
        "at" to R.drawable.flag_at,
        "belgium" to R.drawable.flag_be,
        "be" to R.drawable.flag_be,
        "ireland" to R.drawable.flag_ie,
        "ie" to R.drawable.flag_ie,
        "portugal" to R.drawable.flag_pt,
        "pt" to R.drawable.flag_pt,
        "greece" to R.drawable.flag_gr,
        "gr" to R.drawable.flag_gr,
        "israel" to R.drawable.flag_il,
        "il" to R.drawable.flag_il,
        "new zealand" to R.drawable.flag_nz,
        "nz" to R.drawable.flag_nz,
        "thailand" to R.drawable.flag_th,
        "th" to R.drawable.flag_th,
        "vietnam" to R.drawable.flag_vn,
        "vn" to R.drawable.flag_vn,
        "indonesia" to R.drawable.flag_id,
        "id" to R.drawable.flag_id
    )

    /**
     * Drawable for a category, or 0 when the category is not a
     * country at all (e.g. "Uncategorized" or a hand-made group).
     * Known countries without a bundled flag get the globe.
     */
    fun flagResForCategory(category: String?): Int {
        if (category.isNullOrBlank()) return 0
        val key = category.trim().lowercase(Locale.US)
        if (key == "uncategorized") return 0
        byName[key]?.let { return it }
        // A real country name we did not bundle a flag for gets the
        // generic globe; anything else (a hand-made group name) also
        // gets the globe rather than a wrong flag.
        return R.drawable.minab_flag_globe
    }

    /**
     * Minab Beta v0.9.1 — the flag for one profile, for the Home and
     * Configs rows (fix #6 / #13b). Every server gets a flag, never
     * nothing:
     *   1. the profile's category, when it is a known country
     *      ("Germany", "DE");
     *   2. a flag emoji inside the config name ("Finland 🇫🇮(TCP)")
     *      or the server host;
     *   3. a full country name or 2-letter code anywhere in the
     *      name or server host ("us-free…", "de-01.example.com");
     *   4. otherwise the generic globe — so the flag column is
     *      never empty and every row lines up.
     * Never returns 0.
     */
    fun flagResForProfile(profile: VpnProfile): Int {
        val category = profile.getCategory()
        if (!category.isNullOrBlank()) {
            val key = category.trim().lowercase(Locale.US)
            if (key != "uncategorized") {
                byName[key]?.let { return it }
            }
        }
        val name = profile.mName
        val host = try {
            profile.getPrimaryRemoteHost()
        } catch (e: Exception) {
            null
        }
        flagEmojiCode(name)?.let { byName[it]?.let { res -> return res } }
        flagEmojiCode(host)?.let { byName[it]?.let { res -> return res } }
        scanCountryToken(name)?.let { return it }
        scanCountryToken(host)?.let { return it }
        return R.drawable.minab_flag_globe
    }

    /** The 2-letter country code of the first flag emoji in [text], or null. */
    private fun flagEmojiCode(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (cp in 0x1F1E6..0x1F1FF) {
                // A flag is two regional indicators in a row.
                val j = i + Character.charCount(cp)
                if (j < text.length) {
                    val cp2 = Character.codePointAt(text, j)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        val a = 'A' + (cp - 0x1F1E6)
                        val b = 'A' + (cp2 - 0x1F1E6)
                        return "$a$b".lowercase(Locale.US)
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /** A country name or 2-letter code token inside [text], or null. */
    private fun scanCountryToken(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase(Locale.US)
        // Full country names first (a long name wins over a short code).
        for ((name, res) in byName) {
            if (name.length > 2 && lower.contains(name)) return res
        }
        // Then 2-letter codes as separate tokens (split on non-letters).
        for (token in lower.split(Regex("[^a-z]+"))) {
            if (token.length == 2) {
                byName[token]?.let { return it }
            }
        }
        return null
    }
}
