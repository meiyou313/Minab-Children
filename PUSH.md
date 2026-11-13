# PUSH.md — publish Minab Beta v0.4

Local repo is on branch `minab-beta-0.4`, based on the published
v0.3 tree (`minab-beta-0.3`, HEAD `9204713`). On the machine this was
prepared on there is **no Java / Android SDK**, so the build must be
done by GitHub Actions (see §3), and the push needs a logged-in
machine (or the GitHub web UI + a local clone).

## 0. Signing secrets (do this BEFORE pushing)

v0.4 is signed with one permanent keystore (RSA 2048, valid to 2056,
alias `minab`) that lives OUTSIDE the repo at
`~/workspace/minab-keystore/minab-release.p12`. The workflow FAILS
LOUDLY if the secrets are missing, so set all four in the repo
Settings > Secrets and variables > Actions first:

- `MINAB_KEYSTORE_BASE64` — the whole contents of
  `~/workspace/minab-keystore/minab-release.p12.b64`
- `MINAB_KEYSTORE_PASSWORD` — from `~/workspace/minab-keystore/passwords.txt`
- `MINAB_KEY_ALIAS` — `minab`
- `MINAB_KEY_PASSWORD` — from `~/workspace/minab-keystore/passwords.txt`

## 1. Create the PUBLIC repo (do not make it private)

GPLv2 requires the source to be published, and the user approved a
public repo.

Web UI:
1. Go to https://github.com/new
2. Owner: `meiyou313`, Repository name: `minab`
3. Description: `Minab Children — Android OpenVPN client with bulk import, categories and bulk ping (based on ics-openvpn, GPLv2)`
4. Visibility: **Public**
5. Do NOT tick "Add a README / .gitignore / license" (the local repo
   already has them; ticking them would create a conflicting first commit).
6. Create repository.

Or with `gh` (logged in as meiyou313):

```sh
gh repo create meiyou313/Minab-Children-OpenVPN --public \
  --description "Minab Children — Android OpenVPN client with bulk import, categories and bulk ping (based on ics-openvpn, GPLv2)"
```

## 2. Push the local branch

From `~/workspace/minab/`:

```sh
cd ~/workspace/minab
git push minab minab-beta-0.4
```

The CI workflow triggers on pushes to `minab-beta-0.4` (and the
older beta branches, `master` and `main`), so pushing the branch is
enough. (Upstream ics-openvpn uses `master`.)

## 3. Trigger the beta build

Pushing the branch already runs `.github/workflows/build-android.yml`
("Minab Android (Beta v0.4)"): checkout with submodules, JDK 17
Temurin, Android SDK, `./gradlew assembleUiOvpn23Debug`.

Watch it at: https://github.com/meiyou313/Minab-Children-OpenVPN/actions

- Green → download the APK from the run's artifacts
  (`minab-beta-v0.4-debug-apk`).
- Red → open the failed step, read the exact error, and fix it in
  `~/workspace/minab/` (most likely suspects, in order: missing
  signing secrets (see §0 — the workflow fails loudly on purpose),
  the `assembleUiOvpn23Debug` task name, the JDK version — upstream
  used JDK 25, this workflow uses 17; if Gradle/AGP complains about
  the JDK, bump `java-version` to 21 in
  `.github/workflows/build-android.yml` — or the NDK download).

The upstream workflow (`.github/workflows/build.yaml`) also runs on
every push; it is expected to run too and is not the Minab release
path. Do not "fix" it by deleting it without a reason.

## 4. Publish the release (tag v0.4)

Only after the branch build is green:

```sh
cd ~/workspace/minab
git tag -a v0.4 -m "Minab Beta v0.4"
git push minab v0.4
```

The same workflow then publishes the GitHub Release
**"Minab Beta v0.4"** (marked pre-release) with the debug APK
attached: https://github.com/meiyou313/Minab-Children-OpenVPN/releases/tag/v0.4

## 5. What to verify on the phone (before calling it done)

The APK was **not** built or tested locally — no SDK here. Verify on
a real Android phone, in this order:

1. **ONE-TIME UNINSTALL:** v0.4 cannot install over v0.1–v0.3
   (new permanent signing key) — uninstall the old beta once,
   then install v0.4. From v0.4 onward versions install over each
   other. Profiles in the old app are lost on uninstall; keep the
   `.ovpn` files to re-import.
2. App opens, Home shows the smaller PowerOrb, the two visible
   buttons (**Bulk import**, **Test ping**) fully on screen with
   standard icons, and the group tabs (All + one per group + "+")
   with a small ping icon next to each group name. No huge
   notification banner: the permission is asked at first launch,
   and if declined only a minimal one-line banner shows.
3. Tap a group's ping icon → only that group's profiles are pinged.
4. Tap the connect button → the log page does NOT open; the app
   stays on Home while connecting.
5. The tab bar is at the BOTTOM (Home, Configs, Graph, Settings,
   FAQ, About), smaller and minimal.
6. CONFIGS tab: tap a config → rename / credentials / category /
   ping / delete all work; "Credentials for this group" and
   "Credentials for all" apply a username/password; "Back up
   configs" exports a zip.
7. Settings: "Show speed in notification" is ON by default; after
   connecting, the notification shows download/upload speed.
   "Check for updates" works; Home and About show the update
   notice when a newer release exists (or "up to date").
8. Bulk import: tap the **Bulk import** button → pick 3+ `.ovpn`
   files at once → summary shows `Imported: 3…`; pick the same files
   again → `duplicates skipped: 3`.
9. One username/password + "Apply to all": import a profile that
   needs auth-user-pass, then connect — it must not ask for the
   password again per profile.
10. Long-press a profile → Assign category → pick/create one; tapping
    that group's tab shows only that group; the **Manage** chip
    opens rename/delete.
11. Tap **Test ping** → the progress row shows "Pinging N profiles…
    X done" with a working **Cancel**; each tested row gets a coloured
    pill badge (`42 ms` / `Timeout` / `Failed`), list re-sorts
    fastest-first when done.
12. Connect one profile (stock flow) → VPN connects and disconnects
    normally. This is the regression check for the stock flow.

## 6. Signing (v0.4+)

v0.4+ are all signed with the ONE permanent keystore created for
v0.4 (RSA 2048, valid to 2056, alias `minab`), stored OUTSIDE the
repo at `~/workspace/minab-keystore/` (`.p12` + backup copy +
`.p12.b64` + `passwords.txt`). It is wired into the CI build via
the four secrets in §0. Never commit the keystore, its base64, or
the passwords, and do NOT reuse Namaki's or any other app's keystore
for Minab. The first non-beta (1.0.0) will use this same keystore,
so it will install over the v0.4+ betas without an uninstall.
