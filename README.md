# Minab Children — Minab Beta v0.1

Android-only OpenVPN client, based on **ics-openvpn** (OpenVPN for
Android by Arne Schwabe, GPLv2). This repository is **public on
purpose**: the base is GPLv2 and its author requires anyone building on
it to publish their source (see the upstream note below).

- App name: **Minab Children**
- Package (applicationId): `com.minab.children`
  (the internal Java package stays `de.blinkt.openvpn` — `VpnProfile`
  is Java-serialised and its class name must not change, or profiles
  saved by older versions would stop loading)
- Repo: `https://github.com/meiyou313/minab` (public)
- Version: **Minab Beta v0.1** — `versionName = "0.1-beta"`, `versionCode = 1`, tag `v0.1`
- Platform: **Android only** (minSdk 23). No Windows / Linux build.
- Base commit: `schwabe/ics-openvpn` master `660901ce320539aec476bd04bd1999c396d5e778`
- Completely separate from the Namaki / Mozilla-VPN project: no Namaki
  code, no Namaki signing key, no shared repo.

## What Minab adds on top of stock ics-openvpn

Stock ics-openvpn imports `.ovpn` files one by one and has no bulk
ping. Minab adds three features, without changing the stock
connect / disconnect flow:

### 1. Bulk import — many configs at once, one username/password for all
- Menu: **Bulk import (.ovpn / .zip)** → pick many `.ovpn` / `.conf`
  files at once (`ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE`) or
  one `.zip` containing them (up to 200 files, 2 MB each).
- One username field + one password field + **"Apply to all"**
  checkbox. When checked, the values are written into
  `VpnProfile.mUsername` / `mPassword` of every imported profile that
  uses `auth-user-pass` — exactly the fields the profile editor
  (`Settings_Basic`) writes, persisted by `ProfileManager` into the
  profile file (encrypted when upstream profile encryption is
  enabled). No password is stored in SharedPreferences or in any extra
  file, and the dialog clears the password field when it finishes.
- Duplicates are skipped, never overwritten: same profile **name +
  primary remote host:port** as an existing profile counts as a
  duplicate. The summary shows `Imported: X, duplicates skipped: Y,
  failed: Z` with the failed file names.

### 2. Categories — manual + automatic by country
- Every profile has a category (`VpnProfile.mCategory`, persisted
  with the profile; old profiles migrate to `"Uncategorized"` in
  `upgradeProfile()`, profile version 10 → 11, `serialVersionUID`
  unchanged so old saves still load).
- Manual (the path that always works): long-press a profile →
  **Assign category**; or the **Category** field in the profile editor
  (Basic settings); or the filter bar → **Manage categories**
  (create / rename / delete; deleting a category moves its profiles
  back to Uncategorized).
- Filter: tap the category bar at the top of the profile list, or menu
  → **Filter by category** (All + each category).
- Automatic (best effort, never blocks an import): on bulk import the
  app guesses the country from the remote host —
  1. offline heuristic: a 2-letter country code in the profile/file
     name or hostname (`us-01…`, `de.ovpn`), then the hostname TLD;
  2. online: DNS-resolve the host, then `https://ip-api.com/json/<ip>`
     (3 s timeout, result cached 30 days).
  Any failure leaves the manual category untouched. Honest limits:
  the free ip-api.com tier is rate-limited (~45 req/min per IP), so
  lookups run one at a time with a 1 s gap; offline or DNS failure
  means only the heuristic runs; a hostname can point at a CDN /
  anycast IP whose country is not the country in its name.

### 3. Bulk ping — test all profiles, or one category, at once
- Menu: **Test ping: all profiles** / **Test ping: this category**,
  or long-press one profile → **Test ping**.
- 8 threads in parallel. Per host: first ICMP via
  `/system/bin/ping -c 1 -W 2`, falling back to TCP-connect timing
  to the profile's remote host:port (3 s timeout) when ICMP gives no
  answer. Overall budget ~3 s per host.
- Each row shows live `42 ms` / `Timeout` / `Failed`; when finished
  the list is sorted fastest-first and the result (`mLastPingMs`,
  `mLastPingTime`, `mLastPingResult`) is saved on the profile and shown
  under its name. **Cancel** is available (tap the ping status bar).
