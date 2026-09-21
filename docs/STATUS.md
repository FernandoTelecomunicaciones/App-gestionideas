# AHORA — Status

**Checkpoint: 2026-09-21 — signed-release validation done (pre-Gate-D).** Branch `claude-pruebas` · HEAD `e4eb933` ("chore: prepare AHORA 0.1.0 release", committed; the working tree was clean when the signed build was tested) · **the production-signed 0.1.0 APK passed the full signed-release smoke: 15 / 15, 0 failures** (§4). Gate D has **not** been started. No product behaviour was changed.

> **Read this first:** the app is usable end to end (Hoy, Bandeja + quick capture, Tareas, create/edit sheet, Foco, Ajustes, themes, JSON export/import, reminder permission UX, notification navigation cold and warm). Gate C is approved. **This session (release validation):** versioning `0.1.0` / `1` and *optional* release signing are wired (D-35); the CI workflow was fixed and checked as far as it can be locally; a repeatable device matrix (`scripts/device_matrix.py`) is **green on Android 8, 12, 13, 14 and 16 emulators** (§4); an accessibility audit found and fixed one defect (D-36); the R8 release build was smoke-tested through the real UI with a debug key (rehearsal). **Since then the owner configured the production keystore, the signed `app-release.apk` was built, its signer matches the owner's expected fingerprint, and the signed-release smoke passed on the API 36 emulator (§4).** **What is *not* done:** **any physical device**, the **first GitHub Actions run**, a **full manual TalkBack pass**, screenshot tests.

## 1. Last verified build (final pre-Gate-D run, forced re-run with `--rerun-tasks`, 2026-09-21)

| Task | Result |
|---|---|
| `testDebugUnitTest` | ✅ **323 tests, 0 failures, 0 skipped** (28 classes; no JVM test was added this session — the one code change, the editor drag handle, is guarded by the device-matrix `a11y` check) |
| `lintDebug` | ✅ **0 errors, 10 warnings** (the same 10 as before, §5) |
| `assembleDebug` | ✅ (APK 14.5 MB) |
| `assembleRelease` (R8 + resource shrinking) | ✅ builds. **Unsigned** APK 2.4 MB when no key is supplied (what CI builds); **signed** when `keystore.properties` / `AHORA_*` variables are present (verified with the debug key: `apksigner` → `Verifies`, v2 true, and the file name flips from `app-release-unsigned.apk` to `app-release.apk`). A partial signing config fails the build by design. The R8 build was **exercised end to end on the emulator after the Gate C fixes** (release-smoke rehearsal, §4) — with the *debug* key, so it is not the final signed-release test. |
| Pristine-copy build (CI simulation) | ✅ the four tasks pass on a copy holding only tracked + non-ignored files, with no `local.properties`, no `keystore.properties`, no `AHORA_*` variables (served largely from the Gradle build cache, so it proves *no missing inputs*, not a from-scratch compile) |
| Release manifest / installed package | ✅ on the installed R8 APK: `versionName 0.1.0`, `versionCode 1`, not debuggable, no `INTERNET` requested, no `DebugCommandReceiver` |

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
| M12 | Release readiness | ◐ version `0.1.0`/`1`, signing wiring, CI fixes, device-matrix + release-smoke scripts, [RELEASE.md](RELEASE.md), **production keystore + production-signed APK + signed-release smoke (15/15 on API 36)** are done. **Still open:** a physical device, the manual TalkBack pass, the first CI run. |

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

### Signed-release validation (2026-09-21) — production key, `scripts/release_smoke.py`

The owner configured the production keystore (kept outside the repository; `keystore.properties` is git-ignored; no password was read, printed or copied). `assembleRelease` produced the **signed** APK; it was verified and then installed and driven on the API 36 emulator.

