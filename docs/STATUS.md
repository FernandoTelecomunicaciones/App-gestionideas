# AHORA — Status

**Checkpoint: 2026-09-20 (late) — user-facing MVP built, before Gate C.** Branch `claude-pruebas` · HEAD `084627a` (engine hardened after Gate B) · **the whole UI milestone is in the working tree, uncommitted** (§6).

> **Read this first:** the app is now usable end to end. Hoy, Bandeja (quick capture), Tareas, the create/edit sheet, Foco and Ajustes are real native Compose screens on the real Room repository; notification body / ABRIR navigation works cold and warm; the theme (System/Light/Dark) persists; JSON export/import works. The reminder engine was **not** modified. What is *not* done: Codex Gate C, the device matrix beyond one Android 16 emulator, TalkBack, screenshot tests, hardening/release packaging (M11/M12).

## 1. Last verified build (forced re-run, `--rerun-tasks`)

| Task | Result |
|---|---|
| `testDebugUnitTest` | ✅ **300 tests, 0 failures, 0 skipped** (176 engine/data + 124 new for the UI and backup) |
| `lintDebug` | ✅ **0 errors, 10 warnings** (§5) |
| `assembleDebug` | ✅ (APK 14.5 MB) |
| `assembleRelease` (R8 + resource shrinking) | ✅ (unsigned APK 2.4 MB). Signed with the debug key and exercised on the emulator: Hilt graph, type-safe routes (`Focus(taskId)`), Room, DataStore, capture → editor → Hoy → Foco → Terminar all work under R8. |
| Release manifest | ✅ no `INTERNET`, no `DebugCommandReceiver` |

Toolchain unchanged (D-30). New test dependency: `androidx.navigation:navigation-testing`.

### Tests by suite (all passing)

| Suite | Tests | What it proves |
|---|---:|---|
| Engine / data (unchanged) — `TaskRepositoryTest`, `ReminderReconcilerTest`, `ReminderIntegrationTest`, `AndroidRemindersRobolectricTest`, `NotificationLinkHandlerTest`, domain rules, `ArchitectureBoundaryTest`, scaffold | 176 | see the previous checkpoint (Gate A/B regression tests) |
| `QuickCaptureTest` | 7 | title only → inbox item + "Guardado en Bandeja"; blank rejected, nothing written; title trimmed; chips pre-set date/priority and reset; text + chips survive process recreation; list = inbox items only, newest first |
| `EditorDraftTest` / `EditorViewModelTest` | 11 / 13 | time without date → Hoy/Mañana; clearing date clears time+reminder+repeat; repeat needs a date; save with a title alone; edit title/priority; clear priority/date; reminder saved through the repository (one `create`/`edit` re-plan, none for a title-only edit); delete + undo; a task completed elsewhere is never resurrected; missing task; draft survives recreation; dismiss discards; permission answer marks "asked" and asks the engine to re-plan |
| `HomeViewModelTest` | 10 | no flash of empty state; empty state; Ahora = highest priority; ≤ 3 rows + counter; overdue = ordinary; inbox-only reachable; expand/collapse + survives recreation; midnight tick recomputes; complete + undo |
| `TasksViewModelTest` | 9 | four filters; filter persisted; accent/case-insensitive search over title+notes; search within a filter; empty result; complete → completed view → reopen; **recurring occurrence cannot be reopened and the series never forks (D-31)**; completed hidden by default |
| `FocusViewModelTest` | 12 | starts at the remembered preset; countdown; preset tap restarts + remembers; unknown preset ignored; 00:00 rests and alerts exactly once; **restored session wins over DataStore**; Terminar; Posponer + undo; task done elsewhere / missing / deleted ⇒ closes |
| `PreferencesAndSettingsTest` | 14 | real DataStore: default, **theme persists across "processes"**, filter/preset/keep-on persist, invalid preset never stored, unknown stored values fall back; permission state representation + refresh; import shows counts and changes nothing until confirmed, confirm replaces + resets the engine, cancel is inert, newer/foreign/invalid files rejected; export writes a valid backup |
| `BackupCodecTest` | 13 | round trip; reminder state not exported; strict rejection (garbage, other version, unknown recurrence, priority range, bad date, seconds, duplicate ids, blank title, sizes) |
| `NavigationTest` | 16 | bottom bar, back from tabs, chrome only on tabs; Empezar; leave-Foco idempotent; **body → Hoy from Bandeja/Tareas/Ajustes/Foco; ABRIR cold and warm from each tab and Ajustes ⇒ `[Home, Focus]`, back ⇒ Hoy**; same Foco not restarted; another task replaces it; deleted/done/unknown task ⇒ Hoy (with the real `NotificationLinkHandler`) |
| `ScreensComposeTest` | 16 | rendered Hoy/Bandeja/Tareas/Foco: copy, loading shows nothing, ≤ 3 rows, priority always as text, overdue has no blame copy, Guardar disabled/enabled, recurring completed checkbox is read-only, Foco shows only its content |
| `FormattingTest` | 3 | date labels; year; **DatePicker UTC-midnight conversion** |