- What the numbers mean: ICMP time is a real ping; the TCP fallback
  is a connect time, not an ICMP ping — it answers "is this server
  reachable and how fast does it answer", not a true round-trip.
  `Timeout` = silent on both; `Failed` = DNS failure / refused.
- VPN safety: pings use plain sockets and the ping binary from the
  app process only. They do not touch `VpnService`, do not call
  `protect()`, and cannot disconnect or break a running tunnel. While
  a VPN is connected the app warns that the numbers go through the
  tunnel and may not reflect direct latency.

## Build (GitHub Actions — no local SDK needed)

There is no Android SDK on the machine this was prepared on, so the
APK is built by CI:

- Workflow: `.github/workflows/build-android.yml` —
  `ubuntu-24.04`, JDK 17 (Temurin), Android SDK, then
  `./gradlew assembleUiOvpn23Debug`, APK uploaded as an artifact.
- Pushing tag `v0.1` publishes the GitHub Release **"Minab Beta v0.1"**
  (pre-release) with the debug APK attached.
- The upstream workflow (`.github/workflows/build.yaml`) is kept
  untouched and still runs.

## Signing — read before 1.0.0

**v0.1 is a debug-signed beta on purpose.** A permanent release
keystore must be created before the first non-beta (1.0.0), and 1.0.0
must be signed with it; installing 1.0.0 over this beta will then
need a **one-time uninstall** (different signing key), after which
later versions install over each other normally. Do NOT reuse any
other app's keystore (in particular not Namaki's) for Minab.

## Attribution & licence

- Base: ics-openvpn / OpenVPN for Android, Copyright (c) Arne
  Schwabe, GPLv2 — see `doc/LICENSE.txt` and the upstream README
  below. Minab's own additions are under the same GPLv2.
- `LICENSE` / `doc/LICENSE.txt` from upstream is kept unchanged.
- This app is a client only: it ships **no VPN servers and no
  accounts**. You bring your own `.ovpn` configs / server.

---

# Upstream README (ics-openvpn, preserved unchanged below)

