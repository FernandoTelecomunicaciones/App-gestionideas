# AHORA — Status

**Checkpoint: 2026-09-20 (evening).** Branch `claude-pruebas` · base commits `b2da23a` (Initial commit), `a9c99e1` (design handoff) · **nothing has been committed** (all work is untracked; see §6).

> **Read this first:** the reminder *engine* is built and verified on a real Android 16 emulator, but **the app has no user interface yet.** `MainActivity` renders the text "AHORA". Bandeja / Hoy / Tareas / Foco / the edit sheet / Ajustes, the Compose theme, navigation and DataStore preferences are **not implemented**. Quick Capture does not exist as a screen. What exists is: toolchain, domain rules, Room + repository, the complete reminder engine with notifications and receivers, and tests.

## 1. Last verified build (fresh forced re-run, `--rerun-tasks`, 2026-09-20 19:06)

| Task | Result |
|---|---|
| `assembleDebug` | ✅ **BUILD SUCCESSFUL** (debug APK 14.0 MB) |
| `testDebugUnitTest` | ✅ **156 tests, 0 failures, 0 errors, 0 skipped** |
| `lintDebug` | ✅ **0 errors, 17 warnings** (build passes with `abortOnError = true`; list in §5) |
| Release build (`assembleRelease`, R8) | ⬜ **never run** |

Toolchain (D-30): AGP 9.4.1 · Gradle 9.7.1 · Kotlin 2.4.20 · KSP 2.3.12 · Compose BOM 2026.09.00 · Hilt 2.60.1 · Room 2.8.5 · `minSdk 26` · `targetSdk 36` · `compileSdk 37` · run on Android Studio's JDK 21 (`JAVA_HOME` set per command; the machine default `java` is still 1.8 and untouched).

### Tests by suite (all passing)

| Suite | Tests | What it proves |
|---|---:|---|
| `TaskRepositoryTest` (Robolectric + real in-memory Room) | 50 | create/edit/complete/undo/reopen/postpone/undo/delete/undo/import lifecycle; invariants; idempotence; 8-way concurrent complete ⇒ one successor; revision guards; schema defaults; import atomicity/validation; single clock snapshot |
| `ReminderReconcilerTest` (pure JVM, fakes) | 26 | single cursor; **arm-before-deliver**; poison item / bounded retries; permanent vs transient failure; delivery budget; cancellation mid-delivery; revision race; tray sweep; idempotence; request storm (no deadlock); self-trigger (no deadlock); timezone; DST gap/overlap; clock forward/backward |
| `ReminderIntegrationTest` (real Room + repo + reconciler) | 19 | every mutation re-plans; stale `+10 MIN`/HECHO inert; edit-vs-reconcile race; reboot recovery; kill-after-commit heals; recurring HECHO ⇒ one armed successor; missed reminder once |
| `AndroidRemindersRobolectricTest` | 16 | one alarm only (no PendingIntent collisions); exact vs inexact fallback; notification content/actions; HECHO/+10 MIN immutable broadcasts, ABRIR direct activity intent, explicit components; unique action identities; permission-denied ⇒ permanent failure; deep-link/action URI validation |
| `TodayPlannerTest` / `TaskFiltersTest` / `RecurrenceCalculatorTest` / `ReminderPlannerTest` / `TaskRulesTest` / `GreetingBandTest` | 8 / 8 / 8 / 10 / 6 / 1 | PRODUCT_SPEC §6 rules incl. Ahora ranking, accent-insensitive search, month-end anchor, Madrid DST |
| `ArchitectureBoundaryTest` | 3 | `ui` cannot import data/Room/`ReminderStore`/reconciler; `domain` stays Android-free; **no `INTERNET` permission** |
| `ScaffoldSmokeTest` | 1 | toolchain |
| **Total** | **156** | Code: ~1,770 lines main · ~2,180 lines tests · 111 lines debug-only |

## 2. Milestones (your M0–M12 numbering)