## 2. Milestones

| # | Milestone | State |
|---|---|---|
| M0 | Android scaffold | ✅ |
| M1 | Domain + data | ✅ (Gate A + B reviewed) |
| M2 | Platform/reminder spike | ◐ Android 16 emulator only (see §4) |
| **M3** | Bandeja + Quick Capture | ✅ built and verified on the emulator |
| **M4** | Create/Edit sheet | ✅ (title, notes, date, time, reminder, priority, repeat, duration, list, delete + undo, clear date/priority). Subtasks are a V1 non-goal and were not built. |
| **M5** | Hoy | ✅ (`TodayPlanner` output only; Ahora, ≤ 3 rows, collapsed remainder) |
| **M6** | Tareas | ✅ (search, 4 filters persisted, completion, Completadas link) |
| **M7** | Foco | ✅ (OD-3 timer, presets, Terminar/Posponer/Salir, rotation/process restore) |
| M8 | Reminder engine | ✅ + the UI half: in-context `POST_NOTIFICATIONS` request, inline hints, exact-alarm "Permitir" |
| M9 | Notifications | ✅ + navigation: body → Hoy, ABRIR → Foco, `[Home, Focus]`, cold and warm (device-verified with real touches) |
| **M10** | Ajustes / theme / data | ✅ (permissions, Sistema/Claro/Oscuro persisted, keep-screen-on, JSON export/import, privacy line) |
| M11 | Hardening | ⬜ |
| M12 | Release readiness | ⬜ (signing/versioning not set up) |

## 3. Codex review gates

Review #1, review #2, Gate A, Gate B: done (see DECISIONS). **Gate C is next** and should cover the UI code; carry these conditions: (1) a narrow re-review of the Gate B fixes (never re-reviewed); (2) the device matrix in §4; (3) the deviations in DECISIONS D-33.

## 4. Device / emulator verification (Android 16 emulator, `ahora_api36`)

Everything from the previous checkpoint still stands (engine behaviours). **New in this session, observed on the emulator:**

