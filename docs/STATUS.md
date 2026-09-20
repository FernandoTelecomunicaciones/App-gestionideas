# AHORA — Status

**Checkpoint: 2026-09-20 (night) — after Codex Gate B.** Branch `claude-pruebas` · HEAD `9b98e3a` (engine checkpoint) · **the Gate B fixes are in the working tree, uncommitted** (see §6).

> **Read this first:** the reminder *engine* is built, has passed Codex Gate B (all findings fixed) and is verified on a real Android 16 emulator, but **the app has no user interface yet.** `MainActivity` renders the text "AHORA". Bandeja / Hoy / Tareas / Foco / the edit sheet / Ajustes, the Compose theme, navigation and DataStore preferences are **not implemented**. Quick Capture does not exist as a screen. What exists is: toolchain, domain rules, Room + repository, the complete reminder engine with notifications and receivers, and tests.

## 1. Last verified build (fresh forced re-run, `--rerun-tasks`, after the Gate B fixes)

| Task | Result |
|---|---|
| `assembleDebug` | ✅ **BUILD SUCCESSFUL** (debug APK 14.0 MB) |
| `testDebugUnitTest` | ✅ **176 tests, 0 failures, 0 errors, 0 skipped** (156 before Gate B + 20 regression tests) |
| `lintDebug` | ✅ **0 errors, 17 warnings** (same list as before, §5) |
| `assembleRelease` (R8 + resource shrinking) | ✅ **BUILD SUCCESSFUL** (unsigned APK 0.9 MB). First release build ever run. |
| `DebugCommandReceiver` absent from release | ✅ 0 occurrences in the release merged manifest, the release APK's binary manifest and the release dex; present in the debug APK (control) |
| Release smoke test on the emulator | ✅ signed with the debug key, installed, launched: Hilt graph resolves, app-start reconcile runs, `TIMEZONE_CHANGED` reaches the receiver through the `@EntryPoint` and reconciles, crafted `ahora://` warm-start intents do not crash. (No task can be created in release — that is the point of the debug-only receiver.) |

Toolchain (D-30): AGP 9.4.1 · Gradle 9.7.1 · Kotlin 2.4.20 · KSP 2.3.12 · Compose BOM 2026.09.00 · Hilt 2.60.1 · Room 2.8.5 · `minSdk 26` · `targetSdk 36` · `compileSdk 37` · run on Android Studio's JDK 21 (`JAVA_HOME` set per command; the machine default `java` is still 1.8 and untouched).

### Tests by suite (all passing)

| Suite | Tests | What it proves |
|---|---:|---|
| `TaskRepositoryTest` (Robolectric + real in-memory Room) | 54 | lifecycle; invariants; idempotence; 8-way concurrent complete ⇒ one successor; revision guards; import atomicity/validation incl. revision bound (GB-03); **recurring reopen refused, undo refuses when the successor moved on (GB-04)**; **duplicate `+10 MIN` inert (GB-06)** |
| `ReminderReconcilerTest` (pure JVM, fakes) | 31 | single cursor; arm-before-deliver; poison item / bounded retries; delivery budget; cancellation; revision race; tray sweep; idempotence; request storm; **conflation ≤ 2 passes (GB-05)**; **emergency cursor on failed/cancelled read or failed arm, settle-before-sweep (GB-02)**; timezone; DST gap/overlap; clock jumps |
| `ReminderIntegrationTest` (real Room + repo + reconciler) | 22 | every mutation re-plans; stale actions inert; **a delayed stale action never dismisses a newer card (GB-01)**; **a no-op action dismisses its own card**; duplicate snooze; edit-vs-reconcile race; reboot recovery; kill-after-commit heals; recurring HECHO ⇒ one armed successor |
| `AndroidRemindersRobolectricTest` | 17 | one alarm only; exact vs inexact fallback; notification content/actions; HECHO/+10 MIN immutable broadcasts; ABRIR direct activity intent with a **per-revision** identity; unique action identities; deep-link/action URI validation incl. `?rev=` |
| `NotificationLinkHandlerTest` | 7 | Home / Focus resolution; garbage ⇒ nothing; missing or done ⇒ Home; ABRIR dismisses only its own revision's card (GB-07) |
| `TodayPlannerTest` / `TaskFiltersTest` / `RecurrenceCalculatorTest` / `ReminderPlannerTest` / `TaskRulesTest` / `GreetingBandTest` | 8 / 8 / 8 / 10 / 6 / 1 | PRODUCT_SPEC §6 rules |
| `ArchitectureBoundaryTest` | 3 | `ui` cannot import data/Room/`ReminderStore`/reconciler; `domain` stays Android-free; **no `INTERNET` permission** |
| `ScaffoldSmokeTest` | 1 | toolchain |
| **Total** | **176** | |