> Order deviation, deliberate: I built the **reminder engine before the UI** (risk-first, ARCHITECTURE §16), so M8/M9 are largely done while M3–M7 are not started.

| # | Milestone | State |
|---|---|---|
| **M0** | Android scaffold | ✅ **Done.** Gradle project, version catalog, Hilt/Room/Compose/Navigation deps, manifest, lint, Archivo font + Lucide icons bundled as resources, licences in assets, backup rules (cloud OFF), CI workflow file. *Caveat: the CI workflow has never run (no GitHub run), and there is no Compose theme code yet.* |
| **M1** | Domain + data | ✅ **Done.** Gate A run; all findings fixed. |
| **M2** | Platform/reminder spike | ◐ **Mostly done** on an Android 16 emulator (§4). Not done: Android 12/14/13, API 26, physical device, back-stack (A7), device-transfer backup (A9). |
| M3 | Bandeja + Quick Capture | ⬜ Not started (repository `create` exists; no screen) |
| M4 | Create/Edit task sheet | ⬜ Not started |
| M5 | Hoy | ⬜ Not started (`TodayPlanner` logic done and tested) |
| M6 | Tareas | ⬜ Not started (`TaskFilters`/search logic done and tested) |
| M7 | Foco | ⬜ Not started |
| **M8** | Reminder engine | ✅ **Engine done and device-verified**: reconciler, scheduler, receivers (alarm/actions/boot/time/zone/permission), `ReminderActionHandler`. ⬜ Still missing: runtime `POST_NOTIFICATIONS` request flow and the editor/Ajustes permission hints (need the UI). |
| **M9** | Notifications | ◐ **Mostly done**: channel, builder, HECHO/+10 MIN/ABRIR, PendingIntents, tray sweep. ✅ real touch on **+10 MIN** verified on device. ⬜ ABRIR and body-tap **deep links are not handled** in `MainActivity` (no navigation yet; `AppLinks` parser exists and is tested), so cold/warm-start navigation is untested. |
| M10 | Settings / themes / data tools | ⬜ Not started (no DataStore code, no export/import UI; repository `exportAll`/validated `replaceAll` exist) |
| M11 | Hardening | ⬜ Not started |
| M12 | Release readiness | ⬜ Not started |

**Completed:** M0, M1, M8 (engine), most of M9, most of M2.
**Remaining:** M3–M7, M10–M12, the permission/deep-link UI half of M8/M9, and the unverified items in §4.

## 3. Codex review gates

| Gate | State | Detail |
|---|---|---|
| Review #1 (docs, built-in `review`) | ✅ done | 5 findings, all verified, all fixed (DECISIONS log) |
| Review #2 (docs, 23-point checklist, `task`) | ✅ done | 3 CRITICAL / 8 IMPORTANT / 2 OPTIONAL, all accepted; ARCHITECTURE §17 |
| **Gate A** (M1 code) | ✅ done | 2 CRITICAL / 7 IMPORTANT / 2 OPTIONAL, all fixed with tests. **The fixes were not re-reviewed by Codex.** |
| **Gate B** (reminders + notifications) | ⏳ **pending** | The engine code exists and is device-verified, but Codex has not reviewed it. This is the most important remaining review; best run once ABRIR/deep links exist, or now for the engine alone. |
| Gate C (after M11) | ⏳ pending | |
| Gate D (final adversarial audit) | ⏳ pending | |

Codex integration: no failures. The built-in `/codex:review` accepts no focus text, so all checklist reviews used `codex-companion task` (read-only). One test worker hung during my work; that was my own test deadlocking (see DECISIONS/tests), not Codex.

## 4. Device / emulator verification

Environment: Android Emulator 36.4, **Android 16 (API 36), google_apis x86_64**, WHPX-accelerated. All below were observed with logcat / `dumpsys alarm` / `dumpsys notification` on the debug build.

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

