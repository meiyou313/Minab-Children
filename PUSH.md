# PUSH.md — publish Minab Beta v0.1

Local repo is ready at `~/workspace/minab/` on branch `minab-beta-0.1`,
based on ics-openvpn master `660901ce`. Nothing has been pushed yet:
on the machine this was prepared on there is **no Java / Android SDK**
and **`gh` is not logged in**, so the push and the first build must be
done from a logged-in machine (or the GitHub web UI + a local clone).

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
gh repo create meiyou313/minab --public \
  --description "Minab Children — Android OpenVPN client with bulk import, categories and bulk ping (based on ics-openvpn, GPLv2)"
```

## 2. Push the local branch

From `~/workspace/minab/`:

```sh
cd ~/workspace/minab
git remote add origin https://github.com/meiyou313/minab.git
git push -u origin minab-beta-0.1
```

Then either rename the branch to `master` on GitHub, or keep
`minab-beta-0.1` as the default branch for the beta — the CI workflow
triggers on pushes to `minab-beta-0.1`, `master` and `main`, so either
works. (Upstream ics-openvpn uses `master`.)

## 3. Trigger the beta build

Pushing the branch already runs `.github/workflows/build-android.yml`
("Minab Android (Beta v0.1)"): checkout with submodules, JDK 17
Temurin, Android SDK, `./gradlew assembleUiOvpn23Debug`.

Watch it at: https://github.com/meiyou313/minab/actions

- Green → download the APK from the run's artifacts
  (`minab-beta-v0.1-debug-apk`).
- Red → open the failed step, read the exact error, and fix it in
  `~/workspace/minab/` (most likely suspects, in order: the
  `assembleUiOvpn23Debug` task name, the JDK version — upstream used
  JDK 25, this workflow uses 17 per the brief; if Gradle/AGP
  complains about the JDK, bump `java-version` to 21 in
  `.github/workflows/build-android.yml` — or the NDK download).

The upstream workflow (`.github/workflows/build.yaml`) also runs on
every push; it is expected to run too and is not the Minab release
path. Do not "fix" it by deleting it without a reason.

## 4. Publish the release (tag v0.1)

Only after the branch build is green:

```sh
cd ~/workspace/minab
git tag -a v0.1 -m "Minab Beta v0.1"
git push origin v0.1
```

The same workflow then publishes the GitHub Release
**"Minab Beta v0.1"** (marked pre-release) with the debug APK
attached: https://github.com/meiyou313/minab/releases/tag/v0.1

## 5. What to verify on the phone (before calling it done)

The APK was **not** built or tested locally — no SDK here. Verify on
a real Android phone, in this order:

1. Installs (debug-signed; it will NOT install over any other app
   with the same package — there is none, package is new).
2. App opens, shows the (empty) profile list with the category bar.
3. Bulk import: pick 3+ `.ovpn` files at once → summary shows
   `Imported: 3…`; pick the same files again → `duplicates skipped: 3`.
4. One username/password + "Apply to all": import a profile that
   needs auth-user-pass, then connect — it must not ask for the
   password again per profile.
5. Long-press a profile → Assign category → pick/create one; filter
   bar shows only that category.
6. Menu → "Test ping: all profiles" → each row gets `ms` / `Timeout` /
   `Failed`, list re-sorts fastest-first, Cancel works.
7. Connect one profile (stock flow) → VPN connects and disconnects
   normally. This is the regression check for the stock flow.

## 6. Before the first non-beta (1.0.0) — signing

Create a **new, permanent** release keystore for Minab only (do not
reuse Namaki's or any other app's), store it as GitHub secrets, and
wire it into the release build. Installing 1.0.0 over this debug
beta will need a one-time uninstall — that is expected and must be
told to users in the 1.0.0 release notes.