Each Gate B regression test was mutation-checked: reverting the corresponding fix turns it red (GB-01, 02, 04, 05, 06, and the GB-03 validation).

## 2. Milestones (your M0–M12 numbering)

> Order deviation, deliberate: I built the **reminder engine before the UI** (risk-first, ARCHITECTURE §16), so M8/M9 are largely done while M3–M7 are not started.

| # | Milestone | State |
|---|---|---|
| **M0** | Android scaffold | ✅ **Done.** Gradle project, version catalog, Hilt/Room/Compose/Navigation deps, manifest, lint, Archivo font + Lucide icons bundled as resources, licences in assets, backup rules (cloud OFF), CI workflow file. *Caveat: the CI workflow has never run (no GitHub run), and there is no Compose theme code yet.* |
| **M1** | Domain + data | ✅ **Done.** Gate A run; all findings fixed; fixes re-reviewed by Gate B (two holes found and closed). |
| **M2** | Platform/reminder spike | ◐ **Mostly done** on an Android 16 emulator (§4). Not done: Android 12/14/13, API 26, physical device, back-stack (A7), device-transfer backup (A9). |
| M3 | Bandeja + Quick Capture | ⬜ Not started (repository `create` exists; no screen) |
| M4 | Create/Edit task sheet | ⬜ Not started |
| M5 | Hoy | ⬜ Not started (`TodayPlanner` logic done and tested) |
| M6 | Tareas | ⬜ Not started (`TaskFilters`/search logic done and tested) |
| M7 | Foco | ⬜ Not started |
| **M8** | Reminder engine | ✅ **Engine done, Gate-B reviewed and device-verified**: reconciler, scheduler, receivers (alarm/actions/boot/time/zone/permission), `ReminderActionHandler`. ⬜ Still missing: runtime `POST_NOTIFICATIONS` request flow and the editor/Ajustes permission hints (need the UI). |
| **M9** | Notifications | ◐ **Mostly done**: channel, builder, HECHO/+10 MIN/ABRIR, PendingIntents, tray sweep, and (Gate B) `NotificationLinkHandler` wired into `MainActivity` for cold and warm start: links are validated and the ABRIR card is dismissed (verified on the emulator, cold and warm). ⬜ **Navigation itself is not built**: nothing consumes `MainActivity.pendingDestination`, so a body tap / ABRIR still lands on the placeholder screen; the `[Home, Focus]` back stack (A7) is untested. |
| M10 | Settings / themes / data tools | ⬜ Not started (no DataStore code, no export/import UI; repository `exportAll`/validated `replaceAll` exist) |
| M11 | Hardening | ⬜ Not started |
| M12 | Release readiness | ⬜ Not started (a release build now compiles and starts; signing, versioning, store/side-load packaging are not done) |

**Completed:** M0, M1, M8 (engine), most of M9, most of M2.
**Remaining:** M3–M7, M10–M12, the permission/navigation UI half of M8/M9, and the unverified items in §4.

## 3. Codex review gates

| Gate | State | Detail |
|---|---|---|
| Review #1 (docs, built-in `review`) | ✅ done | 5 findings, all verified, all fixed (DECISIONS log) |
| Review #2 (docs, 23-point checklist, `task`) | ✅ done | 3 CRITICAL / 8 IMPORTANT / 2 OPTIONAL, all accepted; ARCHITECTURE §17 |
| **Gate A** (M1 code) | ✅ done | 2 CRITICAL / 7 IMPORTANT / 2 OPTIONAL, all fixed with tests. Gate B re-reviewed those fixes and found two holes (GB-03, GB-04), now closed. |
| **Gate B** (reminders + notifications) | ✅ **done — engine approved with conditions (below)** | Codex reported 4 CRITICAL / 3 IMPORTANT; my re-classification after verifying each against the code: 1 CRITICAL, 5 IMPORTANT, 1 OPTIONAL. All 7 were real and are fixed with regression tests; details in DECISIONS › "Gate B", normative text in ARCHITECTURE §18. **The fixes have not been re-reviewed by Codex.** |
| Gate C (after M11) | ⏳ pending | |
| Gate D (final adversarial audit) | ⏳ pending | |

