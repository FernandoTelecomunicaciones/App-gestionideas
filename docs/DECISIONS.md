# AHORA — Engineering decisions

Only decisions that are hard to reverse or that shape the system are recorded. Format: **decision → why → what we gave up.** Statuses: **Accepted** (engineering-owned, follows from the brief/handoff) or **Proposed** (needs your approval — listed again in [Open decisions](#open-decisions-requiring-approval)).

Companion docs: [PRODUCT_SPEC.md](PRODUCT_SPEC.md) · [ARCHITECTURE.md](ARCHITECTURE.md) · [STATUS.md](STATUS.md)

## Accepted decisions

### D-01 Single module, layered packages, no use-case classes
**Decision:** one `:app` module; packages `domain` (pure Kotlin) / `data` / `reminders` / `ui`; ViewModel → Repository, no per-action use-case classes.
**Why:** solo, offline app with ~7 screens; multi-module and use-case ceremony add build time and indirection without a second consumer. The pure `domain/rules` package still gives fast JVM tests.
**Gave up:** enforced module boundaries (mitigated by a package dependency rule and lint if it drifts).

### D-02 `minSdk 26`, `targetSdk 36`, `compileSdk 37`
**Why:** API 26 makes `java.time` and notification channels native (no desugaring, no legacy notification code) and covers the practical device base. `targetSdk 36` gives the current runtime behaviours (edge-to-edge, predictive back) instead of debt. **`compileSdk 37`** (amended at M0, 2026-09-20): current stable androidx (Compose 1.12, core 1.19, lifecycle 2.11) declares `minCompileSdk 37`, and AGP 9.4 enforces it; `compileSdk` only unlocks APIs and is independent of `targetSdk`. AGP auto-installed `android-37.0` into the SDK.
**Gave up:** Android 7 and below.

### D-30 Toolchain pinned at M0 (verified by a green build)
AGP **9.4.1** (built-in Kotlin, new DSL) · Gradle **9.7.1** (AGP 9.4 needs ≥ 9.6) · Kotlin **2.4.20** · KSP **2.3.12** · Compose BOM **2026.09.00** · Hilt **2.60.1** · Room **2.8.5** · Navigation **2.10.1** · Lifecycle **2.11.0** · DataStore **1.2.1** · JDK 17 toolchain, run on Android Studio's bundled JDK 21. All resolved from Maven metadata on 2026-09-20 and proven by `assembleDebug` + `testDebugUnitTest` + `lintDebug`.

### D-03 Stack as briefed
Compose + M3, Navigation Compose (type-safe routes), Room, DataStore **Preferences**, Hilt with KSP, Coroutines/Flow. DataStore Preferences (not Proto): four scalar keys. KSP only — kapt is legacy.

### D-04 No `INTERNET` permission; bundled font and icons
**Why:** the strongest possible enforcement of "offline, no analytics, no backend" is that the app *cannot* reach the network; CI fails if the permission appears. Archivo is bundled (OFL) instead of downloadable fonts (which need Play Services and network); Lucide icons are bundled vector drawables.

### D-05 Store floating local date/time, not UTC instants
**Decision:** `dueDate` = epoch-day, `dueTime` = minute-of-day; the fire instant is computed at scheduling time from the current `ZoneId`.
**Why:** "call the bank at 18:00" means 18:00 wherever you are. Storing an instant would silently shift the reminder when the zone changes and needs data migration; floating values need only a `reconcile()`.
**Gave up:** fixed-instant semantics (e.g. "at 18:00 Madrid time while abroad") — not a use case in the design.
**Related rule:** `TimeProvider` evaluates the zone on every call; never cache `Clock.systemDefaultZone()`.

### D-06 Room is the source of truth; AlarmManager is a rebuildable cache with a single-alarm cursor
**Decision:** at most one armed exact alarm (the earliest future reminder). On fire, re-arm the next cursor **first**, then deliver everything due (D-28). `reconcile` is idempotent, non-reentrant, and callable from anywhere via `request()`/`reconcileNow()`.
**Why:** removes per-task PendingIntent bookkeeping and the concurrent-alarm cap; makes every recovery path (boot, zone change, permission change, app start, restore) the same function.
**Gave up:** if the single alarm is lost, all reminders wait for the next reconcile — the same exposure as N alarms after a reboot/force-stop.

### D-07 Exact alarms via `SCHEDULE_EXACT_ALARM` with graceful degradation
**Decision:** use `setExactAndAllowWhileIdle` when allowed, else `setAndAllowWhileIdle`; request access lazily (only when the reminder switch is turned on); do not declare `USE_EXACT_ALARM`.
**Why:** honours "no setup before use" and stays inside Play policy for a task app. `setAlarmClock` (permission-free) was rejected: it presents each reminder as the user's *next alarm* in system UI.
**Gave up:** on Android 14+ the default first-run experience is best-effort timing until the user grants access. **Distribution channel may change this — OD-2.**

### D-08 WorkManager is not used for reminders in V1
**Decision:** AlarmManager delivers; boot/time/zone/permission receivers re-arm with `goAsync()`.
**Why:** WorkManager cannot deliver exact-time reminders; its own boot recovery depends on the same `BOOT_COMPLETED`; a periodic self-heal worker does not survive the force-stop/OEM-kill cases it would target. It adds a dependency and Hilt-Work wiring for no measurable reliability gain.
**Gave up:** the Spec §12 sentence "WorkManager como respaldo tras reinicio". This is an implementation note, not product behaviour. **Revisit** if a deferred background job appears (e.g. scheduled export) or field data shows healable alarm loss.

### D-09 Reconcile triggers
After every reminder-affecting mutation · app start · alarm fired · `BOOT_COMPLETED` · `MY_PACKAGE_REPLACED` · `TIME_SET` · `TIMEZONE_CHANGED` · exact-alarm permission changed · notification-permission result. Redundant on purpose: the cost of a redundant reconcile is one small query; the cost of a missed one is a lost reminder. Receivers are non-exported, use `goAsync()` with an 8 s cap.

### D-10 Missed-reminder policy: deliver once if ≤ 12 h late, else drop silently
**Why:** matches "no guilt" (P-3): no repeats, no escalation, no "you missed 5" summary; also neutralises stale replays after restore/clock changes.
**Gave up:** reminders more than 12 h late are never shown (the task is still on Hoy/Tareas). The 12 h number is a default, cheap to change.

### D-11 Snooze = absolute instant, separate from the due time
`reminderSnoozeUntil` holds "now + 10 min"; the task's due date/time is untouched. Snoozing must not rewrite what the user planned.

### D-12 Notification action wiring
HECHO and +10 MIN → broadcast receivers (immutable PendingIntents, unique `data` URI, no activity started). ABRIR → direct `PendingIntent.getActivity` (never through a receiver — Android 12+ trampoline rule). Post-then-mark delivery with a stable notification id makes at-least-once harmless.

### D-13 Foco deep link: type-safe nav deep link, no manifest scheme, validated
No intent-filter for `ahora://` ⇒ no implicit external entry point; the notification uses an explicit intent. The exported activity can still receive crafted explicit intents, so the target task is always validated (missing/done ⇒ Hoy).

### D-14 Inbox is derived (`!done ∧ no date ∧ no priority`), not a status column
Matches the Prototype. A stored status would have to be kept in sync with date/priority edits and is a classic source of drift. **Gave up:** an item with a priority but no date is no longer "Bandeja" — deliberate ("decided" = it has a date or priority).

### D-15 "Ahora" is computed, not stored
Deterministic ranking (PRODUCT_SPEC §6.2). The Prototype's `isAhora` flag has no UI to set it. Rules unit-tested. **Rule details need approval — OD-4.**

### D-16 Recurrence: new row per occurrence, anchored monthly, no backlog
Completion creates the next occurrence as a new task (history preserved) computed from the series *anchor* (no month-end drift: Jan 31 → Feb 28 → Mar 31), strictly after `max(today, dueDate)`. Undo of a completion removes the generated successor. Deleting an open recurring task ends the series. Completion is idempotent so two paths cannot double-generate.
**Gave up:** an editable "series" concept (edit this/all occurrences) — out of V1 scope.

### D-17 Search in Kotlin, not SQL `LIKE`
SQLite `LIKE` case-folds ASCII only, so "Órdenes" would not match "órdenes". Filter the in-memory pending list with NFD accent stripping + `Locale.ROOT` lowercase. Fine at personal-list scale (hundreds to a few thousand rows); revisit with an FTS/normalised column only if that stops being true.

### D-18 Domain has no Android dependencies; time is injected
Enables JVM tests of every date/zone/DST/recurrence case with a fake `TimeProvider`.

### D-19 Process-death strategy
Durable state in Room/DataStore; transient user input (editor draft, capture text, search query, Foco end timestamp) in `SavedStateHandle`; typed input never depends on async flows (TextField caret stability).

### D-20 Custom components only where M3 cannot honour the design
0 dp radius is a deliberate brand decision (Spec §2). `Shapes` override covers most components; `Switch` (and the checkbox visual) get custom implementations with correct accessibility semantics; date/time picker internals keep their round M3 shapes (documented exception). Rejected: relaxing the radius rule globally.

### D-21 Semantic accessibility colour roles
Introduce `accentFill`, `onAccentFill`, `accentText`, `textSecondary`, `controlOutline` (values in PRODUCT_SPEC §9.2, all taken from the design's own ramps) so the design's own §11 (text ≥ 4.5:1, controls ≥ 3:1) is actually met. **Visible change ⇒ OD-4b.**

### D-22 Undo over confirmation dialogs
Complete, postpone and delete use a Snackbar with **Deshacer** (Spec §10 already prefers Snackbar over modals for reversible actions and allows one action). Action-bearing snackbars use the platform "Short" duration, not 2.2 s.

### D-23 Non-destructive migrations
Schema export on; migration tests from v1; `fallbackToDestructiveMigration` forbidden.

### D-24 Spanish-only V1, all copy in resources
Default `values/` is Spanish; no hard-coded strings; ready for `values-en` later.

### D-25 Edge-to-edge, predictive back
Follows target SDK 36; `BackHandler`/`PredictiveBackHandler` only.

### D-26 Testing philosophy
Fakes over mocks; domain/state-machine/repository written test-first; reminder behaviour also verified on real devices/emulators via the §15.4 matrix; screenshot tests decided at M7.

### D-27 No cloud backup by default *(from Codex review CR-4)*
`dataExtractionRules` exclude everything from cloud backup and allow only device-transfer of the Room/DataStore files; the user-controlled backup is JSON export/import. **Why:** Auto Backup uploads app data to the user's Google account regardless of the missing `INTERNET` permission, which would make the product's own promise ("Tus datos se quedan en tu dispositivo") false. **Gave up:** zero-effort cloud restore on a new phone. Reversible via OD-7.

### D-28 Reminder engine is non-reentrant, arm-first, and tray-aware *(from Codex review CR-1…CR-3, CR-5)*
`request()` (non-blocking, conflated) for mutations vs. `reconcileNow()` (locked) for receivers; the reconciler writes through a trigger-free `ReminderStateStore`; the next alarm is armed **before** delivering, with a 60 s retry cursor while items are undelivered; delivery is one batched DB write; visible reminder notifications are swept against Room; the notification body deep-links to Home. **Why:** each of these was a verified way for the design as first written to deadlock, strand every future reminder, or leave a stale actionable card.

### D-31 A recurring occurrence is not reopenable; undo is guarded *(from Gate B GB-04)*
`reopen` is always refused for an occurrence of a recurring task, and `undoComplete` refuses once the successor it generated is no longer open (completed or deleted). **Why:** there is no series lineage, so a reopen (or an undo replayed after the series moved on) can leave two open branches that each generate their own future occurrences and reminders — a duplicated-reminder bug that only grows. **Gave up:** reopening a completed recurring occurrence from Completadas (Deshacer right after completing still works), and the rare "restart a series whose successor was deleted". **Deferred option:** a nullable `seriesId` column (schema v2 + migration) would let `reopen` be allowed whenever no other occurrence of the series is open; not worth a schema change before there is a UI or user data.

### D-32 Reminder engine amendments from Gate B *(amends D-28/D-29)*
No-op notification actions dismiss only their own revision's card (GB-01); a failed/cut-short candidate read or arm installs a 60 s emergency cursor (GB-02); the real cursor is settled before the cancellable tray sweep (GB-02); `request()` is truly conflated, ≤ 2 passes per storm (GB-05); `+10 MIN` is a no-op while a snooze is pending (GB-06); imported revisions are bounded (GB-03); ABRIR carries `?rev=` so its identity is per revision and it dismisses only its own card (GB-07). Normative text: ARCHITECTURE §18. **Gave up:** nothing user-visible; one extra `AlarmManager` call only on the failure path.

## Open decisions requiring approval

Each has a **recommended default**; implementation follows it until you decide. Everything else in the docs is either taken from the design or an engineering default marked `[G]`.

| ID | Decision needed | Recommended default | Why it matters |
|---|---|---|---|
| **OD-1** | `applicationId`/namespace and launcher name | `app.ahora`, label **AHORA** | Permanent once installed/published. |
| **OD-2** | Distribution channel → exact-alarm permission | Declare `SCHEDULE_EXACT_ALARM` only (Play-policy safe, degrades gracefully). If AHORA is **personal/side-loaded only**, declare `USE_EXACT_ALARM` instead: auto-granted, exact from first run, no prompt. | Determines first-run reminder quality on Android 14+. |
| **OD-3** | **Foco timer semantics** (design shows clock + presets, defines no behaviour) | Entering Foco starts a countdown at the last-used preset; tapping a preset restarts; at 00:00 one soft haptic, no notification/auto-complete. Alternatives: (B) static display only; (C) no timer until a preset is tapped. | Only genuinely ambiguous *behaviour* in the design. |
| **OD-4** | **Ahora / Hoy selection rules** (PRODUCT_SPEC §6.2): priority → date → time; overdue counts as "today"; inbox items never Ahora; nothing decided ⇒ no Ahora | As specified. | This is the heart of "see what matters". |
| **OD-4b** | **Accessibility colour fixes** (PRODUCT_SPEC §9.2): text-bearing red fills use accent-600 + white; small red text deeper in light/lighter in dark; secondary text ≥ 70 %; control outlines ≥ 55 % | Adopt. The design's own tokens fail its own §11 in 7 measured places. | Small, visible change to the approved look. |
| **OD-5** | **Design holes**: task **delete** (with Undo), clearing date/priority, empty-Hoy copy "Nada pendiente.", empty-search copy "Nada coincide." | Add as specified (EDT-09, EDT-03/06, HOY-07, TSK-06). | Without delete the inbox can only grow. |
| **OD-6** | **Ajustes contents** (design lists 5 section names only) and **Datos** scope | PRODUCT_SPEC §5.7; Datos = JSON export/import, in the last milestone, cuttable. | Avoids inventing settings surface later. |
| **OD-7** | Android Auto Backup | **Cloud backup OFF**; direct phone-to-phone transfer allowed for Room + DataStore; manual JSON export/import in Ajustes › Datos. *(Changed after the Codex review: the earlier default — cloud backup on — contradicted the "data stays on device" promise.)* Alternative: enable cloud backup and reword the privacy line. | Privacy promise vs. zero-effort restore. |

## Rejected alternatives (short list)

| Alternative | Rejected because |
|---|---|
| WorkManager as reminder backup (handoff wording) | See D-08 |
| `setAlarmClock` to dodge the exact-alarm permission | Shows reminders as the user's next alarm; semantically wrong; policy-risky |
| One AlarmManager alarm per task | Per-task PendingIntent bookkeeping, alarm cap, harder reconcile (D-06) |
| Store reminder instants in Room | Zone changes would require rewriting data (D-05) |
| Stored `inbox`/`isAhora` status flags | Drift; nothing in the design can set them (D-14, D-15) |
| Multi-module build | No second consumer (D-01) |
| Firebase / any backend / analytics | Excluded by the brief; enforced by no `INTERNET` (D-04) |
| Relaxing the 0 dp radius to use stock M3 Switch | Contradicts a deliberate brand rule (D-20) |

## Codex Phase 1 review — findings log

Run: 2026-09-20, `codex-companion review --scope working-tree` (Codex thread `01a0bf70-c1ca-73b0-9f82-4086c1a14ba2`), against the untracked `docs/` files. **Scope caveat:** the built-in review takes no focus text, so it returned five findings, all against ARCHITECTURE.md's reminder design. It did **not** cover the requested checklist end to end (timezone/DST, process death, Room consistency, permissions, Compose state, testing gaps, design contradictions). I judged each finding independently against the text of the documents before accepting it.

| ID | Codex | My class | Verified? | Resolution |
|---|---|---|---|---|
| CR-1 | [P1] `markDelivered` re-triggers `reconcile()` while `reconcile()` holds its non-reentrant mutex | **CRITICAL** | **Yes.** §4 said every mutation triggers reconcile; §10.4 called a repository mutation inside the locked section. Deadlock or redundant full reconciles as written. | `request()` (non-blocking, conflated) vs `reconcileNow()` (locked); reconciler writes via trigger-free `ReminderStateStore` (ARCH §4, §10.1 rule 3, §10.3, D-28) |
| CR-2 | [P1] Next alarm armed only after the delivery loop; an 8 s receiver timeout strands the cursor | **CRITICAL** | **Yes.** The pseudo-code armed last; a cancelled run left no cursor, so all future reminders wait for an unrelated trigger. | Arm first, 60 s retry cursor while undelivered, one batched mark, re-settle after delivery (ARCH §10.1 rule 6, §10.4, D-28) |
| CR-3 | [P1] Visible notifications are never cancelled when the task is edited/disabled/completed/deleted; stale "+10 MIN" can override a new schedule | **IMPORTANT** (stale card, not data loss; but it contradicted PRODUCT_SPEC §6.3/§6.8) | **Yes.** Only action receivers and ABRIR cancelled; in-app changes did not; `snooze` had no guard. | Repository fast-path cancel + tray sweep against Room; `snoozeIfDelivered` guard; spec sentence added (ARCH §10.4; PRODUCT_SPEC §6.8) |
| CR-4 | [P1] Auto Backup on contradicts "Tus datos no salen de este dispositivo" | **CRITICAL** (an unresolved contradiction with a stated product principle; cheap to fix now, embarrassing later) | **Yes.** OD-7 recommended cloud backup on while PRODUCT_SPEC P-5/§5.7 promised the opposite. | Default flipped to no cloud backup, device-transfer only, JSON export/import; privacy copy qualified (D-27, OD-7, ARCH §12) |
| CR-5 | [P2] Notification body tap on a warm `singleTop` activity leaves the user on the current screen | **IMPORTANT** | **Yes.** The documented handler only processed the Foco data link. | Body intent carries `ahora://home`, handled cold and warm with `popUpTo(Home)` (ARCH §8, §10.6) |

No Codex finding was rejected. Codex's P-levels are its own; the classification above is mine. **Not yet reviewed:** everything in the requested checklist that these five findings did not touch — see review #2 below.

## Architecture Gate resolution (2026-09-20)

The owner approved the gate and set identity/distribution defaults. Recorded resolutions (items not explicitly answered take the recommended default, stated here so it is auditable):

| ID | Resolution | Source |
|---|---|---|
| OD-1 | `applicationId` = namespace = **`com.fernando.ahora`**, name **AHORA** | Owner, explicit |
| OD-2 | V1 = personal APK / GitHub release, **Play-compatible**: declare `SCHEDULE_EXACT_ALARM` only, check capability, degrade gracefully. (A `USE_EXACT_ALARM` build flavor is a cheap later option; not built.) | Owner (distribution) + recommended default |
| OD-3 | Foco timer = option (A) auto-start countdown at last-used preset | Recommended default (owner: "implement the chosen timer behavior from DECISIONS.md") |
| OD-4 / OD-4b / OD-5 / OD-6 | As specified in PRODUCT_SPEC (Ahora rules; accessibility colour roles; delete + clear + provisional copy; Ajustes contents incl. JSON export/import) | Recommended defaults; owner: "apply the approved accessibility corrections" |
| OD-7 | **Cloud backup OFF**; device transfer only | Owner, explicit |
| D-08 | WorkManager not used; approved as part of the architecture | Gate approval |

**Precedence rule amended:** where a recorded, approved engineering decision in this file overrides a platform-component sentence in the Spec (D-08 only), the decision wins.

## Codex review #2 — findings log

Run: 2026-09-20, `codex-companion task` (read-only, custom checklist prompt, thread `01a0bf84-043f-7530-aa20-67b389c7adc5`), 23-point checklist over `/design` and the four docs. Result: **3 CRITICAL, 8 IMPORTANT, 2 OPTIONAL**; verdict "not safe as written; safe after C+I fixed and the WorkManager deviation explicitly approved". Each finding was checked against the text before accepting; all were accepted. The normative fixes are ARCHITECTURE §17 (R2-1…R2-13).

| ID | Sev. | Verified | Resolution |
|---|---|---|---|
| A2-01 | CRITICAL | Yes — candidate snapshot vs concurrent edit; `markDeliveredBatch(ids)` could mark the *new* schedule consumed and leave a stale card | `reminderRevision` token carried through candidates, notifications, marking, actions, sweep (R2-1) |
| A2-02 | CRITICAL | Yes — my "progress is guaranteed" claim was false for a partial prefix or a poison item | Per-item post→conditional mark, failure classification, deadline, retry cursor only while work remains (R2-2) |
| A2-03 | CRITICAL | Yes — in-memory `request()` is not durable across process death, esp. cold `+10 MIN` | Receivers await `reconcileNow` before `finish()`; reminder-affecting mutations await the request job; crash boundary documented (R2-3) |
| A2-04 | IMPORTANT | Yes | Separate `create`/`edit`; edit merges editable fields; `NotFound`/`AlreadyCompleted` (R2-4) |
| A2-05 | IMPORTANT | Yes — D-22/FOC-04 promised Undo for postpone with no operation defined | `PostponeResult` + revision-guarded `undoPostpone` (R2-5) |
| A2-06 | IMPORTANT | Yes | Anchor invariants and transitions (R2-6) |
| A2-07 | IMPORTANT | Partly — governance gap, technical rationale stands | Recorded as approved at the gate; precedence rule amended (R2-7) |
| A2-08 | IMPORTANT | Yes | Atomic import, immediate tray/cursor cleanup, awaited reconcile (R2-8) |
| A2-09 | IMPORTANT | Yes — export via SAF can reach a cloud provider | Privacy copy narrowed to what the app guarantees (R2-9) |
| A2-10 | IMPORTANT | Yes | `TaskRepository` interface in `domain` (R2-10) |
| A2-11 | IMPORTANT | Yes | Test/matrix additions (R2-11, §17.1) |
| A2-12 | OPTIONAL | Yes | Preset precedence (R2-12) |
| A2-13 | OPTIONAL | Yes | Home `restoreState=false` (R2-13) |

### D-29 Reminder engine amendments from review #2
Revision-guarded scheduling state, per-item bounded delivery, awaited reconcile on receiver/mutation paths, create/edit split, postpone rollback, anchor lifecycle, atomic import, repository interface in `domain` — all specified in ARCHITECTURE §17. **Gave up:** the "fire-and-forget after commit" simplicity of D-28; a small latency on reminder-affecting saves (one reconcile: a query and an alarm call).

## Gate A — Codex code review of M1 domain/data (2026-09-20)

Run: `codex-companion task` (read-only, custom prompt) over `core/time`, `domain`, `data`, `di` and the tests. Result: **2 CRITICAL, 7 IMPORTANT, 2 OPTIONAL**. Each finding was verified against the code; all were accepted (GA-01 was accurate for the intentionally stubbed M1 state and is resolved by the real reconciler binding). Tests were written for each.

| ID | Sev. | Verdict | Resolution |
|---|---|---|---|
| GA-01 | CRITICAL | True of the M1 placeholder (`ReminderSync` bound to a no-op); resolved when the reconciler landed | `ReminderSync` bound to `ReminderReconciler`; no-op deleted. `ReminderIntegrationTest` wires repository → real reconciler → real Room and proves every mutation re-plans |
| GA-02 | CRITICAL | Real — import kept revisions, so a stale HECHO/+10 MIN for a reused id could apply to the imported task | Every imported row gets `max(imported, displaced) + 1` (`replaceAll`); test with an old card racing an import |
| GA-03 | IMPORTANT | Real — `replaceAll` delegated validation to callers | `TaskRules.validateForImport`: whole-list, all-or-nothing, 15 invalid classes tested; returns `ReplaceResult.Invalid`, old data untouched |
| GA-04 | IMPORTANT | Real — replaying an old undo token duplicated a recurring series | `CompleteResult.revisionAfter`; `undoComplete` is a guarded no-op unless the exact completion is current |
| GA-05 | IMPORTANT | Real — `undoDelete` used REPLACE | `insertIgnore`; `undoDelete` returns false if the id exists again |
| GA-06 | IMPORTANT | Real — seconds/nanos survived into reminder decisions but were truncated on write | `TaskRules.normalize` truncates to the minute before any reminder maths; import rejects non-minute values |
| GA-07 | IMPORTANT | Real — schema lacked the SQL defaults ARCHITECTURE §6.1 declares | `@ColumnInfo(defaultValue)` on notes/reminderEnabled/reminderRevision/recurrence/done; schema 1 regenerated; `PRAGMA table_info` test |
| GA-08 | IMPORTANT | Real — three tests would pass with the behaviour removed | Rewrote them to seed delivered/snoozed state, advance the clock, assert exact revisions and sync calls |
| GA-09 | IMPORTANT | Partly — the port is reachable in-module, but Kotlin `internal` cannot stop same-module misuse | `ArchitectureBoundaryTest` fails the build if `ui` imports data/Room/ReminderStore/reconciler; domain must stay Android-free |
| GA-10 | OPTIONAL | Accepted (cheap) | Estimates restricted to {15, 30, 60, 120} |
| GA-11 | OPTIONAL | Accepted (cheap) | One clock snapshot (`now`, `zone`, derived `today`) per operation; test uses a `TimeProvider` whose `today()` throws |

## Gate B — Codex code review of the reminder engine (2026-09-20)

Run: `codex-companion task` (read-only, `--effort high`, Codex session `01a0bfd7-6cda-7c20-b0d7-123514ee3cf2`) against commit `9b98e3a`: reminders, notifications, receivers, repository/Room, manifests, R8 config and tests, with a 30-point checklist, the Gate A fixes and five known issues. Result as reported: **4 CRITICAL, 3 IMPORTANT, 0 OPTIONAL**; verdict "Gate B is not approvable". 11 checklist items came back clean, 8 mapped to findings. Every finding was re-verified against the code before acting; **severity below is mine, not Codex's** — I lowered four of its CRITICALs because the failure needs conditions that are not reachable in normal use, but all seven were real and all were fixed (with the noted scope).

| ID | Codex | My class | Verified | Resolution |
|---|---|---|---|---|
| GB-01 | CRITICAL | **IMPORTANT** | Yes by reading. Narrow (tap on revision N, edit, revision N+1 fires *before* the queued broadcast is processed) but the result is a lost, un-repostable reminder card. | Actions dismiss only their own revision's card; no-op actions still reconcile. Test with a newer card already on screen. |
| GB-02 | CRITICAL | **IMPORTANT** | Partly. Real: a failing/cut-short read or arm left the alarm that just fired with no successor, and the sweep ran before settling. Overstated: the `NonCancellable` blocks are a few-ms Room read and binder calls, not an unbounded stall. | Emergency 60 s cursor on failure; cancellable read; settle before a cancellable sweep. **Rejected as OPTIONAL:** batching the sweep's reads and an "explicit degraded result" for total AlarmManager failure. |
| GB-03 | CRITICAL | **IMPORTANT** | Yes, but only through a crafted/corrupt import file (ordinary `+1` overflow needs 2³¹ edits of one task); `resetAll` already clears every card. | `MAX_IMPORT_REVISION` bound in `validateForImport`; wrap branch removed. **Rejected:** a `Long`/opaque revision. |
| GB-04 | CRITICAL | **CRITICAL** | Yes, and it is the known issue K1. Direct `reopen` forks the series through a normal UI path; `undoComplete` can also fork once the successor was completed. The existing test `undoComplete_keepsASuccessorThatWasAlreadyCompleted` was asserting the buggy state. | D-31. Test rewritten; three new tests. |
| GB-05 | IMPORTANT | **IMPORTANT** | Yes — the KDoc said "conflated"; the code launched one pass per call. | Real conflation (queued-job sharing); test counts passes. |
| GB-06 | IMPORTANT | **OPTIONAL, fixed anyway (one predicate)** | Partly. A duplicate `+10 MIN` moves the snooze by milliseconds; a stale one is indistinguishable from a legitimate one (no revision bump). | `AND reminderSnoozeUntil IS NULL`. **Rejected:** revision bump on snooze (would invalidate valid actions). |
| GB-07 | IMPORTANT | **IMPORTANT (engine half) / deferred (navigation)** | Yes — K2/K3. `MainActivity` ignored intents; ABRIR left its card (action buttons do not auto-cancel) and had no revision identity. No crash on the `ahora://` intent. | `NotificationLinkHandler` + ABRIR `?rev=` + activity wiring; verified on the emulator. **Deferred:** the navigation host and `[Home, Focus]` back stack (M3, A7). |

**Known issues:** K1 → fixed (GB-04). K2/K3 → engine half fixed, navigation deferred. **K4 (DebugCommandReceiver) → no defect, and independently verified:** absent from the release merged manifest, the release APK manifest and the release dex (0 hits), present in the debug APK (control). **K5 (R8) → no defect:** `assembleRelease` succeeds, and the signed release APK was installed on the emulator: Hilt resolves, `TIMEZONE_CHANGED` reaches the receiver through the entry point and reconciles, and crafted `ahora://` intents on a warm start do not crash.

**Codex claim not accepted as stated:** "the workspace contains an R8-produced release APK" — true only because I had built it moments earlier; STATUS was correct that Gate B started with the release build never run.

**Gate A fixes re-reviewed:** GA-02 (import revision) had a wrap hole (GB-03); GA-04 (undo guard) did not cover a successor that had since been completed (GB-04). GA-03/05/06/07/10/11: no defect found.

**Tests that passed while production was wrong (Codex list), and what changed:** the request-storm test tolerated N passes → now asserts ≤ 2; the stale-action tests ran before any newer card existed → new test with the newer card on screen; the "kept successor" test asserted the fork → rewritten; the cancellation test never exercised the receiver deadline → emergency-cursor tests added for failed/cancelled read and failed arm. **Not addressed (untestable in the JVM, stays on the device matrix):** `goAsyncBounded` + real `withTimeout` deadline, Robolectric at SDK 34 vs target 36, real process death between commit and arm.