| Item | Value |
|---|---|
| APK | `app/build/outputs/apk/release/app-release.apk` (not `-unsigned`) |
| Size | **2,381,082 bytes (2.27 MiB / 2.4 MB)** |
| File SHA-256 | `5db1a1c1bb973f2c46f98f5a692994c61ad8c4e6a23ec28d1a7493374acb3e36` (the APK pulled back from the emulator's `base.apk` is byte-identical) |
| `apksigner verify --print-certs -v` | `Verifies` · v2 **true** · v1/v3/v3.1/v4 false (minSdk 26: v2 is enough) · 1 signer |
| Signer | `CN=Fernando, OU=Fernando, O=Fernando, L=Madrid, ST=Espa?a, C=ES` · RSA 4096 (not `CN=Android Debug`) |
| **Signer certificate SHA-256** | **`90:AB:1E:3F:1F:7B:DD:9D:5C:DC:5A:71:60:F3:41:80:1A:A7:8E:E8:87:5E:C2:15:41:C7:5F:A3:6F:D2:6B:FC`** — compared programmatically against the owner's expected fingerprint: **match** |
| Version | `versionName 0.1.0`, `versionCode 1` (`output-metadata.json` and the installed package agree) |
| Installed flags | not debuggable · no `INTERNET` · no debug receiver · minSdk 26 · targetSdk 36 |

**Smoke result (API 36 emulator `sdk_gphone64_x86_64`, real UI only, real alarms, ~30 min): PASS — 15 pass, 0 fail.**

| # | Check | Result |
|---|---|---|
| 1 | `signature` — verifies, v2, signer + SHA-256 printed | ✅ |
| 2 | `installed_metadata` — 0.1.0 (1), not debuggable, no `INTERNET`, no debug receiver | ✅ |
| 3 | `launch` — Hoy shown | ✅ |
| 4 | `capture_persistence` — listed; survives force-stop; survives process death | ✅ |
| 5 | `edit` — title edited and saved | ✅ |
| 6 | `hoy_foco` — created → Ahora card → Foco + clock → Terminar | ✅ |
| 7 | `reminders_created` — four reminders through the editor and the time dial (A 16:40, B 16:45, C 16:50, D 16:55; device time 16:29) | ✅ |
| 8 | `reminder_A_delivery` — process dead; body `Vence a las 4:40 p. m.`; actions HECHO · +10 MIN · ABRIR | ✅ |
| 9 | `action_ABRIR_cold` — Foco opened for the task; Terminar completed it | ✅ |
| 10 | `action_HECHO` — card dismissed, task done | ✅ |
| 11 | `action_MAS10` — card dismissed, task still open | ✅ |
| 12 | `reboot` — alarm re-armed with the app never opened; D delivered after boot | ✅ |
| 13 | `action_body_cold` — body tap after reboot (cold process) opened Hoy | ✅ |
| 14 | `snooze_redelivery` — the +10 MIN reminder was really re-delivered and completed | ✅ |
| 15 | `no_crash` — no `FATAL EXCEPTION` in the crash log for the whole run | ✅ |

Not rerun: nothing failed. Scope of this evidence: **one emulator (Android 16), the production key, the R8 build.** It is not physical-device evidence. The smoke script uninstalls any previous copy first, so the data on the emulator was wiped (debug → production signature differs).

### Release-validation session (2026-09-21) — device matrix, accessibility, Gate C re-checks

**Device/API matrix.** `scripts/device_matrix.py` (D-36, procedure in [RELEASE.md](RELEASE.md) §4), **debug** APK, five emulators run in parallel, one clean run of the final script. **Result: 0 failures on every device** (pass/fail/skip: API 26 = 14/0/3, API 31 = 15/0/2, API 33 = 17/0/0, API 34 = 17/0/0, API 36 = 17/0/0; the skips are checks that do not exist below that API level).

| Check | API 26 (Android 8) | API 31 (Android 12) | API 33 (Android 13) | API 34 (Android 14) | API 36 (Android 16) |
|---|---|---|---|---|---|
| launch, tabs + Ajustes + back, quick capture, Room persistence (force-stop + relaunch), edit, Hoy → Ahora → Empezar → Foco → Terminar | ✅ | ✅ | ✅ | ✅ | ✅ |
| Fresh-install permission state (reported, asserted) | notifications granted, exact allowed | same | notifications **not** granted, exact allowed | notifications not granted, **exact denied** | same as 34 |
| In-context `POST_NOTIFICATIONS` dialog on the reminder switch, then Allow | n/a | n/a | ✅ | ✅ | ✅ |
| Real alarm delivery; body `Vence a las h:mm`; actions HECHO · +10 MIN · ABRIR | ✅ | ✅ | ✅ | ✅ | ✅ |
| HECHO: task done, card dismissed | ✅ | ✅ | ✅ | ✅ | ✅ |
| +10 MIN: snooze 593–596 s ahead, task still open, card dismissed | ✅ | ✅ | ✅ | ✅ | ✅ |
| ABRIR after process death → Foco; back → Hoy | ✅ | ✅ | ✅ | ✅ | ✅ |
| Body tap while the app is open on Bandeja → Hoy | ✅ | ✅ | ✅ | ✅ | ✅ |
| Notifications denied: nothing posted, task kept, reminder consumed silently | n/a | n/a | ✅ | ✅ | ✅ |
| Exact alarm denied: a reminder due ≤ 60 s ahead is still delivered (69–73 s after creation) | n/a | ✅ | ✅ | ✅ | ✅ |
| **Reboot** with the app never reopened: alarm re-armed *n* s after boot, then delivered | ✅ 3 s | ✅ 9 s | ✅ 16 s | ✅ 16 s | ✅ 18 s |
| Accessibility audit at 100 % and 200 % font (Hoy, Bandeja, Tareas, Ajustes, editor) | ✅ | ✅ | ✅ | ✅ | ✅ |

All devices are Google APIs x86_64 emulators (Pixel 5 profile, 1080×2400, 420 dpi). **No physical device was connected (`adb devices` listed only emulators): nothing here is physical-device verified.**

**R8 release smoke — rehearsal, debug-signed, NOT the final signed-release test** (`scripts/release_smoke.py`, real UI only, no debug hook; API 36 emulator: **15 pass, 0 fail**; an earlier run on API 34 passed everything except two checks that a bug in the script itself mis-read, fixed and re-run on API 36). Covered on the R8 APK: signature verifies (v2) and the signer is printed; installed `0.1.0 (1)`, not debuggable, no `INTERNET`, no debug receiver; launch; capture; persistence after force-stop *and* after process death; edit; Hoy → Foco → Terminar; **four real reminders created through the editor and the time dial**; delivery with the process dead (body + three actions); ABRIR from a dead process → Foco → Terminar; HECHO; +10 MIN; **reboot with the app never reopened → alarm re-armed → delivered → body tap on the cold process opens Hoy**; the snoozed reminder **really came back** ~10 min later; no `FATAL EXCEPTION` for the whole run. Not covered by this rehearsal: a *signed-with-the-production-key* run (**since done, see "Signed-release validation" above: 15/15**), a physical device.

**Accessibility.**
- **Real TalkBack** (the image ships it) was run on the API 36 emulator. Confirmed by its own speech output: the settings icon is announced *"Ajustes, Button, Double-tap to activate"*; the title and notes fields are announced *"Editing, Edit box"* + their placeholder (so they are named); the *"Tarea completada"* / *"Deshacer"* snackbar is announced as an alert; the date picker announces *"Current selection: Tuesday, September 22, 2026"*. Driving TalkBack with adb gestures (swipe-to-next) was **not reliable**, so a full linear reading-order pass with TalkBack was **not** done — the manual 10-minute checklist is in [RELEASE.md](RELEASE.md) §6.
- **Accessibility-tree audit** (what TalkBack consumes) of Hoy, Bandeja, Tareas, Ajustes, editor and Foco at font 1.0 portrait, font 2.0 portrait and font 1.0 landscape: every interactive node is ≥ 48 dp and has a name, **except one defect, now fixed** (D-36): the editor sheet's drag handle was an unnamed 48×35 dp clickable. After the fix the audit reports 0 issues; it is part of the matrix (`a11y`, five screens × two font scales, on all five devices).
- 200 % font, portrait: contact sheets of all six screens inspected by eye — no clipped copy, no overlapping FAB, the editor scrolls above the keyboard, priority chips stack. Landscape at 100 %: inspected for the same six screens.
- Priority is never colour-only (P1/P2/P3 text on every row, card and chip, plus the selected state exposed as `checked`).
- **Foco in landscape (known item): verified.** At 100 % and at 200 % font, Terminar, Posponer and Salir are all reachable by scrolling; tapping Salir returns to Hoy.

**Gate C accepted items re-checked.** GC-02 (check-then-cancel window): code path unchanged (`AndroidReminderNotifier.cancel(taskId)` keyed by task id); the matrix's HECHO / +10 MIN / ABRIR / body flows show no stale-card behaviour on five Android versions; no new evidence, stays accepted. GC-10 (partial export): `SettingsViewModel.export` builds the whole JSON in memory *before* opening the destination stream (mode `"wt"`), so the only exposure is an I/O failure of the provider mid-write; the app's own data is never touched; stays accepted. Editor + deep link: PRODUCT_SPEC/DECISIONS still say the link closes the editor and discards its draft (D-33 #7); unchanged.

### ⬜ NOT verified (do not treat as working)
- **Any physical device**, OEM battery behaviour, reboot *before first unlock* (direct boot), device-transfer restore (A9), exact-alarm revoke/re-grant through the real system screen, blocked reminders channel on a device (JVM/Robolectric only), TIME_SET/zone/DST on a real alarm.
- The **CI workflow on GitHub** (never run; it also has never run on Linux). The production-signed release *is* now verified on the API 36 emulator only (see "Signed-release validation"); on other Android versions and on hardware the signed APK has not been run (the R8 build was run on API 34 as a rehearsal, debug-signed).
- Whether a *genuinely granted* exact-alarm permission survives a reboot: in the tests the grant was made with `adb shell appops set`, and after a reboot the app-op read `default` (denied on Android 14+), so post-reboot reminders were armed in the designed degraded mode (inexact) and still arrived. That is a property of the harness, not proof about the real Ajustes → system-screen grant.
- A full manual TalkBack pass (linear reading order of every screen); keyboard/D-pad focus rings; contrast measured on rendered screens; predictive back for the sheet; 200 % font in landscape (only Foco was checked there).
- Gate C fixes with no device evidence: Hoy/Mañana at midnight, export/import of an at-limit file on the device.
- Light-theme screenshots were inspected by eye only; there are no screenshot tests. Performance targets (NFR-02).
- Activity-level cold/warm start is device-verified (matrix), not a JVM test (the navigation logic itself is tested with `TestNavHostController`).

## 5. Known issues

**Design discrepancies (visual)**
1. The checkbox's visible 22 dp square sits ~9 dp further right than in the prototype because its touch target is 48 dp (design: 44 px buttons were raised to 48 dp on purpose, PRODUCT_SPEC §9.3).
2. Top bar: the design has none; the title-less bar with the settings icon (approved, DD-3) adds 48 dp above every tab heading.
3. Time is shown in the device's 12/24 h format ("8:06 p. m." on the emulator).
4. Overdue/undated dates use platform ICU abbreviations (`sept`, not always `sep`).
5. Bottom bar is 56 dp with a 2 px rule; the design's is slightly shorter.

**Product/engineering**
6. A notification link closes an open editor sheet and discards its draft (D-33 #7) — accepted limitation (Gate C: the modal must close to show the destination). Foco in landscape needs a scroll to reach Salir (system back is the same action) — accepted limitation; **verified reachable** at 100 % and 200 % font (Terminar, Posponer, Salir all reachable by scrolling, Salir returns to Hoy).
7. After the system refuses the notification dialog for good, the first tap on "Permitir" may show nothing before it turns into "Abrir ajustes" (the platform gives no way to know beforehand).
8. Lint warnings (10, none blocking): 5 `UnusedResources` (Lucide icons `chevron-down/up`, `x`, `search`, `trash-2` bundled for the design set but not used by any screen), 2 `UseKtx`, `OldTargetApi` (targetSdk 36 vs 37), `ExportedReceiver` (debug-only command receiver), `ObsoleteSdkInt` (`mipmap-anydpi-v26`).
9. `PRODUCT_SPEC.md` still says "Draft v1"; its requirements were followed as written (deviations: DECISIONS D-33).
10. Gate C residuals **accepted, not fixed**: the check-then-cancel window of a stale action against a just-posted newer card (GC-02: needs revision-specific notification ids); a failed SAF export can leave a partial file in the document the user chose (GC-10); no Activity-level/instrumented test of `MainActivity` link handling or of receiver deadlines (GC-11 — verified on the device instead).
11. From the previous checkpoint and unchanged: Robolectric runs at SDK 34 while `targetSdk` is 36; `./gradlew clean` can fail on Windows while a shell holds `app/build/test-results` as cwd; residual GB-01 window; `DebugCommandReceiver` is debug-only (verified absent from the installed release). Release versioning/signing is wired (D-35) and the owner's production key now signs the release APK (fingerprint in §4). On Android 14+ a fresh install has **exact alarms denied** (D-07): reminders still arrive (69–73 s after creation for a reminder due ≤ 60 s ahead in the emulator test), but with best-effort timing until the user grants "Alarmas exactas" in Ajustes.

## 6. Git state

Branch `claude-pruebas`, HEAD `e4eb933` ("chore: prepare AHORA 0.1.0 release"), which contains the release-prerequisite work (signing wiring, CI fixes, drag-handle a11y fix, D-35/D-36, RELEASE.md, both scripts) on top of the Gate C fixes (`a8dcd8a`). The working tree was **clean** when the signed APK was built and smoke-tested, so the tested APK corresponds to `e4eb933`. The only change since is this `docs/STATUS.md` update, **uncommitted** — I have not committed anything, and **no git tag exists**. `keystore.properties` and the `.jks` are git-ignored / outside the repository and never enter a commit. Note: `gradlew` is tracked with mode `100644`; the workflow works around it with `chmod +x`, but `git update-index --chmod=+x gradlew` is the permanent fix.

## 7. Before Gate D / an APK release

Done: version `0.1.0`/`1` and its policy (D-35) · signing wiring · CI workflow fixed and simulated · device matrix green on API 26, 31, 33, 34, 36 · accessibility audit, one defect fixed · **production keystore configured by the owner, signed APK built, signer SHA-256 matches the expected fingerprint, signed-release smoke 15/15 on the API 36 emulator** (§4).

Still open — **these are the items blocking Gate D / a public APK:**
1. **A physical device**: matrix + release smoke (`--skip-reboot` if it has a lock screen, then the reboot step by hand) + OEM battery behaviour + reboot before first unlock (none has been run on real hardware). Optionally, the signed APK on the other emulators (API 26/31/33/34) for signed-build coverage beyond API 36.
2. **A manual TalkBack pass** on a phone at 100 % and 200 % font ([RELEASE.md](RELEASE.md) §6) — A11Y-04/05 are acceptance requirements and only partially evidenced.
3. **The first GitHub Actions run** (never run; also never run on Linux) — push, then read the result.
4. **Owner decisions, not blockers of evidence:** whether to run Gate D; the `v0.1.0` tag (RELEASE.md §7: only after the items above and Gate D are decided).
5. Optional hardening: screenshot tests; Activity-level tests for cold/warm/restored notification entry.
6. **Key custody:** the `.jks` and both passwords must be backed up in two places (RELEASE.md §2); losing the key makes in-place updates impossible.