**Gate B conditions (carried forward; they do not block the engine):**
1. The navigation half of GB-07 — consume `pendingDestination`, build `[Home, Focus]` (A7) — must land with M3/M5 and be verified cold and warm from every destination.
2. The device matrix in §4 "NOT verified" (API 26/31/33/34, a physical device) before any release.
3. Ask Codex for a narrow re-review of the Gate B fixes at Gate C.

Codex integration: no failures. The built-in `/codex:review` accepts no focus text, so all checklist reviews use `codex-companion task` (read-only). Gate B ran in ~6 minutes at `--effort high`.

## 4. Device / emulator verification

Environment: Android Emulator 36.4, **Android 16 (API 36), google_apis x86_64**, WHPX-accelerated. All below were observed with logcat / `dumpsys alarm` / `dumpsys notification`.

### ✅ Verified on the emulator
| Assumption / behaviour | Result |
|---|---|
| **A1** exact alarm denied by default on a fresh install | `canScheduleExact=false`, `notificationsGranted=false` |
| Degraded path (exact denied) | alarm armed as **inexact** allow-while-idle (`window=+2m`), no crash |
| **A4** exact-alarm grant/revoke | grant ⇒ `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` reaches the non-exported receiver, cursor re-armed **exact** (`window=0`); **revoke ⇒ process killed and alarm dropped**; re-grant restarts the process and re-arms |
| Exact delivery | fired **40 ms** after the due instant; card: HIGH importance, `category=reminder`, ONLY_ALERT_ONCE, AUTO_CANCEL, 3 actions, activity content intent |
| **A8** for the broadcast action | real touch on **+10 MIN** ⇒ snooze = tap + 10 min, due time and revision unchanged, card dismissed, exact cursor re-armed |
| **A2** reboot recovery | `BOOT_COMPLETED` reached the non-exported receiver without opening the app; cursor rebuilt from Room |
| Force-stop | alarm wiped (platform limitation, as documented); next launch re-arms |
| **A5** timezone change | same process (no restart) re-planned GMT ⇄ New York with no data change |
| **A6** `TIMEZONE_CHANGED` / `TIME_SET` manifest receivers | delivered while the process is alive |
| Clock jump **forward** past a due reminder | delivered once, late; no duplicate from the RTC alarm |
| Clock jump **backward** | cursor stays at the absolute instant; no spurious delivery |
| **Doze** (`deviceidle force-idle`) | exact alarm fired **45 ms** after due while in deep idle |
| Notification permission revoked (card visible) | card cleared (process killed by the system), no crash |
| Reminder due with **no** notification permission | consumed silently, cursor moved on, 0 crashes |
| **DST gap** on a real armed alarm | Madrid 2026-03-29 02:30 (non-existent) ⇒ `01:30Z` = 03:30 local |
| **DST overlap** on a real armed alarm | Madrid 2026-10-25 02:30 ⇒ `00:30Z` (earlier occurrence) |
| Update in place | `MY_PACKAGE_REPLACED` delivered and re-armed within ~1 s (*whether the alarm also survives by itself was not isolated — irrelevant, we re-arm*) |
| **After Gate B** — exact delivery through the rewritten reconcile pass | real exact alarm fired **31 ms** after due; the card carries the revision extras |
| **After Gate B** — ABRIR link, warm start | `ahora://focus/1?rev=9` (stale) leaves the card; `?rev=0` (matching) dismisses it; no crash. Sent with `am start` (explicit intent), **not a real touch on the button** |
| **After Gate B** — ABRIR link, cold start (after force-stop) | launches, app-start reconcile runs, no crash |
| **Release/R8 build** | see §1 |

