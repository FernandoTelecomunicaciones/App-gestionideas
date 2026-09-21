# AHORA — Release runbook

Companion to [DECISIONS.md](DECISIONS.md) (D-35 versioning + signing, D-36 validation tooling) and [STATUS.md](STATUS.md).
This file holds **procedures**; results live in STATUS.md. It contains no secret and must never get one.

## 1. Version

`versionName "0.1.0"`, `versionCode 1` (`app/build.gradle.kts`). Policy in D-35: `versionCode` +1 for every *distributed* build, never reused or lowered; `versionName` = `MAJOR.MINOR.PATCH`; a `vX.Y.Z` tag marks the release commit. Application ID / namespace `com.fernando.ahora`, label `AHORA`. **No git tag exists yet.**

## 2. Signing — what the owner supplies

The production key is created and held **by the owner**; nothing in this repository can create or commit it.

**Create it once** (any machine you trust; keep the file *outside* the repository, e.g. `C:\Users\pc\keys\ahora-release.jks`):

```
keytool -genkeypair -v -keystore ahora-release.jks -alias ahora -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` asks for a keystore password, a key password and your name/organisation. `-validity 10000` ≈ 27 years (a key must outlive every update you will ever ship). **Back the `.jks` and both passwords up in two places** (password manager + offline copy). If the key is lost, nobody who installed AHORA can update it in place: the package name stays but the signature no longer matches.

**Give the build these four values** — by either route, never in chat, never in a commit:

| Value | `keystore.properties` key | environment variable |
|---|---|---|
| Path to the `.jks` | `storeFile` | `AHORA_KEYSTORE_FILE` |
| Keystore password | `storePassword` | `AHORA_KEYSTORE_PASSWORD` |
| Key alias (`ahora` above) | `keyAlias` | `AHORA_KEY_ALIAS` |
| Key password | `keyPassword` | `AHORA_KEY_PASSWORD` |

`keystore.properties` goes in the **repository root** and is git-ignored (`*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `keystore.properties` are all ignored). Use forward slashes in the path:

```
storeFile=C:/Users/pc/keys/ahora-release.jks
storePassword=...
keyAlias=ahora
keyPassword=...
```

All four or none: with none, `assembleRelease` produces an **unsigned** APK (this is what CI builds); with only some, the build **fails** and names the missing keys (never a value).

**Build and verify the signed APK:**

```
./gradlew :app:assembleRelease                     # -> app/build/outputs/apk/release/app-release.apk  (NOT ...-unsigned.apk)
apksigner verify --print-certs -v app/build/outputs/apk/release/app-release.apk
```

`apksigner` must print `Verifies`, `v2 ... true`, and a certificate whose DN is yours (not `CN=Android Debug`). Write the SHA-256 digest down: it is the fingerprint of your identity and the value to compare on every future release. (It is public; it is safe to paste into an issue or a doc.)

## 3. CI

`.github/workflows/build.yml` runs on every push/PR on `ubuntu-latest` with Temurin 21: `testDebugUnitTest lintDebug assembleDebug assembleRelease`, uploads the lint report. It needs **no secrets**. It has not run on GitHub yet; the first run is the proof.

## 4. Device matrix (`scripts/device_matrix.py`)

Priority checks per Android version, automated with adb + uiautomator (standard-library Python). It installs the **debug** APK (it needs the adb seeding hook) and prints PASS/FAIL per check:

`launch` · `caps` (permission state of a fresh install) · `navigation` (tabs, Ajustes, back) · `quick_capture` · `persistence` (force-stop + relaunch) · `edit` · `hoy_foco` · `permission_dialog` (in-context `POST_NOTIFICATIONS` request, API 33+) · `reminders` (real alarm delivery, notification body and actions, HECHO, +10 MIN, ABRIR after process death, body tap on a warm app) · `notifications_denied` (API 33+) · `exact_alarm_degraded` (exact-alarm access revoked, API 31+; reports the delay) · `a11y` (no unnamed or < 48 dp interactive control on Hoy, Bandeja, Tareas, Ajustes and the editor at 100 % and 200 % font) · `reboot` (alarm re-armed after boot **without opening the app**, then delivered).

```
python scripts/device_matrix.py --serial <adb serial>          # a running device/emulator
python scripts/device_matrix.py --avd ahora_api26 --port 5556  # boots the AVD headless, runs, shuts it down
python scripts/device_matrix.py --serial <serial> --only launch,reminders   # or --skip reboot,reminders
```

AVDs used: `ahora_api26`, `ahora_api31` (Android 12), `ahora_api33` (Android 13), `ahora_api34` (Android 14), `ahora_api36` (Android 16) — Google APIs x86_64 images, Pixel 5 profile. About 12 minutes per device; the runs are independent, so devices can run in parallel. Reports are written to `build/device-matrix/`.

## 5. Signed-release smoke (`scripts/release_smoke.py`)

Runs against the **release** APK (R8, no debug hook) using only the real UI: signature and fingerprint, installed version/flags/permissions (no `INTERNET`, not debuggable, no debug receiver), launch, capture, persistence across force-stop and process death, edit, Hoy → Foco → Terminar, **four real reminders created through the editor and the time dial** (permission dialog included), delivery with body and actions, ABRIR after process death, HECHO, +10 MIN **and its real re-delivery**, and reboot recovery.

```
python scripts/release_smoke.py --serial <serial> --apk app/build/outputs/apk/release/app-release.apk
python scripts/release_smoke.py --serial <phone> --apk ... --skip-reboot   # a phone with a lock screen: see below
```

`--allow-debug-key` exists only to rehearse the script with a debug-signed build; a debug-signed run is **never** the final test. About 30 minutes (real alarms). On an **unlocked physical phone** it also works; on one with a lock screen, run it with `--skip-reboot` and do the reboot step by hand. Behaviour of a reminder that comes due *before the first unlock after a reboot* (direct boot) has **not** been verified on any device; it is on the STATUS "not verified" list.

## 6. TalkBack — manual pass (10 minutes, on a real phone)

Real TalkBack was run on the API 36 emulator and it announces what the accessibility tree promises (text fields as *Edit box* + their placeholder, Ajustes as a button, the date picker, the *Tarea completada / Deshacer* snackbar as an alert), but driving TalkBack with adb gestures was not reliable enough to claim a full reading-order pass. Do this once on the physical device, TalkBack on, at 100 % and again at 200 % font size:

1. Hoy: swipe right through the screen — settings button, greeting, Ahora card (title, priority, date), rows, "También pendiente", tabs, FAB. Each stop must say what it is and its state ("Prioridad P1", "Marcar como hecha: …").
2. FAB → editor: title and notes fields read their placeholder; Hoy/Mañana/Elegir fecha say *selected/not selected*; the reminder switch says *on/off*; P1/P2/P3 read their name; Guardar says *disabled* until there is a title.
3. Complete a task: the snackbar is announced and *Deshacer* is reachable.
4. Foco: the clock, presets, Terminar, Posponer, Salir are all reachable (also in landscape, by scrolling).
5. A reminder notification: the actions read HECHO, +10 MIN, ABRIR.

## 7. Before tagging `v0.1.0`

Signed APK built and `apksigner verify` shows your fingerprint · `release_smoke.py` green on an emulator and (if you have one) the phone · CI green on GitHub · Gate D decided by the owner · then and only then `git tag v0.1.0` on the release commit.
