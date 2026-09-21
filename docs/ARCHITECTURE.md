# AHORA — Architecture

| | |
|---|---|
| Status | **Approved at the Architecture Gate (2026-09-20); implemented through M10. Amendments §17 (review #2), §18 (Gate B), §19 (UI) and §20 (Gate C) supersede the older body text where they conflict.** |
| Date | 2026-09-20 |
| Requirements | [PRODUCT_SPEC.md](PRODUCT_SPEC.md) · Rationale: [DECISIONS.md](DECISIONS.md) (`D-xx`, `OD-x`) |

## 1. Overview and constraints

Native Android, fully **offline-first**, single process, no backend, no accounts, no analytics, no AI, no Firebase. All data lives in one Room database on the device. The riskiest part of the product is **reminder reliability**, so the architecture is shaped around it (§10) and it is built and verified early (§16).

Hard constraints (from the brief and the handoff): Kotlin · Jetpack Compose · Material 3 · Navigation Compose · Room · DataStore · Coroutines/Flow/StateFlow · ViewModel · Hilt · NotificationCompat · AlarmManager. No `INTERNET` permission.

## 2. Platform and toolchain

| Item | Choice | Note |
|---|---|---|
| `minSdk` | **26** | `java.time` and notification channels are native — no desugaring, no pre-O branches. (D-02) |
| `compileSdk` / `targetSdk` | **36** (Android 16) | SDK 35, 36, 36.1 are installed locally. Edge-to-edge and predictive back are enforced/on by default at 36. |
| Language / UI | Kotlin (K2), Compose BOM, Material 3 | |
| DI / DB / prefs | Hilt (KSP), Room (KSP), DataStore **Preferences** | KSP only — no kapt. |
| Navigation | Navigation Compose, **type-safe routes** (`@Serializable`) | |
| Serialization | `kotlinx-serialization` (routes, saved draft, JSON export) | |
| Splash | `androidx.core:core-splashscreen` | Holds the first frame until the theme preference has loaded (no theme flash). |
| Build | Gradle wrapper + `libs.versions.toml`, **single `:app` module** | Exact versions are pinned when the project is scaffolded and verified against the installed SDK. |
| JDK | 17+ toolchain | The machine's default `java` is **1.8** and cannot build this; use Android Studio's bundled JDK 21 via user-level `JAVA_HOME`/`~/.gradle/gradle.properties` — never a machine path in the repo. |

## 3. Package structure (single module)

```
com.fernando.ahora              // applicationId = namespace (OD-1, resolved at the gate)
├─ AhoraApp.kt                  // @HiltAndroidApp; app scope; dynamic time/zone receiver
├─ MainActivity.kt              // single Activity, singleTop; hosts NavHost + editor sheet
├─ di/                          // Hilt modules (Database, Prefs, Time, AppScope, Reminders)
├─ core/time/                   // TimeProvider (now/zone evaluated per call), ClockTicker
├─ domain/
│  ├─ model/                    // Task, Priority, Recurrence, TaskDraft, HoyPlan, TaskFilter
│  ├─ TaskRepository.kt         // INTERFACE (ui depends on this, never on data) — see §17
│  └─ rules/                    // TodayPlanner, TaskFilters, TextNormalizer, RecurrenceCalculator,
│                               // ReminderPlanner, GreetingBand   (pure Kotlin + java.time, no Android)
├─ data/
│  ├─ local/                    // AhoraDatabase, TaskEntity, TaskDao, Converters, migrations
│  ├─ prefs/                    // AppPreferences (DataStore)
│  └─ TaskRepositoryImpl.kt     // Room-backed implementation, bound in Hilt; the ONLY write path
├─ reminders/                   // ReminderScheduler, ReminderReconciler, ReminderDeliverer,
│                               // NotificationFactory, receivers, PermissionState
└─ ui/
   ├─ theme/                    // AhoraTheme, AhoraColors, Shapes(0dp), Typography(Archivo)
   ├─ components/               // AhoraSwitch, AhoraCheckbox, AhoraButton, PriorityTag, TaskRow…
   ├─ navigation/               // routes, AhoraNavHost, deep-link handling
   ├─ home/ inbox/ tasks/ editor/ focus/ settings/   // Screen + ViewModel + UiState each
```

Dependency rule: `ui → domain ← data`; `reminders → domain + data`. **`domain` has no Android imports**, so every rule is a fast JVM unit test.

## 4. Layering and data flow

```
Compose screen (stateless) ──events──▶ ViewModel ──▶ TaskRepository ──▶ Room (single source of truth)
        ▲                                   │                │
        └──── StateFlow<UiState> ◀──────────┘                └──▶ ReminderReconciler ──▶ AlarmManager
                       ▲                                                   ▲
   Room Flow + ClockTicker + prefs ──combine──▶ domain rules (TodayPlanner…)  │ (also boot/time/zone/permission receivers)
```

- Unidirectional data flow. Screens are stateless composables taking `UiState` + callbacks; a thin `*Route` composable owns the ViewModel.
- ViewModels expose `StateFlow<UiState>` via `stateIn(viewModelScope, WhileSubscribed(5_000), Loading)`; collected with `collectAsStateWithLifecycle()`.
- **Every user-facing mutation goes through `TaskRepository`** (a Room `@Transaction` where it touches more than one row), then, **after commit**, calls `ReminderReconciler.request(reason)` and — for reminder-affecting mutations — **awaits the returned job** (bounded), so "saved" means "cursor armed" (§17 R2-3). `request` never takes the reconciler's lock in the caller's context. DAO write methods are `internal`. The reconciler's own bookkeeping writes use a separate trigger-free `ReminderStateStore`, so a reconcile can never re-trigger itself (§10.1 rule 3).
- No use-case classes: the pure rules in `domain/rules` plus the repository are enough (D-01).
- **Quick capture** = Bandeja field → `InboxViewModel.capture(title, optionalDate, optionalPriority)` → `repository.create(...)`. **FAB** = `EditorViewModel.openNew()` (activity-scoped so any tab can open the same sheet).

## 5. Domain rules (pure Kotlin)

| Unit | Responsibility |
|---|---|
| `TimeProvider` | `now(): Instant`, `zone(): ZoneId = ZoneId.systemDefault()` **evaluated on every call**. Never cache `Clock.systemDefaultZone()` — it freezes the zone at creation. Tests inject a fake. |
| `TodayPlanner` | PRODUCT_SPEC §6.2 → `HoyPlan(ahora, today3, restCount, rest)`. |
| `TaskFilters` | The 4 filters + sort orders (§5.3) + `TextNormalizer` search (NFD, strip diacritics, lowercase with `Locale.ROOT`). Runs in memory on the pending list (D-17). |
| `RecurrenceCalculator` | Next occurrence (§6.5), anchor-based monthly. |
| `ReminderPlanner` | `nextFireInstant(task, zone)`; DST gap/overlap; missed-reminder (12 h) classification; snooze. |
| `GreetingBand` | Time band → string resource key. |

**Pitfall recorded:** `DatePickerState.selectedDateMillis` is **UTC midnight**. Convert with `Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()` — never the device zone (off-by-one-day west of UTC). Covered by a test.

## 6. Room data model

Database `ahora.db`, version 1, `exportSchema = true` (schemas committed under `app/schemas/`). **`fallbackToDestructiveMigration` is forbidden** (NFR-04); every version bump ships a tested `Migration`/`AutoMigration`.

### 6.1 Table `tasks`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | | Also the notification id (as Int; ids stay ≪ 2³¹). |
| `title` | TEXT | no | trimmed, non-empty (repository-enforced) |
| `notes` | TEXT | no, default `''` | |
| `dueDate` | INTEGER | yes | `LocalDate.toEpochDay()` — floating local date (D-05) |
| `dueTime` | INTEGER | yes | minute-of-day 0‥1439 |
| `reminderEnabled` | INTEGER (bool) | no, default 0 | |
| `reminderFiredAt` | INTEGER | yes | epoch ms; non-null ⇒ the due-time reminder for the *current* schedule is consumed |
| `reminderSnoozeUntil` | INTEGER | yes | epoch ms (absolute instant) |
| `priority` | INTEGER | yes | 1‥3, null = undecided |
| `recurrence` | TEXT | no, default `'none'` | stable codes `none/daily/weekly/monthly` via converter |
| `recurrenceAnchor` | INTEGER | yes | epoch day of the series' first due date |
| `estimatedMinutes` | INTEGER | yes | data-only in V1 |
| `listName` | TEXT | yes | data-only in V1 |
| `done` | INTEGER (bool) | no, default 0 | |
| `completedAt` | INTEGER | yes | epoch ms |
| `createdAt`, `updatedAt` | INTEGER | no | epoch ms from `TimeProvider` |

Indices: `(done, dueDate)`, `(done, priority)`. Invariants (PRODUCT_SPEC §4.1) are enforced in `TaskRepository` (Room cannot express `CHECK` on annotated entities); DAOs are not reachable from `ui`.

### 6.2 Queries

`observePending(): Flow<List<Task>>` (all pending; planner/filters/search run in memory — a personal task list is hundreds, not millions of rows) · `observeCompleted(): Flow<List<Task>>` (`ORDER BY completedAt DESC`) · `observeById` / `getById` · `getReminderCandidates()` = `done=0 AND reminderEnabled=1 AND dueDate IS NOT NULL AND dueTime IS NOT NULL`.

### 6.3 Transactional operations (all in `TaskRepository`)

| Operation | Atomic steps |
|---|---|
| `create/save(draft)` | validate → normalise (§4.1 rules) → reset reminder state if date/time/toggle changed → if resulting fire instant ≤ now set `reminderFiredAt = now` → upsert |
| `complete(id)` | **no-op if already done** → set done/completedAt, clear snooze → if recurring insert successor (new row) → return `CompletionResult(successorId?)` |
| `undoComplete(id, successorId?)` | reopen + delete successor; **refuses if the successor is no longer open (GB-04)** |
| `reopen(id)` | done=false; if fire instant past ⇒ mark consumed. **Always refused for recurring rows (GB-04, D-31)** |
| `postpone(id)` | `dueDate = today+1` (zone at call time), reset reminder state |
| `delete(id)` / `undoDelete(task)` | delete / re-insert with the same id |
| `snoozeIfDelivered(id, now)` *(ReminderStateStore, trigger-free)* | `reminderSnoozeUntil = now + 10 min` only if `!done ∧ reminderEnabled ∧ reminderFiredAt != null`; otherwise no-op |
| `markDeliveredBatch(ids, now)` *(ReminderStateStore, trigger-free)* | one transaction: `reminderFiredAt = now`, `reminderSnoozeUntil = null` for all ids |

### 6.4 Consistency risks and mitigations

| Risk | Mitigation |
|---|---|
| Two paths complete the same recurring task (notification + Foco) → duplicate successor | `complete` is idempotent inside one transaction (checks `done`). Tested with concurrent callers. |
| Process dies between DB commit and alarm re-arm | Room is truth; alarm is a derived cache. Next receiver/app start runs `reconcile()` (§10.4). |
| Alarm fires for a task deleted/completed since arming | Receiver **re-reads the DB**; it never trusts the intent payload. |
| Notification posted but mark-consumed fails (or reverse) | **Post first, then mark.** Same notification id ⇒ a repost replaces itself; at-least-once is harmless, at-most-once could lose a reminder. |
| Accent/case-insensitive search impossible in SQLite `LIKE` (ASCII-only case folding) | Search in Kotlin over the in-memory list (D-17). |
| Schema drift / data loss on upgrade | Exported schemas + `MigrationTestHelper` tests from v1; no destructive fallback. |
| Restore from backup replays stale reminders | 12 h missed-reminder rule (§10.4). |

## 7. Preferences (DataStore Preferences)

Keys: `theme` (SYSTEM/LIGHT/DARK), `tasksFilter`, `focusPreset` (15/25/45), `keepScreenOnInFocus`. A `corruptionHandler` falls back to defaults. First read gates the splash screen. **No task data in DataStore.**

## 8. Navigation

Type-safe routes: `Home`, `Inbox`, `Tasks`, `Focus(taskId: Long)`, `Settings`. `Home` is the start destination. Bottom-bar switching uses the standard `popUpTo(Home){saveState}` / `launchSingleTop` / `restoreState`.

| Concern | Design |
|---|---|
| Chrome visibility | `Scaffold` at the root derives bottom bar + FAB visibility from the current destination (PRODUCT_SPEC §3.2). Focus/Settings hide both. |
| Edit/create sheet | `ModalBottomSheet` composed **once at the root**, driven by the activity-scoped `EditorViewModel`; it is state, not a destination. |
| Deep links | `navDeepLink<Focus>(basePath = "ahora://focus")` and `navDeepLink<Home>(basePath = "ahora://home")` (notification body tap → Hoy, clearing the stack above it). **No manifest intent-filter for the scheme** — no implicit external entry point. The notification builds an explicit `Intent(ACTION_VIEW, uri, ctx, MainActivity::class)` with `FLAG_ACTIVITY_NEW_TASK`; Navigation matches `intent.data`. |
| Robustness | `FocusViewModel` loads the task; **missing or already-done ⇒ pop to Home silently**. Any app could send that intent to the exported activity, so the id is always validated. |
| Re-delivery | `MainActivity` is `singleTop`; handle `onNewIntent` via `addOnNewIntentListener { navController.handleDeepLink(it) }`. Navigation marks handled intents, so rotation/process restore does not re-navigate. |
| Back stack | Deep link into Foco must synthesise `[Home, Focus]` so back lands on Hoy (verified in the spike, §16 A7). |
| Notification cleanup | Opening Foco via ABRIR cancels that task's notification (action buttons do not auto-cancel). |
| Terminar / Posponer | Navigate to `Home` with `popUpTo(Home)`. Salir / back = `popBackStack()`. |
| Predictive back | Enabled at target 36; use `BackHandler`/`PredictiveBackHandler`, no `onBackPressed`. |

## 9. UI state, lifecycle and process death

### 9.1 Where each piece of state lives

| State | Lives in | Survives rotation | Survives process death |
|---|---|---|---|
| Tasks, reminders | Room | ✔ | ✔ |
| Theme, last Tareas filter, Foco preset | DataStore | ✔ | ✔ |
| Editor draft + mode (new/edit id) | `EditorViewModel` + `SavedStateHandle` (`@Serializable`) | ✔ | ✔ |
| Bandeja capture text + capture chips | `InboxViewModel` `SavedStateHandle` | ✔ | ✔ |
| Search query, "También pendiente" expanded | `SavedStateHandle` / `rememberSaveable` | ✔ | ✔ |
| Foco end timestamp + preset | `FocusViewModel` `SavedStateHandle` (wall-clock epoch ms; remaining = end − now) | ✔ | ✔ |
| Picker dialogs, disclosure, snackbar | composition / `rememberSaveable` | mostly | ✘ (fine) |
| NavController back stack | `rememberNavController` (saved) | ✔ | ✔ |

Swiping the app from recents discards saved state — expected Android behaviour.

### 9.2 Compose state rules

- `TextField` backing state is updated **synchronously** (`MutableStateFlow.value =` / `TextFieldState`) — never round-tripped through Room, DataStore or async Flow operators (prevents caret-jump/lost keystrokes).
- Emit `Loading` until first data so empty states never flash (HOY-08).
- `LazyColumn` uses stable `key = { it.id }` and `contentType`; UI models are `@Immutable`/`ImmutableList`-style; derived values via `derivedStateOf`.
- One-shot effects (Snackbar, navigation) go through a `Channel<UiMessage>` collected in a lifecycle-aware `LaunchedEffect` at the root; an undo is a token (`UndoToken(kind, ids)`) resolved by the ViewModel, not a captured lambda. A lost Snackbar is acceptable; a lost data write is not (writes never depend on the UI collector).
- `ClockTicker` emits at each minute boundary only while collected (STARTED); it drives "today", greeting and DST/zone changes for Hoy.

## 10. Reminder architecture

### 10.1 Principles

1. **Room is the source of truth.** AlarmManager is a *derived cache* that can be rebuilt at any time.
2. **Single-alarm cursor.** At most **one** exact alarm is armed: the earliest future reminder. When it fires, *all* due reminders are delivered and the cursor re-arms to the next one. This avoids per-task PendingIntent bookkeeping and the per-app concurrent-alarm cap (D-06).
3. **Idempotent and non-reentrant `reconcile`.** Two entry points: `request(reason)` — non-suspending, conflated, used by repository mutations and UI, **never awaited**; and `reconcileNow(reason)` — suspending, takes the `Mutex`, used by receivers that must finish before `goAsync()` ends. The reconciler writes only through the trigger-free `ReminderStateStore`; nothing inside a reconcile may call a triggering repository method (otherwise it would wait on its own lock).
4. **The receiver re-reads the DB**; intent extras carry no task state.
5. **Floating local time** stored; instants computed at scheduling time from the *current* zone (D-05).
6. **Arm first, deliver second.** The next cursor is persisted with `AlarmManager` *before* any notification work, so a slow, failed or timed-out delivery can never strand the cursor.
7. **The notification tray is part of the state.** Visible reminder notifications are reconciled against Room (§10.4 sweep), so an edited/completed/deleted/moved task can never leave a stale, actionable card.

### 10.2 Reminder state (per task)

| Fields | Meaning |
|---|---|
| `reminderEnabled`, `dueDate`, `dueTime`, `!done` | A reminder is *defined* |
| `reminderFiredAt = null` | The due-time reminder is still pending |
| `reminderSnoozeUntil != null` | A snooze is pending (overrides the due-time one) |

`nextFireInstant = if (!defined) null else if (snoozeUntil != null) snoozeUntil else if (firedAt != null) null else zoned(dueDate, dueTime).toInstant()`.

Transitions: edit of date/time/toggle ⇒ `firedAt = null, snooze = null` (then "already past ⇒ consumed"); fire ⇒ `firedAt = now, snooze = null`; **+10 MIN** ⇒ `snooze = now+10 min`; complete ⇒ clears snooze and (done) drops out; reopen ⇒ consumed if past; postpone ⇒ reset.

### 10.3 Components

| Component | Role |
|---|---|
| `ReminderScheduler` (interface + `AlarmManagerScheduler`) | `arm(instant)` / `cancel()`. Uses `setExactAndAllowWhileIdle(RTC_WAKEUP, …)` when `canScheduleExactAlarms()` (always true below API 31), else `setAndAllowWhileIdle`; a `SecurityException` at the call also falls back. Single `PendingIntent` (explicit component, `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`). |
| `ReminderReconciler` | `request(reason)` (non-blocking, conflated) and `reconcileNow(reason)` (suspending, serialised by a `Mutex`). Owns arm-first delivery and the notification sweep (§10.4). |
| `ReminderStateStore` | Trigger-free writes used only by the reconciler and action receivers: `markDeliveredBatch(ids, now)`, `snoozeIfDelivered(id, now)`. |
| `ReminderDeliverer` | Builds and posts notifications; called by the reconciler for due items. |
| `NotificationFactory` | Channel creation (idempotent), builder, PendingIntents. |
| `ReminderAlarmReceiver` | Alarm fired → `reconcileNow("alarm")`. |
| `ReminderActionReceiver` | HECHO / +10 MIN. |
| `BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` → `reconcileNow`. (Not `LOCKED_BOOT_COMPLETED`: Room lives in credential-encrypted storage.) |
| `TimeChangeReceiver` | `TIME_SET`, `TIMEZONE_CHANGED` (manifest **and** dynamic while the process is alive). |
| `ExactAlarmPermissionReceiver` | `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` → `reconcileNow`. |

Receivers are Hilt `@AndroidEntryPoint`, `exported="false"`, use `goAsync()` + the application scope with a hard `withTimeout(8 s)` and always `finish()`.

### 10.4 `reconcile(reason)`

```
reconcileNow(reason) = lock(mutex):
  now, zone = timeProvider
  candidates = store.getReminderCandidates()
  fire(t)    = nextFireInstant(t, zone)
  due        = candidates with fire <= now + 2s
  future     = the rest
  next       = min(future by fire)

  // 1. ARM FIRST. If anything below is slow, throws, or is cancelled by the receiver's
  //    8 s timeout, a cursor already exists and no future reminder is stranded.
  //    While undelivered items remain, the cursor is a short retry.
  arm( if due.isEmpty() then next else min(next, now + 60s) )     // or cancel() when both are null

  // 2. Deliver (bounded work: one post per due item, ONE batched DB write)
  post = due where (now - fire) <= 12h and notificationsEnabled
  for t in post: notifier.post(t)                                  // post FIRST (same id => an idempotent repost)
  store.markDeliveredBatch(due.ids, now)                           // then mark all due (incl. >12h => silent) in one transaction

  // 3. Sweep the tray against Room
  sweepNotifications()

  // 4. Settle on the real next cursor
  if due.isNotEmpty(): arm(next) else nothing
```

If the timeout hits during step 2, items not yet marked stay `firedAt = null` and still past-due, so the 60 s retry cursor delivers them (subject to the 12 h rule). Progress is guaranteed; nothing waits for an unrelated trigger.

**Sweep rule (stale notifications).** For every active notification in the `reminders` channel (id = task id): keep it only if the task exists ∧ `!done` ∧ `reminderEnabled` ∧ `reminderFiredAt != null` ∧ `reminderSnoozeUntil == null`; otherwise cancel it. Because an edit of date/time/toggle resets `reminderFiredAt`, a card for a moved, disabled, completed or deleted reminder disappears at the next reconcile. As a fast path the repository also cancels the task's notification directly, after commit, on `complete`, `delete`, `postpone` and on edits that change date/time/reminder. Action receivers re-validate too: **HECHO** is a no-op on a done/missing task; **+10 MIN** applies only if `reminderEnabled ∧ !done ∧ reminderFiredAt != null`, so a stale card can never overwrite a newer schedule.

Triggers (all via `request` unless noted): after every repository mutation touching reminder fields · app start (`Application.onCreate` + `MainActivity.onStart`, `reconcileNow`) · alarm fired, boot, package replaced, `TIME_SET`, `TIMEZONE_CHANGED`, exact-alarm permission changed (all `reconcileNow` from receivers) · notification-permission result.

Missed-reminder policy: **≤ 12 h late ⇒ deliver once; older ⇒ mark consumed silently**. This also protects against a stale replay after a restore/import or a clock jump.

### 10.5 Time computation

- `zoned(dueDate, dueTime)` = `ZonedDateTime.of(date, time, zone)`. Gap ⇒ `java.time` moves forward past the gap; overlap ⇒ earlier offset (matches PRODUCT_SPEC §6.1). Tests cover `Europe/Madrid` 2026-03-29 (gap) and 2026-10-25 (overlap), plus a zone change mid-schedule.
- The DB stores no instants derived from a zone (except `firedAt`/`snoozeUntil`, which are true absolute instants), so a timezone change needs **no data migration**, only `reconcile()`.
- Past-due at edit time ⇒ consumed, no retroactive notification.

### 10.6 Notification design

| Aspect | Decision |
|---|---|
| Channel | `reminders`, name "Recordatorios", `IMPORTANCE_HIGH`; created at app start; user tunes sound in system settings |
| Builder | `NotificationCompat`, category `CATEGORY_REMINDER`, `setAutoCancel(true)`, `setOnlyAlertOnce(true)`, `setShowWhen(false)`, visibility private, small icon monochrome |
| Body | "Vence a las HH:MM" from `dueTime` in the device time format |
| Content intent | `PendingIntent.getActivity` with an **explicit `ahora://home` deep-link intent** (`FLAG_ACTIVITY_NEW_TASK`), handled for cold **and** warm starts (`onNewIntent`) by navigating to `Home` with `popUpTo(Home)`. A plain launcher intent is not enough: when the `singleTop` activity is already on Tareas/Ajustes/Foco it would only deliver `onNewIntent` and leave the user where they were. |
| **HECHO** | `PendingIntent.getBroadcast` → `ReminderActionReceiver`, action + `data = ahora://reminder/{id}/done`, immutable. Completes via repository (idempotent), cancels notification, re-plans. **Never starts an activity** (Android 12+ trampoline restriction). |
| **+10 MIN** | Broadcast, `…/snooze`. Sets snooze, cancels notification, re-plans. |
| **ABRIR** | **Direct** `PendingIntent.getActivity` (never via a receiver) with the Foco deep-link intent `ahora://focus/{id}?rev={revision}` (a per-revision identity, GB-07). `NotificationLinkHandler` validates it (missing/done ⇒ Home) and cancels the card **only if the tray card's revision equals `rev`**. |
| PendingIntent identity | Unique `data` URI per task+action so `filterEquals` distinguishes them; all `FLAG_IMMUTABLE`. |
| Grouping | None; the system auto-bundles. |

### 10.7 Platform constraints (documented limitations, not bugs)

| Constraint | Effect | Handling |
|---|---|---|
| Android 14+: `SCHEDULE_EXACT_ALARM` is **denied by default** for new installs targeting ≥ 33 | Default first run = best-effort alarm | Inline hint + settings deep link; degrade, never block (PRODUCT_SPEC §8.1). **OD-2** |
| Revoking exact-alarm access stops the app and cancels its alarms | Reminders lost until re-grant | Permission-changed receiver + reconcile on next start |
| Reboot clears alarms | | `BootReceiver` (D-09) |
| **Force-stop** (or OEM "swipe away kills alarms") cancels alarms and blocks receivers until the next manual launch | Reminders lost | Cannot be fixed by an app; reconcile at next launch (12 h rule); one plain sentence in Ajustes |
| Doze throttles allow-while-idle alarms (~one per 9 min per app) | Two reminders < 9 min apart may deliver the second late | Accepted, documented |
| Notifications denied | Nothing shows | Hint in editor + Ajustes; reminder consumed silently |
| Alarm survives app update (platform behaviour) | — | `MY_PACKAGE_REPLACED` reconcile anyway (cheap, idempotent) |

### 10.8 WorkManager

Not used for reminder delivery or reboot recovery in V1 (D-08). WorkManager cannot deliver exact-time reminders, and its own boot recovery relies on the same `BOOT_COMPLETED` we already handle; a periodic "self-heal" worker cannot survive the force-stop/OEM cases it would be meant to cover, and costs a dependency plus Hilt-Work wiring. It becomes appropriate if (a) a deferred, retry-able background job is added (e.g. scheduled export), or (b) field evidence shows alarms disappearing in cases a periodic reconcile would heal. The Spec §12 note "WorkManager como respaldo tras reinicio" is deliberately not followed (PRODUCT_SPEC DD-13).

## 11. Permissions and manifest

| Declaration | Reason |
|---|---|
| `POST_NOTIFICATIONS` | Requested in context (first reminder toggle) on API 33+ via `ActivityResultContracts.RequestPermission`; after two denials the system stops prompting ⇒ deep link to app notification settings |
| `SCHEDULE_EXACT_ALARM` | Special access; `AlarmManager.canScheduleExactAlarms()`; `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` to grant. `USE_EXACT_ALARM` **not** declared (Play policy limits it to alarm/timer/calendar apps) — **OD-2** |
| `RECEIVE_BOOT_COMPLETED` | Reboot recovery |
| **Not declared** | `INTERNET`, `WAKE_LOCK`, `VIBRATE`, `FOREGROUND_SERVICE*`, `USE_FULL_SCREEN_INTENT`, storage. (Foco end haptic uses Compose `HapticFeedback`, no permission; export/import uses the system file picker.) |

Manifest: `MainActivity` exported + LAUNCHER, `launchMode="singleTop"`; every other component `exported="false"`; a CI/lint check fails the build if `INTERNET` appears in the merged manifest.

## 12. Backup and data safety

Default recommendation (**OD-7, D-27**): **no cloud backup**, because Android Auto Backup uploads app data to the user's Google account and would contradict the product promise that data stays on the device (PRODUCT_SPEC P-5, §5.7). Concretely: `dataExtractionRules` **excludes everything from `<cloud-backup>`** and includes only the Room files + DataStore in `<device-transfer>` (direct phone-to-phone migration, user-initiated); for Android ≤ 11, where both mechanisms are the same, `fullBackupContent` excludes everything (behaviour to verify at M7, A9). The user-controlled backup is the manual JSON export/import (Ajustes › Datos, OD-6): versioned (`schemaVersion`), validated on import, import replaces after an explicit confirmation. After any transfer or import the first launch runs `reconcileNow()` (alarms are never restored) and the 12 h rule prevents stale-reminder floods. If you later choose to enable cloud backup, the privacy line in Ajustes › Acerca de must be changed to say so.

## 13. Theming and Compose components

`AhoraTheme` supplies an M3 `ColorScheme` plus an `AhoraColors` `CompositionLocal` with the semantic roles the design needs (`accentFill`, `onAccentFill`, `accentText`, `textSecondary`, `controlOutline`, `divider`, tag colours — see PRODUCT_SPEC §9.2 / **OD-4b**). `Shapes` are all `RectangleShape`. Typography is Archivo from `res/font` (3 static weights, OFL licence bundled). Custom composables where M3 cannot honour the design: `AhoraSwitch` (square, `Role.Switch`), `AhoraCheckbox` (22 dp visual in a 48 dp target), `AhoraButton` (flush-left label variants), `PriorityTag`. M3 `NavigationBarItem` runs with a transparent indicator; `ModalBottomSheet`, FAB, `SegmentedButton`, `Snackbar` and picker dialogs take a 0 dp shape parameter. Lucide icons are bundled vector drawables.

## 14. Errors and logging

Repository methods return typed results; a failed write surfaces one generic Snackbar ("No se pudo guardar") and never crashes the receiver path. A `Logger` wrapper logs to `Logcat` in debug only; **release builds log no task text**. No third-party logging/crash SDK.

## 15. Testing strategy

### 15.1 Pyramid

| Layer | Tooling | Scope |
|---|---|---|
| **Domain unit** (JVM) | JUnit + `kotlinx-coroutines-test` + Turbine; hand-written fakes over mocks | `TodayPlanner` (empty, overdue, ties, inbox-only, fallback), filters/sort, search normalisation (ñ, á, uppercase), recurrence (month-end, leap year, late completion, early completion), `ReminderPlanner` (gap/overlap, snooze, consumed, 12 h rule), greeting bands, `DatePicker` UTC→local conversion |
| **Repository/Room** (instrumented or Robolectric) | `Room.inMemoryDatabaseBuilder`, `MigrationTestHelper` | Invariants, every transaction in §6.3, idempotent `complete`, undo semantics, migration tests from v1 |
| **Reminders logic** (JVM) | Fake `ReminderScheduler`, fake `TimeProvider` | Single-cursor arming/cancelling, **arm-before-deliver ordering (cursor exists even if delivery throws/times out)**, **no re-entrancy: a reconcile never triggers or awaits another reconcile (deadlock test)**, batched mark, 60 s retry cursor, **stale-notification sweep (edit/disable/complete/delete/postpone; stale +10 MIN is a no-op)**, post-then-mark, permission matrix |
| **Receivers/notifications** (instrumented) | `androidx.test`, `NotificationManager` shadow/queries | Action PendingIntents (immutable, unique), HECHO/+10 MIN idempotence, no activity from receiver |
| **ViewModel** | Turbine + `SavedStateHandle` | Saved/restored drafts, capture text, Foco end time |
| **Compose UI** | `createComposeRule`, `StateRestorationTester` | Sheet validation, disabled Guardar, conditional Recordatorio switch, chip toggle-to-clear, Hoy states, Foco actions, rotation/process-death restoration |
| **Navigation** | `TestNavHostController` | Deep link to Foco valid / missing / done; back stack `[Home, Focus]`; bottom-bar/FAB visibility |
| **Accessibility** | Accessibility Test Framework checks in Compose tests + manual TalkBack | Roles/states, 48 dp targets, font scale 1.0/2.0, contrast tokens asserted numerically |
| **Visual** | Screenshot tests (Paparazzi/Roborazzi — decide at M7) | Light/dark × font scale for each screen and the sheet |
| **Static** | Android Lint (`abortOnError`), manifest check | No `INTERNET`, exported components, missing translations |

### 15.2 Test-first rule

`domain/rules`, the state machine in §10.2 and the repository transactions are written test-first. They contain every date, time-zone, recurrence and reminder edge case, so they must be provable without a device.

### 15.3 CI

`./gradlew testDebugUnitTest lintDebug assembleDebug` on every push (GitHub Actions, added in M0). Instrumented tests run on an emulator job once system images are available.

### 15.4 On-device reminder matrix (manual + `adb`, required before release; also the M3 spike)

| # | Scenario | How |
|---|---|---|
| 1 | Exact on-time delivery, screen off | Reminder 2 min ahead |
| 2 | Reboot recovery | Reminder ahead → `adb reboot` |
| 3 | Time zone change | Set reminder → change device zone → verify fires at new local time |
| 4 | Manual clock change | Move time forward past a due reminder → ≤ 12 h late delivery once; > 12 h dropped |
| 5 | Doze | `adb shell dumpsys deviceidle force-idle` |
| 6 | Exact-alarm denied | `adb shell appops set <pkg> SCHEDULE_EXACT_ALARM deny` → degrade, hint, later grant → re-arm |
| 7 | Notifications denied / revoked | Hint shown; no crash; consumed silently |
| 8 | Force-stop | Alarm lost (expected) → reopen → reconcile |
| 9 | App update in place | Alarm intact or re-armed |
| 10 | HECHO / +10 MIN / ABRIR with app killed | Process cold-started by receiver; back stack `[Home, Focus]` |
| 11 | Recurring task completed from notification | Exactly one successor |
| 12 | Backup restore | No stale flood |
| 13 | Devices | Android 12, 14, 16 minimum |

## 16. Implementation order and assumptions to verify

Risk-first: **M0** scaffold/tooling/CI → **M1** domain + Room + repository (test-first) → **M2 reminder spike on a device/emulator** (validates the assumptions below before UI investment) → **M3** UI shell + Hoy/Bandeja/Tareas → **M4** editor sheet + pickers + permission hints → **M5** Foco + deep links → **M6** Ajustes/Datos → **M7** a11y, screenshots, perf, R8, backup rules → **M8** release candidate. Detail in [STATUS.md](STATUS.md).

**Platform assumptions to verify in the spike** (nothing here can be verified today — no emulator image or device is available):

| # | Assumption |
|---|---|
| A1 | `canScheduleExactAlarms()` is `false` by default on a fresh Android 14+ install targeting ≥ 33 |
| A2 | Alarms are cleared on reboot; `BOOT_COMPLETED` reaches a non-exported receiver |
| A3 | Alarms survive an in-place app update |
| A4 | Revoking exact-alarm access kills the process and clears alarms; the state-changed broadcast is delivered when re-granted |
| A5 | `ZoneId.systemDefault()` is current in a running process after a zone change |
| A6 | `TIME_SET` / `TIMEZONE_CHANGED` manifest receivers are delivered on 26+ (else rely on dynamic receiver + start-up reconcile) |
| A7 | A `NEW_TASK` deep-link PendingIntent yields back stack `[Home, Focus]` |
| A8 | Broadcast-only HECHO / +10 MIN complies with the Android 12+ trampoline rules |
| A9 | Device-transfer restore of the Room files is consistent (cloud backup is off, D-27) |

## 17. Amendments from Codex review #2 (normative — these supersede earlier text where they conflict)

Source: review #2 findings A2-01…A2-13, triaged in DECISIONS.md › "Codex review #2 — findings log".

**R2-1 Schedule revision token (A2-01, CRITICAL).** `tasks.reminderRevision INTEGER NOT NULL DEFAULT 0` is incremented in the same transaction as any change that affects scheduling: due date, due time, reminder toggle, postpone, undo-postpone, reopen, delete/restore, import. The revision travels with everything reminder-related: `ReminderCandidate(taskId, revision, …)`; notification extras `(taskId, revision)`; the HECHO / +10 MIN / ABRIR `data` URIs (`ahora://reminder/{id}/{revision}/{action}`). Rules: `markDelivered(id, revision, now)` updates **only if the row's revision still equals the candidate's** (returns rows affected; 0 ⇒ the schedule changed underneath the reconcile, so the just-posted card is cancelled immediately); `snoozeIfDelivered(id, revision, now)` and HECHO validate the same revision; the sweep cancels any card whose revision differs from Room. Deterministic test: *edit-versus-reconcile interleaving* (candidate read → edit → post → mark).

**R2-2 Per-item, bounded delivery (A2-02, CRITICAL).** Replaces the single batched mark in §10.4. For each due item, in order: `try { post } catch → classify`; then `markDelivered(id, revision, now)` for that item alone (small transaction). A **permanent** post failure (e.g. `SecurityException`) is *consumed* (marked) so it cannot become a poison item; a **transient** failure leaves the item unmarked. Work stops at a deadline (receiver budget minus a reserve). The retry cursor (`now + 60 s`) stays armed **only while unprocessed items remain**; the true next cursor is armed as the last step. Every item that was posted-and-marked is never revisited, so progress is real. Tests: partial prefix then timeout; a notifier that always throws for one task; many due items.

**R2-3 Durability boundary (A2-03, CRITICAL).** Receivers `await reconcileNow()` **inside `goAsync()` before `finish()`** — including `ReminderActionReceiver` (HECHO / +10 MIN). Reminder-affecting repository mutations (`create`, `edit`, `postpone`, `undoPostpone`, `complete`, `reopen`, `delete`, `import`) await the job returned by `request()` (bounded by `withTimeout`), so success is reported only after the cursor is armed; `request()` remains a fire-and-forget trigger for non-critical callers (permission result, app start). The reconciler never calls a repository mutation (§10.1 rule 3), so awaiting cannot deadlock. **Honest boundary:** a process kill between the Room commit and the `AlarmManager` call (milliseconds) can still lose one arming; recovery is the next receiver/app launch (`reconcileNow` at start). NFR-03 is narrowed accordingly. Tests simulate kill-after-commit for create, edit, postpone, complete-with-successor and snooze, then run start-up reconcile.

**R2-4 Create and edit are separate operations (A2-04).** `create(draft)` inserts. `edit(id, EditableFields)` re-reads the row **inside the transaction** and merges **only** the editable fields (title, notes, dueDate, dueTime, reminderEnabled, priority, recurrence, estimatedMinutes, listName), preserving `done`, `completedAt`, reminder delivery state (unless the schedule changed) and recurrence lineage. Results: `Saved`, `NotFound`, `AlreadyCompleted`. On `NotFound`/`AlreadyCompleted` the sheet closes without writing and shows a neutral Snackbar ("Esta tarea ya no está pendiente"); it never resurrects, uncompletes or overwrites. Tests: notification-complete and delete versus editor save.

**R2-5 Postpone has a real rollback (A2-05).** `postpone(id)` returns `PostponeResult(previous: SchedulePatch, revisionAfter)`. `undoPostpone(id, result)` runs one transaction that restores the previous schedule **only if `reminderRevision == revisionAfter`** (so it cannot overwrite a later edit), bumps the revision, then cancels stale cards and reconciles. Tests: undo before and after the original reminder instant, and after an intervening edit (⇒ no-op).

**R2-6 `recurrenceAnchor` lifecycle (A2-06).** Invariant: `recurrence = none ⇔ recurrenceAnchor = null`. On **create/edit**: if `recurrence != none` and (recurrence or `dueDate` changed, or the anchor is null) ⇒ `anchor := dueDate`; if `recurrence = none` ⇒ `anchor := null`. `postpone` does **not** touch the anchor. Successors copy the anchor. Import validates the invariant. Transition tests (none→monthly, monthly→none→monthly, date edited before completion) complement the calculator tests.

**R2-7 WorkManager (A2-07).** Governance, not design: D-08 is part of the architecture approved at the Architecture Gate on 2026-09-20; the precedence rule in PRODUCT_SPEC §0 is amended (DECISIONS D-08) so a recorded, approved engineering decision may override a platform-component sentence in the Spec.

**R2-8 Import is atomic and cleans up immediately (A2-08).** Parse and fully validate the file (schemaVersion, invariants incl. R2-6, unique ids) **before** touching Room. Replace all rows in **one** Room transaction; any failure leaves the old database untouched. After commit, in this order: clear editor/navigation state referencing old rows, cancel the alarm cursor and **all** reminder notifications, then **await** `reconcileNow()`. Tests: malformed file, duplicate ids, invariant failure, cancellation, kill mid-transaction.

**R2-9 Privacy copy (A2-09).** Absolute wording replaced with what the app can guarantee (PRODUCT_SPEC §5.7): *"Sin cuenta ni sincronización. AHORA no envía tus datos a ningún sitio; las copias que exportes van donde tú elijas."*

**R2-10 Repository is an interface in `domain` (A2-10).** `domain.TaskRepository` is what ViewModels depend on; `data.TaskRepositoryImpl` implements it and is bound in a Hilt module. `ReminderStateStore` is `internal` to the data/reminders implementation. The rule `ui → domain ← data` holds.

**R2-11 Verification additions (A2-11).** Tests must cover: notification **body → Home** cold and warm from every destination; HECHO / +10 MIN as broadcast-only (no back stack) versus ABRIR as an activity intent (back stack `[Home, Focus]`); API 26; forward and backward clock/date changes; Madrid DST gap/overlap on a real alarm; reboot before/after unlock; exact-alarm revoke/re-grant; notification permission revoked with a visible card; duplicate reconcile storms; partial batches; stale edits; kill-after-commit. Assumptions A1–A9 stay **unverified** until verified on a device.

**R2-12 Foco preset sources (A2-12).** DataStore holds only the *last-used default*. The active session's preset **and** end timestamp live together in `FocusViewModel`'s `SavedStateHandle`, which wins on restoration; preset changes are persisted to DataStore asynchronously and never overwrite a restored session.

**R2-13 "También pendiente" collapse (A2-13).** The expanded flag is `rememberSaveable` (survives rotation/process death). To satisfy "collapses when leaving the tab", bottom-bar navigation **to Home uses `restoreState = false`** (Inbox/Tasks keep `restoreState = true`), so a returning Home is fresh. Tests: rotation keeps it expanded; tab switch and return collapses it.

### 17.1 Extra rows for the on-device matrix (§15.4)

| # | Scenario |
|---|---|
| 14 | API 26 device: full reminder path (channel, exact/inexact, boot) |
| 15 | Backward clock change and backward date change while reminders are pending |
| 16 | Madrid DST gap (2026-03-29) and overlap (2026-10-25) on an actual armed alarm |
| 17 | Reboot before first unlock (alarms cleared, no crash) and after unlock (re-armed) |
| 18 | Revoke then re-grant exact-alarm access with an armed cursor |
| 19 | Revoke notification permission while a reminder card is visible |
| 20 | Notification body tap: cold, and warm from Home / Inbox / Tasks / Settings / Foco |
| 21 | Kill the process right after HECHO / +10 MIN commit (cold receiver) |

## 18. Amendments from Codex Gate B (normative — supersede earlier text where they conflict)

Source: Gate B findings GB-01…GB-07, triaged in DECISIONS.md › "Gate B — Codex code review of the reminder engine".

**GB-01 Actions dismiss only their own card.** HECHO / +10 MIN / ABRIR never cancel a notification by task id alone. A no-op action (stale revision, duplicate, task gone) cancels the tray card **only if its revision equals the action's**; a successful action cancels as before. A delayed action from revision N can therefore never remove the valid card of revision N+1 (which Room already records as delivered and which could never be re-posted). Every no-op action still runs a reconcile so the sweep heals leftovers.

**GB-02 Phase 1 cannot strand the cursor; the sweep cannot delay it.** §10.4 is amended: (1) the candidate read is cancellable; if it fails, is cut short by the receiver timeout, or the real arm throws, a 60 s emergency cursor is armed (best effort, non-cancellable, no I/O) before the failure propagates; (2) the real next cursor is settled **before** the tray sweep, and the sweep is cancellable, so a timeout during the sweep costs nothing. The arm calls themselves stay non-cancellable and I/O-free.

**GB-03 Imported revisions are bounded.** `TaskRules.MAX_IMPORT_REVISION` (10⁹) is enforced by `validateForImport`; `replaceAll` no longer wraps a revision back to a small value that a stale action could match.

**GB-04 A recurring series never forks.** `reopen` is always refused for a recurring occurrence; `undoComplete` refuses when the successor it generated is no longer open. Without persistent series lineage (a `seriesId`) those are the only ways to guarantee "at most one open occurrence per series". See D-31 for the deferred lineage option.

**GB-05 `request()` is genuinely conflated.** While a pass is queued behind the mutex, further requests share it; a request during a running pass queues exactly one follow-up. A storm costs ≤ 2 passes.

**GB-06 +10 MIN cannot re-snooze.** `snoozeIfDelivered` also requires `reminderSnoozeUntil IS NULL`, so a duplicated/delayed tap cannot push a pending snooze further out. (Rejected: bumping the revision on snooze — the harm is milliseconds, and a bump would invalidate legitimate actions.)

**GB-07 Notification entry points (engine half).** `MainActivity` forwards `intent.data` on cold start (`savedInstanceState == null`) and warm start (`addOnNewIntentListener`) to `NotificationLinkHandler`, which returns a validated `LinkDestination` (`Home` / `Focus(id)`), cancelling the matching ABRIR card. **The navigation host that consumes the destination and builds `[Home, Focus]` is still M3 work** (A7 remains unverified).

## 19. UI implementation notes (M3–M7, M10) — normative where they differ from §8/§9/§13

Rationale and the full deviation table: DECISIONS D-33.

**Layout of `ui/`** (as built): `theme/` (`AhoraColors` roles, `AhoraType` Archivo scale, `AhoraTheme`), `components/` (`AhoraButton`, `AhoraChip`, `AhoraCheckbox`, `AhoraSwitch`, `PriorityTag`, `TaskRow`, `AhoraTextField`, `TabTopBar`/`BackTopBar`, `focusRing`), `common/` (`TaskActions`, `UiMessenger`, `UndoToken`, `DateLabels`, `PickerDates`), `navigation/Routes.kt`, `AhoraRoot.kt`, `MainViewModel`, and one package per screen (`home`, `inbox`, `tasks`, `editor`, `focus`, `settings`), each with a `*Route` (owns the ViewModel), a stateless `*Screen` and a ViewModel. `ArchitectureBoundaryTest` still holds: `ui` imports nothing from `data`, Room, `ReminderStore` or the reconciler. Preferences and the backup codec are ports/pure code in `domain` (`AppPreferences`, `ReminderPermissionSource`, `BackupCodec`); `data.prefs` implements DataStore.

**Navigation.** Routes are type-safe (`Home`, `Inbox`, `Tasks`, `Focus(taskId)`, `Settings`). All stack manipulation lives in extension functions in `Routes.kt` and is unit-tested with `TestNavHostController`:
- `switchTab`: Hoy pops back to the root; Bandeja/Tareas replace each other above it (`saveState`/`restoreState`), so system back from either lands on Hoy.
- `openFocus` (Empezar): plain push → `[Home, Focus]`.
- `applyLink(LinkDestination)`: **Home** = `switchTab(Home)` (clears everything above); **Focus(id)** = pop to Home, then push Focus (`[Home, Focus]`) from any starting point (another tab, Ajustes, another Foco); re-delivering the Foco already on screen is a no-op so the running timer is not restarted.
- `leaveFocusToHome`: idempotent (Terminar and the "task is done" observer may both fire).
- Destination changes use a ~300 ms fade-through; chrome (bottom bar, FAB) is shown only on the three tabs, and the FAB is hidden while the sheet is open.

**Entry from notifications.** `MainActivity` (cold: `savedInstanceState == null`; warm: `addOnNewIntentListener`) → `NotificationLinkHandler.resolve` (validation, revision-aware card dismissal — unchanged) → `Channel<LinkDestination>` → `AhoraRoot` closes an open sheet and calls `applyLink`. The Foco `ViewModel` re-validates the task on its own (missing/done ⇒ `Closed` ⇒ back to Hoy).

**Editor.** One `ModalBottomSheet` composed at the root, driven by the Activity-scoped `EditorViewModel`. The draft (`EditorDraft`, `@Serializable`) is snapshot state mirrored into `SavedStateHandle`. Guardar closes the sheet and writes through `TaskActions`/`TaskRepository` in the application scope; the sheet never schedules a reminder. `POST_NOTIFICATIONS` is requested in context (first time the reminder switch goes on and the dialog can still be shown); after an answer, or if the runtime dialog is no longer available, the inline hint offers "Abrir ajustes". The exact-alarm hint offers "Permitir" (system screen) and disappears on return when access was granted (permission state is re-read on `ON_RESUME`).

**Foco.** `FocusViewModel` keeps `[preset, endMillis]` + an "alerted" flag in its `SavedStateHandle`; `remaining = ceil((end − now)/1 s)`; the haptic fires once (`alertPending` → `onAlerted`). Terminar/Posponer run through `TaskActions` and set `leaving`, so the screen closes immediately while the write completes in the application scope.

**Theme.** System/Light/Dark from DataStore; the splash is held until the first read (`themeMode == null`), system-bar styles and the window background follow the resolved theme.

## 20. Amendments from Codex Gate C (normative — supersede earlier text where they conflict)

Source: Gate C findings GC-01…GC-13, triaged in DECISIONS.md › "Gate C" and D-34.

**GC-04 Supported dates.** `dueDate` and `recurrenceAnchor` must lie in `TaskRules.MIN_DATE … MAX_DATE` (1900-01-01 … 2200-12-31, a superset of the editor's picker). Import rejects anything outside; `normalize` drops an out-of-range date with its dependents. Reason: `Instant.toEpochMilli()` (alarm arming) and `plusDays` (recurrence) overflow far outside it.

**GC-09 Shared text limits.** `TaskRules.MAX_TITLE/MAX_NOTES/MAX_LIST` (1 000 / 20 000 / 200) bind every write path *and* the backup codec; the editor and quick capture stop typing at them and `normalize` truncates as a backstop. Export refuses to write a file larger than the import limit (8 MiB), so a copy AHORA writes is a copy AHORA can read.

**GC-05 Commit versus re-plan (amends R2-3).** The repository still awaits the re-plan after a commit, but a *failure* of that re-plan is logged and swallowed: the row is committed, the alarm is a rebuildable cache and the next receiver/app start reconciles. Cancellation still propagates. Every write launched from `TaskActions` in the (handler-less) application scope is wrapped: a failure becomes the generic "No se pudo guardar" message, never an uncaught exception.

**GC-06 Undo of a completion (extends GB-04).** `undoComplete` also refuses when the generated successor has been edited since (`updatedAt != createdAt`).

**GC-03 Notification capability.** "Can notify" = app-level notifications enabled **and** the `reminders` channel not `IMPORTANCE_NONE` (`AndroidReminderNotifier.canPostReminders`, used by the notifier, the permission snapshot and therefore the Ajustes/editor hints). A missing channel counts as usable (it is created on first post).

**GC-07 / GC-13 Editor.** `EditorViewModel.openEdit` keeps its one pending read and cancels it on any later `openEdit`/`openNew`/`dismiss`. The Hoy/Mañana chips read the date when tapped.

**GC-08 Switch.** `AhoraSwitch` takes a `label` (its accessible name) and state words "Activado/Desactivado".

**Accepted residuals (not changed):** the check-then-cancel window of a stale action against a just-posted newer card (GB-01/GC-02 — closing it needs revision-specific notification identity); partial file after a failed SAF export (GC-10).