OpenVPN for Android
=============
![build status](https://github.com/schwabe/ics-openvpn/actions/workflows/build.yaml/badge.svg)

Note to other developers 
------------------------
This is a spare time project that I work on my own time and pick to work what I want.
You are free use the source code of this app with the conditions (GPL) that are attached to it
but do not expect any support or anything that I do not feel like to provide. 

The goal of this project is about providing an open-source OpenVPN app for Android. It is 
NOT about creating a library to be used in other projects.

If you build something on top of this is app you MUST publish your source code according to
the license of this app (GPL).

This not personal against other developers or your software and projects. The reason that I am not 
helping or spending time to really into issues that are not part of this app itself  is that this 
is just a spare time project of mine. The number of apps that use my code is quite large and
the majority of them violates the license of my app. People create new apps that do not publish 
their source code.

I am just not willing to be the unpaid support for other people trying to make money of my code 
for free anymore. That is just not something I want to do in my spare time, so I tend to close
these tickets quite quickly. 

When the project started, I tried to help people but most people were just taking advantage of me 
and promises about open sourcing their app when they were finished were not fulfilled and 
I was just often ghosted when asking for the promises. Some people had even the audacity to 
come then back a year or two later and demand help with critical bug fixes when their app broke
with some newer Android versions. Over the time, I simply lost confidence in people that were 
hesitant to share their source code and play with open cards.

That being said, I am happy to work together with people that are have are reproducing bugs in
this app that they observed in their open-sourced fork to improve this app. 

Description
------------
With the new VPNService of Android API level 14+ (Ice Cream Sandwich) it is possible to create a VPN service that does not need root access. This project is a port of OpenVPN.

<a href="https://f-droid.org/repository/browse/?fdid=de.blinkt.openvpn" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80"/></a>
<a href="https://play.google.com/store/apps/details?id=de.blinkt.openvpn" target="_blank">
<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="80"/></a>

Code Transparency
-----------------
Fingerprint of the code transparency key used to be able to verify that
apks generated by the Play are not modified (https://developer.android.com/guide/app-bundle/code-transparency): 

    19 62 43 6C 96 B4 9D 12 75 83 B1 22 DA 14 F4 5D 2B 78 5D A4 13 1F 04 BE 73 A0 88 32 15 59 18 8D

Full signing certificate (also under misc/code-transparency.pem):

    -----BEGIN CERTIFICATE-----
    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
    -----END CERTIFICATE-----


Developing
---------------
If you want to develop on ics-openvpn please read the [doc/README.txt](https://github.com/schwabe/ics-openvpn/blob/master/doc/README.txt) *before* opening
issues or emailing me. 

Also, please note that before contributing to the project that I would like to retain 
my ability to relicense the project for different third parties and therefore probably 
need a contributor's agreement from any contributing party. To get started, 
[sign the Contributor License Agreement](doc/CLA.md).

You can help
------------
Even if you are no programmer you can help by translating the OpenVPN client into your native language. [Crowdin provides a free service for non commercial open source projects](https://crowdin.net/project/ics-openvpn/) (Fixing/completing existing translations is very welcome as well)

FAQ
-----
You can find the FAQ here (same as in app): https://ics-openvpn.blinkt.de/FAQ.html

Controlling from external apps
------------------------------

There is the AIDL API for real controlling (see developing section). Due to high demand also 
activities to start/stop, pause/resume (like a user would with the notification)  exists
  
 - `de.blinkt.openvpn.api.DisconnectVPN`
 - `de.blinkt.openvpn.api.ConnectVPN`
 - `de.blinkt.openvpn.api.PauseVPN`
 - `de.blinkt.openvpn.api.ResumeVPN`

They use `de.blinkt.openvpn.api.profileName` as extra for the name of the VPN profile.

You can use `adb` to test these intents:

    adb -d shell am start -a android.intent.action.MAIN -n de.blinkt.openvpn/.api.ConnectVPN --es de.blinkt.openvpn.api.profileName myvpnprofile


Note to administrators
------------------------

You make your life and that of your users easier if you embed the certificates into the .ovpn file. You or the users can mail the .ovpn as an attachment to the phone and directly import and use it. Also downloading and importing the file works. The MIME Type should be application/x-openvpn-profile. 

Inline files are supported since OpenVPN 2.1rc1 and documented in the  [OpenVPN man page](https://openvpn.net/community-docs/community-articles/openvpn-2-7-manual.html) (under INLINE FILE SUPPORT) 

(Using inline certificates can also make your life on non-Android platforms easier since you only have one file.)

For example `ca mycafile.pem` becomes
```
  <ca>
  -----BEGIN CERTIFICATE-----
  MIIHPTCCBSWgAwIBAgIBADANBgkqhkiG9w0BAQQFADB5MRAwDgYDVQQKEwdSb290
  [...]
  -----END CERTIFICATE-----
  </ca>
```

Minimal UI mode/Managed configuration
-------------------------------------
The app allows configuration via configuration that is provided by a central device management. See
the [app_restrictions.xml](main/src/main/res/xml/app_restrictions.xml) for the available options to 
configure app behaviour and configuration profiles.

One particular thing that can be enabled is the minimal mode that restricts the app to a single default
VPN Profile:

![Screenshot of minimal mode](misc/minimal_ui_mode.png)

Footnotes
-----------
Please note that OpenVPN used by this project is under GPLv2. 

If you cannot or do not want to use the Play Store you can [download the apk files directly](http://plai.de/android/).

If you want to donate you can donate to [arne-paypal@rfc2549.org via paypal](https://www.paypal.com/cgi-bin/webscr?hosted_button_id=R2M6ZP9AF25LS&cmd=_s-xclick), or alternatively if you believe in fancy Internet money you can use Bitcoin: 1EVWVqpVQFhoFE6gKaqSkfvSNdmLAjcQ9z 

The old official or main repository was a Mercurial (hg) repository at http://code.google.com/p/ics-openvpn/source/

The new Git repository is now at GitHub under https://github.com/schwabe/ics-openvpn

Please read the doc/README before asking questions or starting development.
