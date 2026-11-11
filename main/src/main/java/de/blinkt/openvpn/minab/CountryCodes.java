/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 * See README.md and doc/LICENSE.txt for the full terms.
 *
 * CountryCodes: ISO 3166-1 alpha-2 code -> English country name, for the
 * auto-categorisation feature. Only a small table is bundled on purpose:
 * it is used for offline heuristics (filename / hostname contains a
 * country code) and to turn a countryCode returned by ip-api.com into a
 * readable category name. Unknown codes fall back to the code itself.
 */

package de.blinkt.openvpn.minab;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CountryCodes {
    private CountryCodes() {
    }

    private static final Map<String, String> NAMES;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("us", "United States");
        m.put("gb", "United Kingdom");
        m.put("uk", "United Kingdom");
        m.put("de", "Germany");
        m.put("nl", "Netherlands");
        m.put("fr", "France");
        m.put("ca", "Canada");
        m.put("au", "Australia");
        m.put("jp", "Japan");
        m.put("kr", "South Korea");
        m.put("sg", "Singapore");
        m.put("in", "India");
        m.put("br", "Brazil");
        m.put("mx", "Mexico");
        m.put("es", "Spain");
        m.put("it", "Italy");
        m.put("se", "Sweden");
        m.put("no", "Norway");
        m.put("fi", "Finland");
        m.put("dk", "Denmark");
        m.put("pl", "Poland");
        m.put("cz", "Czechia");
        m.put("at", "Austria");
        m.put("ch", "Switzerland");
        m.put("be", "Belgium");
        m.put("ie", "Ireland");
        m.put("pt", "Portugal");
        m.put("gr", "Greece");
        m.put("tr", "Turkey");
        m.put("il", "Israel");
        m.put("ae", "UAE");
        m.put("sa", "Saudi Arabia");
        m.put("za", "South Africa");
        m.put("eg", "Egypt");
        m.put("ng", "Nigeria");
        m.put("ke", "Kenya");
        m.put("ru", "Russia");
        m.put("ua", "Ukraine");
        m.put("ro", "Romania");
        m.put("hu", "Hungary");
        m.put("hr", "Croatia");
        m.put("rs", "Serbia");
        m.put("bg", "Bulgaria");
        m.put("lt", "Lithuania");
        m.put("lv", "Latvia");
        m.put("ee", "Estonia");
        m.put("is", "Iceland");
        m.put("nz", "New Zealand");
        m.put("hk", "Hong Kong");
        m.put("tw", "Taiwan");
        m.put("th", "Thailand");
        m.put("vn", "Vietnam");
        m.put("my", "Malaysia");
        m.put("id", "Indonesia");
        m.put("ph", "Philippines");
        m.put("pk", "Pakistan");
        m.put("bd", "Bangladesh");
        m.put("lk", "Sri Lanka");
        m.put("np", "Nepal");
        m.put("kz", "Kazakhstan");
        m.put("uz", "Uzbekistan");
        m.put("ge", "Georgia");
        m.put("am", "Armenia");
        m.put("az", "Azerbaijan");
        m.put("ir", "Iran");
        m.put("iq", "Iraq");
        m.put("jo", "Jordan");
        m.put("lb", "Lebanon");
        m.put("qa", "Qatar");
        m.put("kw", "Kuwait");
        m.put("bh", "Bahrain");
        m.put("om", "Oman");
        m.put("ye", "Yemen");
        m.put("cl", "Chile");
        m.put("ar", "Argentina");
        m.put("co", "Colombia");
        m.put("pe", "Peru");
        m.put("ve", "Venezuela");
        m.put("uy", "Uruguay");
        m.put("py", "Paraguay");
        m.put("bo", "Bolivia");
        m.put("ec", "Ecuador");
        m.put("cr", "Costa Rica");
        m.put("pa", "Panama");
        m.put("do", "Dominican Republic");
        m.put("pr", "Puerto Rico");
        m.put("jm", "Jamaica");
        m.put("tt", "Trinidad and Tobago");
        m.put("bs", "Bahamas");
        m.put("bb", "Barbados");
        m.put("gt", "Guatemala");
        m.put("hn", "Honduras");
        m.put("sv", "El Salvador");
        m.put("ni", "Nicaragua");
        m.put("cu", "Cuba");
        m.put("ht", "Haiti");
        m.put("gy", "Guyana");
        m.put("sr", "Suriname");
        m.put("fk", "Falkland Islands");
        m.put("gl", "Greenland");
        m.put("fo", "Faroe Islands");
        m.put("lu", "Luxembourg");
        m.put("mc", "Monaco");
        m.put("mt", "Malta");
        m.put("cy", "Cyprus");
        m.put("al", "Albania");
        m.put("ba", "Bosnia and Herzegovina");
        m.put("mk", "North Macedonia");
        m.put("me", "Montenegro");
        m.put("xk", "Kosovo");
        m.put("md", "Moldova");
        m.put("by", "Belarus");
        m.put("sk", "Slovakia");
        m.put("si", "Slovenia");
        m.put("dz", "Algeria");
        m.put("ma", "Morocco");
        m.put("tn", "Tunisia");
        m.put("ly", "Libya");
        m.put("sd", "Sudan");
        m.put("et", "Ethiopia");
        m.put("gh", "Ghana");
        m.put("ci", "Ivory Coast");
        m.put("sn", "Senegal");
        m.put("cm", "Cameroon");
        m.put("ao", "Angola");
        m.put("zm", "Zambia");
        m.put("zw", "Zimbabwe");
        m.put("mz", "Mozambique");
        m.put("mg", "Madagascar");
        m.put("mu", "Mauritius");
        m.put("sc", "Seychelles");
        m.put("mv", "Maldives");
        m.put("bn", "Brunei");
        m.put("kh", "Cambodia");
        m.put("la", "Laos");
        m.put("mm", "Myanmar");
        m.put("mn", "Mongolia");
        m.put("kg", "Kyrgyzstan");
        m.put("tj", "Tajikistan");
        m.put("tm", "Turkmenistan");
        m.put("af", "Afghanistan");
        NAMES = Collections.unmodifiableMap(m);
    }

    /** Returns true when the lower-case code is a known country code. */
    public static boolean isKnownCode(String code) {
        return code != null && NAMES.containsKey(code.toLowerCase(Locale.US));
    }

    /** Human-readable name for a code, or the upper-case code itself if unknown. */
    public static String nameForCode(String code) {
        if (code == null || code.isEmpty()) return code;
        String name = NAMES.get(code.toLowerCase(Locale.US));
        return name != null ? name : code.toUpperCase(Locale.US);
    }

    /** All known codes, for tests / UI use. */
    public static java.util.Set<String> knownCodes() {
        return NAMES.keySet();
    }
}
