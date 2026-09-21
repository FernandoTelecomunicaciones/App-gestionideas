# AHORA — Status

**Checkpoint: 2026-09-21 — Codex Gate C done and its fixes applied.** Branch `claude-pruebas` · HEAD `04cedc8` (the full user-facing MVP, committed) · **the Gate C fixes, their 23 regression tests and the doc updates are in the working tree, uncommitted** (§6). Gate D has **not** been started.

> **Read this first:** the app is usable end to end (Hoy, Bandeja + quick capture, Tareas, create/edit sheet, Foco, Ajustes, themes, JSON export/import, reminder permission UX, notification navigation cold and warm). Codex Gate C reviewed the whole tree: **0 CRITICAL, 12 IMPORTANT, 1 OPTIONAL as reported**; after independent verification 8 were fixed, 1 was shown not to reproduce on the device, and 4 were rejected or deferred with reasons (DECISIONS › Gate C, D-34, ARCHITECTURE §20). What is *not* done: any device other than one Android 16 emulator, TalkBack, screenshot tests, release signing/versioning (M12); the CI workflow has never run on GitHub.

## 1. Last verified build (forced re-run, `--rerun-tasks`)

| Task | Result |
|---|---|
| `testDebugUnitTest` | ✅ **323 tests, 0 failures, 0 skipped** (300 before Gate C + 23 Gate C regression tests) |
| `lintDebug` | ✅ **0 errors, 10 warnings** (§5) |
| `assembleDebug` | ✅ (APK 14.5 MB) |
| `assembleRelease` (R8 + resource shrinking) | ✅ builds (**unsigned** APK 2.4 MB). The R8 build was signed with the debug key and exercised on the emulator at the *previous* checkpoint (Hilt graph, type-safe routes, Room, DataStore, capture → editor → Hoy → Foco → Terminar); **that signed smoke was not repeated after the Gate C fixes** — only the build itself was. |
| Release manifest / dex | ✅ no `INTERNET` permission, no `DebugCommandReceiver` (manifest and dex re-checked on the final build) |

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
| **Gate C:** `GateCDomainTest` | 8 | dates beyond 1900–2200 rejected on import (due date and anchor) and dropped by `normalize`; the whole supported range imports; text capped at the backup limits and **an export saved at the limits re-imports** |
| **Gate C:** `GateCRepositoryTest` | 5 | a failing re-plan after commit does not fail `create`/`complete`/`edit`/`postpone`/`delete`/import and keeps the Undo token; **`undoComplete` refuses an edited successor** and still removes an untouched one |
| **Gate C:** `GateCEditorTest` | 5 | a slow `openEdit` read cannot reopen a dismissed sheet, overwrite another task's draft or a new blank draft (held-result gate); typing stops at the limits |
| **Gate C:** `GateCUiTest` | 3 | a switch is found by its name and exposes on/off; every `TaskActions` write that throws becomes a message, not an uncaught exception |
| **Gate C:** `AndroidRemindersRobolectricTest` (+2) | 2 | a blocked reminders channel ⇒ `canNotify()` and the permission snapshot say "no"; posting into it is a permanent failure |

Each Gate C fix was mutation-checked: reverting it makes its regression test fail (GC-07, 06, 05 ×2, 03, 04, 09; GC-08 is a structural test).

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

Review #1, review #2, Gate A, Gate B, **Gate C**: done (see DECISIONS). Gate C covered the whole working tree and re-reviewed the Gate B fixes (they hold, apart from the accepted GB-01 residual window). **Gate D is not started.** What still blocks an APK release is in §7.

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

**Gate C session (same emulator, debug build after the fixes):**

| Flow | Result |
|---|---|
| Task retained in Recents, process **killed** (before and after the alarm), Ajustes as the last screen; tap **ABRIR** on the card | Foco; back → Hoy |
| Same setup; tap the notification **body** | Hoy (Ajustes gone); back leaves the app |
| Accessibility tree of the Ajustes "Mantener pantalla encendida" switch | checkable node + a labelled child node — the same structure the labelled checkboxes have. **Not** verified with TalkBack. |
| Final debug build: launch, FAB opens the sheet, typing works, system back closes it | ✔ (smoke only) |