| Flow | Result |
|---|---|
| Quick capture ×2 with IME Done | field clears, keyboard and focus stay, newest row on top, "Guardado" snackbar |
| Editor: date/priority/save → Hoy | Ahora card with P1 + "Mañana", "También pendiente · 1" |
| Time picker → date auto-selects Mañana when the time has passed; "Quitar hora" | ✔ |
| Reminder switch with notifications revoked | system dialog appears **at that moment**; after "No permitir" an inline hint + "Abrir ajustes" (opens the app's system notification screen); after granting and returning the hint switches to the exact-alarm one, and "Permitir" opens the system exact-alarm screen; after allowing, the hint disappears. Saving is never blocked. |
| Complete from a Hoy row → "Tarea completada" + Deshacer | undo restores the task |
| Foco: countdown, rotation to landscape and back | the timer continues (24:44 → same session). Landscape (phone, 1080 px high): kicker, title, clock, presets, Terminar and Posponer are visible; **Salir sits below the fold inside the scroll container — I did not scroll to it** (system back is the same action) |
| Notification **ABRIR** (real touch), warm | Foco; system back → Hoy |
| Notification **ABRIR** (real touch), **cold**: process started by the alarm, no activity | Foco; system back → Hoy |
| Notification **body** (real touch) while on **Ajustes** | Hoy, Ajustes gone |
| "También pendiente" expanded → Bandeja → Hoy | collapsed again (5 rows → 3) |
| Ajustes: theme Oscuro | applied live; Hoy, sheet, time picker render correctly in dark |
| Export → system picker → file | valid JSON (`schemaVersion 1`); "Copia exportada" |
| Import: garbage file → "Ese archivo no es una copia de AHORA"; valid file → dialog with both counts → "Copia importada" | ✔ |
| System font scale 2.0 | Hoy and the editor show no clipped copy; the sheet scrolls under the IME |
| Release (R8) build, signed with the debug key | capture → editor → Hoy → Foco → Terminar all work |

### ⬜ NOT verified (do not treat as working)
- TalkBack (semantics were inspected through the accessibility tree only), predictive back for the sheet, the 200 % font scale on Bandeja/Tareas/Ajustes/Foco, keyboard/D-pad focus rings, contrast measured on rendered screens.
- Light-theme screenshots were inspected by eye only; there are no screenshot tests.
- Android 12/13/14 and API 26 devices, physical devices, OEM battery behaviour, reboot before unlock, device-transfer restore (A9), the CI workflow.
- Performance targets (NFR-02).
- Activity-level cold/warm start is device-verified, not a JVM test (the navigation logic itself is tested with `TestNavHostController`).

## 5. Known issues

**Design discrepancies (visual)**
1. The checkbox's visible 22 dp square sits ~9 dp further right than in the prototype because its touch target is 48 dp (design: 44 px buttons were raised to 48 dp on purpose, PRODUCT_SPEC §9.3).
2. Top bar: the design has none; the title-less bar with the settings icon (approved, DD-3) adds 48 dp above every tab heading.
3. Time is shown in the device's 12/24 h format ("8:06 p. m." on the emulator).
4. Overdue/undated dates use platform ICU abbreviations (`sept`, not always `sep`).
5. Bottom bar is 56 dp with a 2 px rule; the design's is slightly shorter.

**Product/engineering**
6. A notification link closes an open editor sheet and discards its draft (D-33 #7).
7. After the system refuses the notification dialog for good, the first tap on "Permitir" may show nothing before it turns into "Abrir ajustes" (the platform gives no way to know beforehand).
8. Lint warnings (10, none blocking): 5 `UnusedResources` (Lucide icons `chevron-down/up`, `x`, `search`, `trash-2` bundled for the design set but not used by any screen), 2 `UseKtx`, `OldTargetApi` (targetSdk 36 vs 37), `ExportedReceiver` (debug-only command receiver), `ObsoleteSdkInt` (`mipmap-anydpi-v26`).
9. `PRODUCT_SPEC.md` still says "Draft v1"; its requirements were followed as written (deviations: DECISIONS D-33).
10. From the previous checkpoint and unchanged: Robolectric runs at SDK 34 while `targetSdk` is 36; `./gradlew clean` can fail on Windows while a shell holds `app/build/test-results` as cwd; residual GB-01 window; release signing/versioning not set up; `DebugCommandReceiver` is debug-only (verified absent from release).

## 6. Git state

Branch `claude-pruebas`, HEAD `084627a`. **Nothing from this session is committed.** Working tree: 8 modified files (`MainActivity`, `DataModule`, `ReminderPermissions`, `strings.xml`, `build.gradle.kts`, `libs.versions.toml`, `ARCHITECTURE.md`, `DECISIONS.md`) plus this file, and 43 new files (`ui/**` ≈ 3 900 lines, `domain/{AppPreferences,ReminderPermissionState,backup/BackupCodec,model/ThemeMode}`, `core/time/ClockTicker`, `data/prefs/DataStoreAppPreferences`, and the new tests). Build, 300 tests, lint (0 errors) and the release build are green on exactly this tree, so **it is a clean point for a commit** — I have not made one.