### ⬜ NOT verified (do not treat as working)
- **A7** back stack after ABRIR = `[Home, Focus]`, and the **body tap** (cold *and* warm from each screen) — no navigation code yet.
- **A9** device-transfer restore of the Room files (needs two devices); cloud backup is OFF by manifest rules but the rules were never exercised.
- Real touch on **HECHO** and **ABRIR** (HECHO is covered by unit/integration tests only).
- **Android 12, 13, 14** and **API 26** devices (only API 36 tested); any **physical device**; OEM battery-killer behaviour.
- Reboot **before first unlock**; process killed exactly between the Room commit and the alarm call (unit-simulated only).
- The runtime **`POST_NOTIFICATIONS` dialog** and the exact-alarm settings hand-off (no UI).
- **Release/R8 build**, TalkBack, 200 % font scale, predictive back, edge-to-edge insets, dark/light rendering, screenshots, performance (NFR-02), accessibility contrast on real screens.
- The CI workflow on GitHub.

## 5. Known issues

**Product/scope**
1. **No UI** (see banner). The app cannot capture, list, edit, focus or configure anything from the screen yet.
2. ABRIR/body deep links unhandled in `MainActivity` (tapping them opens the app, nothing more).
3. Ajustes contents, theme switching, DataStore, JSON export/import: not started.
4. Reopening a *completed recurring* occurrence that already produced an open successor yields two open occurrences (accepted V1 limitation; not guarded).

**Engineering**
5. **Lint warnings (17, none blocking):** `OldTargetApi` (targetSdk 36 vs 37), `ExportedReceiver` (the **debug-only** command receiver — must be confirmed absent from release), `ObsoleteSdkInt` (`mipmap-anydpi-v26` is redundant at minSdk 26), and 14× `UnusedResources` for icons the not-yet-built UI will use.
6. **`DebugCommandReceiver`** (`src/debug`, exported) is a test harness. It is debug-variant only, but the release variant has never been built to prove it.
7. `ReminderReconciler.request()` does not coalesce; N requests run N (idempotent, cheap) passes.
8. `TIME_SET`/`TIMEZONE_CHANGED` are manifest-only (verified delivered); no dynamic registration.
9. Robolectric tests run at SDK 34 while `targetSdk` is 36.
10. `./gradlew clean` can fail on Windows while a shell holds `app/build/test-results` as its cwd (harmless file lock).

**Documentation vs code — deltas not yet written into DECISIONS/ARCHITECTURE**
- Receivers resolve collaborators through a Hilt **`@EntryPoint`** (Kotlin cannot call `super.onReceive` on `@AndroidEntryPoint` receivers).
- Deep links will be parsed **manually** via `AppLinks` from explicit intents (not `navDeepLink`), still with no manifest intent-filter.
- `ReminderStore` stays a public domain interface, guarded by `ArchitectureBoundaryTest` (GA-09).
- `compileSdk` is 37 (recorded in D-02/D-30).

**Environment (changes made on this machine, all additive)**
- Android SDK: AGP auto-installed platform **android-37.0**; I installed **cmdline-tools/latest**, system image **android-36 google_apis x86_64**, and created AVD **`ahora_api36`** (`C:\Users\pc\.android\avd`). The emulator (`emulator-5554`) **is still running**; its clock/time-zone were restored (network time on, GMT).
- The Windows default `java` is still 1.8 (unchanged); `local.properties` (gitignored) points at the SDK; Git Bash needs `MSYS_NO_PATHCONV=1` for `adb` device paths.

## 6. Git state

Branch `claude-pruebas`, **10 untracked entries, 0 modified, 0 commits added**: `.github/`, `.gitignore`, `app/`, `build.gradle.kts`, `docs/`, `gradle.properties`, `gradle/`, `gradlew`, `gradlew.bat`, `settings.gradle.kts`. `local.properties`, `build/`, `.gradle/` are ignored.

**This is a clean checkpoint for a commit** (build, 156 tests and lint are green): suggested single commit `chore: Android scaffold, domain/data layer and reminder engine (M0–M1, M8–M9 engine)`. I have not committed anything.