### ⬜ NOT verified (do not treat as working)
- **A7** back stack after ABRIR = `[Home, Focus]`, and the **body tap** (cold *and* warm from each screen) — no navigation code yet (link handling and card dismissal are verified; navigation is not).
- **A9** device-transfer restore of the Room files (needs two devices); cloud backup is OFF by manifest rules but the rules were never exercised.
- Real touch on **HECHO** and **ABRIR** (HECHO is covered by unit/integration tests; ABRIR's link handling was driven with `am start`).
- `goAsyncBounded` under a real receiver deadline; a real process kill between the Room commit and the alarm call (unit-simulated only).
- **Android 12, 13, 14** and **API 26** devices (only API 36 tested); any **physical device**; OEM battery-killer behaviour.
- Reboot **before first unlock**.
- The runtime **`POST_NOTIFICATIONS` dialog** and the exact-alarm settings hand-off (no UI).
- TalkBack, 200 % font scale, predictive back, edge-to-edge insets, dark/light rendering, screenshots, performance (NFR-02), accessibility contrast on real screens.
- The CI workflow on GitHub.

## 5. Known issues

**Product/scope**
1. **No UI** (see banner). The app cannot capture, list, edit, focus or configure anything from the screen yet.
2. Notification **navigation** is not built: `MainActivity` resolves and validates the link and dismisses the ABRIR card, but nothing consumes `pendingDestination` yet (M3).
3. Ajustes contents, theme switching, DataStore, JSON export/import: not started.
4. ~~Reopen forks a recurring series~~ **Fixed (GB-04, D-31):** a recurring occurrence cannot be reopened; undo refuses once its successor moved on. **Consequence for the UI milestone:** the Completadas checkbox of a recurring occurrence must not be actionable (PRODUCT_SPEC §6.3 / TSK-05 now say so). There is no persistent series lineage (deferred, D-31).

**Engineering**
5. **Lint warnings (17, none blocking):** `OldTargetApi` (targetSdk 36 vs 37), `ExportedReceiver` (the **debug-only** command receiver), `ObsoleteSdkInt` (`mipmap-anydpi-v26` is redundant at minSdk 26), and 14× `UnusedResources` for icons the not-yet-built UI will use.
6. **`DebugCommandReceiver`** (`src/debug`, exported) is a test harness, **verified absent from release** (§1).
7. ~~`request()` does not coalesce~~ **Fixed (GB-05).**
8. `TIME_SET`/`TIMEZONE_CHANGED` are manifest-only (verified delivered); no dynamic registration.
9. Robolectric tests run at SDK 34 while `targetSdk` is 36 (flagged again by Gate B; behaviour at 36 is device-verified only).
10. `./gradlew clean` can fail on Windows while a shell holds `app/build/test-results` as its cwd (harmless file lock).
11. Residual, accepted: HECHO/+10 MIN/ABRIR check the tray and then cancel; a newer card posted in the microseconds between the two could still be cancelled (and Room already records it delivered). A card's title is not refreshed after a title-only edit (its actions stay valid).
12. Release signing/versioning is not set up: the release APK is unsigned and was only smoke-tested after re-signing with the debug key.

**Documentation vs code — deltas still not written into the older ARCHITECTURE §8/§10 body text** (ARCHITECTURE §18 records the Gate B amendments and supersedes where they conflict)
- Receivers resolve collaborators through a Hilt **`@EntryPoint`** (Kotlin cannot call `super.onReceive` on `@AndroidEntryPoint` receivers).
- Deep links are parsed **manually** via `AppLinks` from explicit intents (not `navDeepLink`), still with no manifest intent-filter.
- `ReminderStore` stays a public domain interface, guarded by `ArchitectureBoundaryTest` (GA-09).
- `compileSdk` is 37 (recorded in D-02/D-30).
- The ARCHITECTURE header still says "Draft v1 … No code exists" — stale.

**Environment (changes made on this machine, all additive)**
- Android SDK: AGP auto-installed platform **android-37.0**; I installed **cmdline-tools/latest**, system image **android-36 google_apis x86_64**, and created AVD **`ahora_api36`** (`C:\Users\pc\.android\avd`). The emulator (`emulator-5554`) **is still running** with the **debug** build installed; its time zone is GMT and network time is on.
- The Windows default `java` is still 1.8 (unchanged); `local.properties` (gitignored) points at the SDK; Git Bash needs `MSYS_NO_PATHCONV=1` for `adb` device paths. `apksigner.bat` needs a Windows-style `JAVA_HOME`.

## 6. Git state

Branch `claude-pruebas`, HEAD `9b98e3a` (`feat: add Android scaffold, data layer and reminder engine`). **Uncommitted:** the Gate B work — 18 modified files (9 in `main` — `MainActivity`, `TaskRepositoryImpl`, `TaskDao`, `TaskRepository`, `TaskRules`, `AndroidReminderNotifier`, `ReminderActionHandler`, `ReminderIntents`, `ReminderReconciler` — 5 test files incl. `ReminderFakes`, and 4 docs) and 2 new files (`reminders/NotificationLinkHandler.kt` and its test). Build, 176 tests, lint and the release build are green on exactly this tree, so it is a clean point for a commit; **I have not committed anything.**