### ⬜ NOT verified (do not treat as working)
- Gate C fixes with no device evidence: reminders channel blocked in system settings (JVM/Robolectric only), the editor's reminder switch label (the Ajustes one was inspected), Hoy/Mañana at midnight (no automated test; verified by reading), export/import of an at-limit file on the device.
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
6. A notification link closes an open editor sheet and discards its draft (D-33 #7) — accepted limitation (Gate C: the modal must close to show the destination). Foco in landscape needs a scroll to reach Salir (system back is the same action) — accepted limitation.
7. After the system refuses the notification dialog for good, the first tap on "Permitir" may show nothing before it turns into "Abrir ajustes" (the platform gives no way to know beforehand).
8. Lint warnings (10, none blocking): 5 `UnusedResources` (Lucide icons `chevron-down/up`, `x`, `search`, `trash-2` bundled for the design set but not used by any screen), 2 `UseKtx`, `OldTargetApi` (targetSdk 36 vs 37), `ExportedReceiver` (debug-only command receiver), `ObsoleteSdkInt` (`mipmap-anydpi-v26`).
9. `PRODUCT_SPEC.md` still says "Draft v1"; its requirements were followed as written (deviations: DECISIONS D-33).
10. Gate C residuals **accepted, not fixed**: the check-then-cancel window of a stale action against a just-posted newer card (GC-02: needs revision-specific notification ids); a failed SAF export can leave a partial file in the document the user chose (GC-10); no Activity-level/instrumented test of `MainActivity` link handling or of receiver deadlines (GC-11 — verified on the device instead).
11. From the previous checkpoint and unchanged: Robolectric runs at SDK 34 while `targetSdk` is 36; `./gradlew clean` can fail on Windows while a shell holds `app/build/test-results` as cwd; residual GB-01 window; release signing/versioning not set up; `DebugCommandReceiver` is debug-only (verified absent from release).

## 6. Git state

Branch `claude-pruebas`, HEAD `04cedc8` (the UI MVP). **Nothing from Gate C is committed.** Working tree: 16 modified files (`TaskRules`, `BackupCodec`, `TaskRepositoryImpl`, `TaskActions`, `EditorViewModel`, `EditorDraft`, `EditorSheet`, `InboxViewModel`, `Controls`, `SettingsScreen`, `SettingsViewModel`, `AndroidReminderNotifier`, `ReminderPermissions`, `strings.xml`, `.github/workflows/build.yml`, one Robolectric test) + the docs (`STATUS`, `DECISIONS`, `ARCHITECTURE`, `PRODUCT_SPEC`) + 4 new test files (`GateCDomainTest`, `GateCRepositoryTest`, `GateCEditorTest`, `GateCUiTest`). Build, 323 tests, lint (0 errors) and the release build are green on exactly this tree, so it is a clean point for a commit — I have not made one.

## 7. Before Gate D / an APK release

1. **Release signing and versioning** (M12, GC-12): `assembleRelease` is unsigned; needs the owner's keystore (never in the repo) and a `versionCode/versionName` policy.
2. **Device matrix** (ARCHITECTURE §15.4 / §17.1): at least API 26, one Android 12–14 and one physical device; reboot, TIME_SET/zone/DST on a real alarm, exact-alarm revoke/re-grant, blocked channel, OEM battery behaviour.
3. **TalkBack pass** at 100 % and 200 % font scale (A11Y-04/05) — an acceptance requirement, currently only inspected through the accessibility tree.
4. **Run the CI workflow** on GitHub once (it now includes `assembleRelease`).
5. Repeat the signed-release smoke on the emulator after the Gate C fixes.
6. Optional hardening: screenshot tests; Activity-level tests for cold/warm/restored notification entry.
