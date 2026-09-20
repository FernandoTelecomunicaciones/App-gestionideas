# AHORA — Product Specification (implementation contract)

| | |
|---|---|
| Status | **Draft v1 — Phase 1, awaiting Architecture Gate approval** |
| Date | 2026-09-20 |
| Source of truth | `/design` (approved Claude Design handoff, commit `a9c99e1`) |
| Companion docs | [ARCHITECTURE.md](ARCHITECTURE.md) · [DECISIONS.md](DECISIONS.md) · [STATUS.md](STATUS.md) |

## 0. How to read this document

This spec translates the approved handoff (`design/AHORA Design Spec.dc.html` = **"Spec"**, `design/AHORA App.dc.html` = **"Prototype"**) into testable implementation requirements. It does **not** redesign the product. Every requirement carries an origin tag so nothing is silently changed:

| Tag | Meaning |
|---|---|
| `[D]` | Stated in the approved design (Spec or Prototype). Implement as written. |
| `[G]` | **Gap** — the design is silent. Engineering default chosen to keep the app coherent. Cheap to reverse; object if you disagree. |
| `[X]` | **Correction** — the design contradicts itself, the platform, or its own accessibility rules. The resolution is stated; the original is kept in §12. |
| `[OD-n]` | Needs an owner decision. Listed in [DECISIONS.md §Open decisions](DECISIONS.md#open-decisions-requiring-approval). A recommended default is stated; implementation follows it until you decide. |

Precedence when sources disagree: **Prototype visuals/copy > Spec prose** (the prototype is the concrete artefact reviewed), except where the Spec states a platform component, a rule, or an accessibility requirement, in which case the Spec wins — **unless DECISIONS.md records an owner-approved engineering decision that overrides it** (currently only D-08, WorkManager). Resolved conflicts are listed in §12.

> The repository README (2 lines, pre-design) describes an "idea/project prioritisation" app. The approved handoff models **flat tasks** with priority P1–P3 and a free-text list/category; there is **no Project entity**. We follow the handoff.

## 1. Product definition

**AHORA** is a native Android, fully offline task/idea app for people who lose track of things. It exists to run one loop and get out of the way:

> **CAPTURE → DECIDE LATER → SEE WHAT MATTERS → DO IT → STOP MANAGING IT.**

| Loop step | Where it lives |
|---|---|
| Capture | **Bandeja** — one field, only a title is required, 2–3 seconds |
| Decide later | Tapping an inbox item opens the **edit sheet**; date/priority are optional |
| See what matters | **Hoy** — exactly one "Ahora" card, ≤ 3 next rows, the rest collapsed |
| Do it | **Foco** — full-screen, one task, "Sólo esto ahora." |
| Stop managing it | Complete / postpone in one tap; reminders and repeats run themselves |

### 1.1 Principles (acceptance-level)

- **P-1 Minimal cognitive load.** Hoy shows at most one decision at a time. No lists by default. `[D]`
- **P-2 Zero-friction capture.** Only the title is required; nothing else can block a save. `[D]`
- **P-3 No guilt, no gamification.** No streaks, scores, statistics, confetti, red urgency, failure counters, or blame language anywhere — including notifications and overdue tasks. `[D]`
- **P-4 No required setup.** Every setting has a sensible default. Permissions are requested in context, never up-front. `[D]`
- **P-5 Offline & private.** No account, backend, analytics, AI API or network access. The app does not declare the `INTERNET` permission. `[D]` (stack constraints from the brief)

### 1.2 Non-goals for V1

Backend / sync / accounts · analytics or crash-reporting SDKs · AI features · widgets, share-target, quick-settings tile · subtasks · tag system beyond one free-text list · calendar integration · multiple reminders per task or "N minutes before" · location reminders · Wear OS · tablets-specific layouts (must not break; see NFR-07) · multi-language (Spanish only; strings are externalised so localisation is possible later).

## 2. Glossary

| Term | Definition |
|---|---|
| **Task** | The single entity. An "idea" is just a task with no date and no priority. |
| **Pending / Completed** | `done = false` / `done = true`. |
| **Inbox item ("sin decidir")** | Pending task with **no date and no priority**. Derived, never stored. `[D]` (Prototype `inbox` filter) |
| **Decided task** | Pending task with a date or a priority (i.e. not an inbox item). |
| **Today** | The device's current local calendar date. Changes at local midnight and when the time zone changes. |
| **Due pool** | Pending, decided tasks with `dueDate ≤ today` (due today **or overdue**). |
| **Ahora** | The single highest-ranked task shown on Hoy (§6.2). Computed, never stored. |
| **Reminder** | One notification at the task's due date+time. At most one per task. |
| **Snooze** | Notification action "+10 MIN": re-notify 10 min after the tap. |
| **Focus session** | Time spent on the Foco screen for one task. Not persisted. |

## 3. Information architecture & navigation

### 3.1 Destinations `[D]` (Spec §1)

| Destination | Route | Purpose | In bottom bar |
|---|---|---|---|
| Hoy | `home` (start destination) | One question: what deserves attention now | Yes (icon: Lucide `home`) |
| Bandeja | `inbox` | 2–3 s capture | Yes (`inbox`) |
| Tareas | `tasks` | Full base: search + 4 filters | Yes (`check-square`) |
| Foco | `focus/{taskId}` | Full-screen single-task mode | No |
| Ajustes | `settings` | Small fixed settings | No — opened from a `TopAppBar` icon |
| Edit/Create | *(none)* | `ModalBottomSheet` over the current screen — **never a new screen** | n/a |

### 3.2 Chrome visibility matrix

| Element | Hoy | Bandeja | Tareas | Foco | Ajustes |
|---|---|---|---|---|---|
| `NavigationBar` (3 fixed items) | ✔ | ✔ | ✔ | ✘ | ✘ `[G]` |
| Global FAB (+) → new-task sheet | ✔ | ✔ | ✔ | ✘ | ✘ |
| `TopAppBar` | title-less, trailing **settings** icon `[G]` | same | same | ✘ | title "Ajustes" + back `[G]` |

`[X]` The Spec puts Settings in a `TopAppBar`, but no Prototype screen has a top bar. Resolution: a minimal, title-less `TopAppBar` whose only content is a trailing settings icon button, so the screen headings ("¿Qué toca ahora?", "Tareas"…) stay as designed content.

`[G]` Ajustes is a pushed screen (no bottom bar, no FAB, back arrow). The FAB is hidden while the edit sheet is open (the sheet is modal).

### 3.3 Back behaviour

- System back on a tab: standard — Bandeja/Tareas return to Hoy; Hoy exits. `[G]`
- Foco: system back ≡ **Salir** (no changes). `[D]`
- Edit sheet: system back / scrim tap / swipe-down ≡ **Cancelar** (discard). `[G]`
- Predictive back is enabled (target SDK 36); all `BackHandler`s must be predictive-back compatible.

## 4. Data model (product level)

| Field | Type | Required | Notes |
|---|---|---|---|
| `title` | text | **Yes** | Trimmed, non-empty. Only mandatory field. `[D]` |
| `notes` | text | No | Multiline. `[D]` |
| `dueDate` | local date | No | A real calendar date, never a relative token. `[X]` (Prototype stores `'hoy'`/`'mañana'` strings — a prototype shortcut that would rot at midnight) |
| `dueTime` | local time (minute precision) | No | Requires `dueDate`. `[G]` |
| `reminderEnabled` | boolean | No | Requires `dueTime`. `[D]` |
| `priority` | `P1`/`P2`/`P3`/none | No | none = undecided. Labels: P1 *Importante*, P2 *Normal*, P3 *Puede esperar*. `[D]` |
| `recurrence` | none/daily/weekly/monthly | No | Requires `dueDate`. `[D]` fields; behaviour `[G]` §6.5 |
| `estimatedMinutes` | 15/30/60/120/none | No | **Data-only in V1**: stored and editable, drives no behaviour (design defines none). `[G]` |
| `list` | free text | No | "Lista / categoría". **Data-only in V1** (no list filter is designed). `[G]` |
| `done`, `completedAt` | bool, instant | — | |
| `createdAt`, `updatedAt` | instant | — | Used for ordering ties. |

### 4.1 Invariants (enforced in exactly one write path)

1. `title.trim().isNotEmpty()`.
2. `dueTime != null ⇒ dueDate != null`.
3. `reminderEnabled ⇒ dueTime != null`.
4. `recurrence != none ⇒ dueDate != null`.
5. `priority ∈ {null, 1, 2, 3}`.
6. Inbox membership = `!done ∧ dueDate = null ∧ priority = null` (derived, no stored status).

UI consequence `[G]`: picking a time with no date auto-selects the date (**Hoy**, or **Mañana** if that time has already passed today — the selected chip makes this visible). Repeat is disabled until a date exists. Clearing the date clears time, reminder and recurrence.

## 5. Screens

### 5.1 Hoy `[D]` Spec §3, Prototype "01 · HOY"

| ID | Requirement |
|---|---|
| HOY-01 | Header: greeting by time band (§6.7) in 12 sp secondary text, then H2 **"¿Qué toca ahora?"** (24 sp / 800). Never a task list by default. |
| HOY-02 | **Ahora card** (only if an Ahora exists): 1 px outlined card (`OutlinedCard`, **not** a coloured fill). Kicker "AHORA" (10 sp/600, letter-spaced, `accentText`); title 17 sp/800; priority tag + date label; if reminder on, a Lucide `bell` icon + `HH:MM` `[X]` (Prototype used the 🔔 emoji); full-width **Empezar** primary button → Foco. Tapping the title opens the edit sheet. |
| HOY-03 | **"Tus 3 de hoy"** section header (10 sp caps) + up to **3** rows (56 dp each). Row = checkbox (22 dp visual, 48×48 dp touch) · title 14 sp (1–2 lines) · priority tag right. Row tap → edit sheet; checkbox tap → complete. Header hidden when there are 0 rows `[G]`. |
| HOY-04 | **"También pendiente · N"** text row (13 sp/600, `accentText`), hidden when N = 0. Tap expands the remaining pending tasks **inline** (same row component); it never navigates. Collapsed by default; collapses when leaving the tab `[D]`; survives rotation/process death `[G]`. No aggressive red badge. |
| HOY-05 | Overdue tasks look identical to today's tasks. No red, no counters, no blame copy. Date label follows §6.6. |
| HOY-06 | Composition follows §6.2 exactly. |
| HOY-07 | **Empty state** (no pending tasks at all) `[G]`, **PROVISIONAL COPY** — "Nada pendiente." (centered, 13 sp, no illustration). If only inbox items exist: Ahora card absent, "También pendiente · N" still shown so they remain reachable. |
| HOY-08 | Never flash an empty state before the first data emission (show nothing until loaded). `[G]` |
| HOY-09 | Bottom content padding ≥ 88 dp so the FAB never covers the last row. `[D]` (Prototype `padding-bottom:88px`) |

### 5.2 Bandeja `[D]` Spec §4, Prototype "02 · BANDEJA"

| ID | Requirement |
|---|---|
| INB-01 | H2 **"¿Qué tienes en la cabeza?"** (22 sp) + sub-copy "Escribe y guarda. Todo lo demás puede esperar." (13 sp secondary). `[X]` The Spec calls this phrase the field *placeholder*; the Prototype uses it as heading with placeholder **"Ej. Llamar al dentista"**. Prototype wins (see §12). |
| INB-02 | Always-visible capture row: text field (single line, auto-capitalise sentences, IME action **Done**) + compact **Guardar** button. Save on button or IME Done. |
| INB-03 | Guardar disabled (45 % opacity) while the trimmed title is empty. Save with only a title is valid. `[D]` |
| INB-04 | After save: field clears, **focus and keyboard stay** (rapid multi-capture) `[G]`, new row appears at top immediately, Snackbar **"Guardado en Bandeja"**. `[D]` |
| INB-05 | Optional shortcut chips under the field: **Hoy · Fecha · Prioridad** (11 sp). They pre-set `dueDate = today` / open the date picker / open a P1–P3 menu for the item being captured; tapping a set chip clears it. They never block or delay saving; they reset after each save. If any chip was set the item is no longer an inbox item, so the Snackbar reads **"Guardado"** instead. `[G]` (Spec §4 requires the chips; Prototype only shows them dimmed in the static gallery) |
| INB-06 | "Sin decidir todavía" list: **one-line** rows (title only, ellipsis), **no metadata, no checkbox** — the decision is forced into the edit sheet. Newest first. Row tap → edit sheet. `[D]` |
| INB-07 | Empty state: **"Todo fuera de tu cabeza."** No illustration, no confetti. `[D]` |
| INB-08 | Capture text is preserved across rotation and process death. `[G]` |

### 5.3 Tareas `[D]` Spec §5, Prototype "03 · TAREAS"

| ID | Requirement |
|---|---|
| TSK-01 | H2 "Tareas", search field (placeholder **"Buscar"**), 4 single-select `FilterChip`s: **Pendientes** (default) · **Próximas** · **Sin fecha** · **P1**. One level only. |
| TSK-02 | Row = checkbox · title 14 sp · date label 11 sp · priority tag. Row tap → edit; checkbox → complete. |
| TSK-03 | The last selected filter is persisted (DataStore) and restored. `[D]` (Spec §12) |
| TSK-04 | Search is case- **and accent-insensitive** over title and notes (typing "camion" finds "Camión"). Debounced ≤ 150 ms. Applies within the active filter. `[X]` (Prototype: plain `toLowerCase().includes()` on title only) |
| TSK-05 | **Completadas**: a discreet text link (not a section, not a chip) switches the list to completed tasks, newest-completed first; tapping a checkbox reopens the task (§6.3). A text link "Pendientes" returns. `[G]` |
| TSK-06 | Empty result copy `[G]`, **PROVISIONAL**: "Nada coincide." |

**Filter semantics** `[G]` (Prototype-faithful):

| Filter | Predicate (all imply `!done`) | Sort |
|---|---|---|
| Pendientes | — | `dueDate` asc (none last) → priority asc (none last) → `createdAt` desc |
| Próximas | `dueDate != null` (includes today **and overdue**, so nothing dated ever hides) | `dueDate` asc → `dueTime` asc |
| Sin fecha | `dueDate == null` | `createdAt` desc |
| P1 | `priority == P1` | `dueDate` asc (none last) → `createdAt` desc |

### 5.4 Create / edit sheet `[D]` Spec §6

**Quick capture has two entry points, both saving a task with only a title:** the Bandeja field (§5.2, the 2–3 s path) and the global **FAB (+)**, which opens this sheet as the "creación rápida" surface (Spec §1). The FAB path lands in the same sheet used for editing, so anything optional can be added without leaving the screen.

`ModalBottomSheet`, fully expanded (`skipPartiallyExpanded`), IME-aware. Heading **"Nueva tarea"** / **"Editar tarea"**.

| ID | Field | Control | Behaviour |
|---|---|---|---|
| EDT-01 | Título | `OutlinedTextField`, placeholder "¿Qué hay que hacer?" | Only mandatory field. |
| EDT-02 | Notas (opcional) | Multiline `TextField`, placeholder "Detalles, contexto…" | |
| EDT-03 | Fecha | 3 `FilterChip`s: **Hoy · Mañana · Elegir fecha** → `DatePickerDialog` | Tapping the selected chip clears the date `[G]`. A picked date is shown on the "Elegir fecha" chip. |
| EDT-04 | Hora (opcional) | `TimePicker` dialog | Clearable `[G]`. Uses device 12/24 h preference. Setting it with no date → §4.1. |
| EDT-05 | Recordatorio | M3-style `Switch`, label **"Avisarme a esa hora"** | **Appears immediately below Hora once a time is set** `[D]`. Turning it on requests notification permission in context and, if needed, shows the exact-alarm hint (§8.2). Never blocks saving. If the chosen instant is already past, a non-error helper line reads "Esa hora ya pasó: no habrá aviso." `[G]` |
| EDT-06 | Prioridad | `SingleChoiceSegmentedButtonRow`: **P1 Importante · P2 Normal · P3 Puede esperar** | Tapping the selected segment clears it `[G]`. At large font scale the segments stack vertically. |
| EDT-07 | Más opciones | Collapsed disclosure ("+ Más opciones" / "− Más opciones") | Contains **Repetir** (No se repite / Cada día / Cada semana / Cada mes — disabled until a date exists), **Duración estimada** (Sin estimar / 15 min / 30 min / 1 hora / 2 horas), **Lista / categoría** (free text, "Ej. Casa, Trabajo, Máster"). |
| EDT-08 | Actions | **Cancelar** (secondary) · **Guardar** (primary) | Guardar disabled (45 %) until `title.trim()` non-empty. No error messages for optional fields. `[D]` |
| EDT-09 | Eliminar | Text button, edit mode only, bottom of the sheet | **[OD-5]** The design has no way to delete a task; without it the Bandeja can only grow. Recommended: delete immediately with Snackbar **"Eliminada" + Deshacer**. Deleting an open recurring task ends its series. |
| EDT-10 | Confirmation | Snackbar **"Guardado"** after save `[D]` | |
| EDT-11 | Draft durability | The draft survives rotation and process death while the sheet is open. `[G]` | |

Pickers: the M3 `DatePicker` / `TimePicker` keep their internal round selection shapes (they are functional dials/day cells, not chrome); their **dialog containers** use 0 dp radius. Documented exception to the 0 dp rule (§9.3).

### 5.5 Foco `[D]` Spec §7, Prototype interactive phone

| ID | Requirement |
|---|---|
| FOC-01 | Full screen. **No** bottom bar, FAB, top bar, task list, stats or gamification. `[D]` |
| FOC-02 | Content, vertically centred: kicker "FOCO" (10 sp caps, `accentText`) · task title (24 sp/800) · **"Sólo esto ahora."** (14 sp secondary) · clock (44 sp/800, `MM:SS`) · presets **15 / 25 / 45 min** (last used remembered, default 25) · actions stacked, max width 220 dp. |
| FOC-03 | **Terminar** (primary): complete the task exactly as §6.3, Snackbar "Tarea completada", return to **Hoy**. |
| FOC-04 | **Posponer** (secondary): move the task to **tomorrow** (§6.4), Snackbar **"Pospuesta a mañana"** (+ Deshacer `[G]`), return to Hoy. |
| FOC-05 | **Salir** (ghost): return to the previous screen, no changes. |
| FOC-06 | **Timer semantics — [OD-3].** Design shows a `MM:00` clock and three presets but defines no behaviour. Recommended: entering Foco starts a countdown at the last-used preset (entering via **Empezar** *is* the start); tapping a preset restarts at that length; at `00:00` one soft haptic and the clock rests at `00:00` — no notification, no auto-complete, no stats. State is restored after rotation/process death via an end timestamp. Screen stays awake while in Foco (setting in Ajustes › Comportamiento). |
| FOC-07 | If the task is completed/deleted elsewhere while Foco is open (e.g. notification **HECHO**), Foco closes to Hoy silently. |
| FOC-08 | Transitions: Hoy → Foco uses M3 fade-through, 200–300 ms; honours the system animator-duration scale. `[D]` |

### 5.6 System notification `[D]` Spec §8

| ID | Requirement |
|---|---|
| NTF-01 | One channel **"Recordatorios"**, importance HIGH (heads-up + default sound), category `REMINDER`. Sound/vibration are managed in system channel settings (linked from Ajustes). |
| NTF-02 | Title = task title. Body = **"Vence a las HH:MM"** (device time format). No urgency or blame wording, ever. |
| NTF-03 | Three actions, exact labels: **HECHO**, **+10 MIN**, **ABRIR**. No `BigTextStyle`. |
| NTF-04 | **HECHO** completes the task **without opening the app** (§6.3), dismisses the notification. |
| NTF-05 | **+10 MIN** dismisses and re-notifies **10 minutes after the tap**. The task's due date/time is unchanged. Can be repeated. |
| NTF-06 | **ABRIR** opens **Foco** for that task and dismisses the notification; back returns to Hoy. |
| NTF-07 | Tapping the notification body opens the app on **Hoy** `[G]` (so ABRIR remains the distinct "go straight to Foco" action). |
| NTF-08 | Actions are idempotent: pressing HECHO twice, or on a task already completed/deleted, is a silent no-op that still dismisses the notification. |
| NTF-09 | Small icon: monochrome "A" glyph (Android requires a single-colour silhouette; the Prototype's red "A" tile is preview-only). |

### 5.7 Ajustes `[D]` Spec §9 — contents are a design gap **[OD-6]**

Five fixed, small sections; nothing mandatory; every value has a default.

| Section | Proposed content (recommended default) |
|---|---|
| **Recordatorios** | Status rows for *Notificaciones* and *Alarmas exactas* (granted / not granted + one-tap "Abrir ajustes"/"Permitir"); "Sonido y vibración" → system channel settings. |
| **Apariencia** | Tema: **Sistema** (default) · Claro · Oscuro. |
| **Comportamiento** | "Mantener pantalla encendida en Foco" (default on). Nothing else. |
| **Datos** | "Exportar copia" and "Importar copia" (JSON via the system file picker; import replaces after one confirmation dialog). This is the user-controlled backup: the app does **not** use cloud backup by default (OD-7). Last milestone; can be cut without affecting anything else. |
| **Acerca de** | App name, version, open-source licences (Archivo OFL, Lucide ISC, libraries), line **"Sin cuenta ni sincronización. AHORA no envía tus datos a ningún sitio; las copias que exportes van donde tú elijas."** (Narrowed after review #2: the file picker can target a cloud provider the user chooses; the app itself uploads nothing.) Import is atomic and validated (ARCHITECTURE R2-8). |

## 6. Behaviour rules

### 6.1 Time & date (all logic uses the *device's* current zone, evaluated at call time)

- `dueDate`/`dueTime` are **floating local values**: "18:00" means 18:00 wherever the device currently is. Absolute instants are derived when scheduling. (D-05)
- DST: a non-existent local time (spring-forward gap) fires at the first valid instant after the gap; an ambiguous time (autumn overlap) fires at the **earlier** occurrence. Covered by tests for `Europe/Madrid` 2026-03-29 and 2026-10-25.
- "Today" is recomputed at local midnight and whenever the zone or clock changes.

### 6.2 Hoy composition (Ahora selection) — **[OD-4]**

The Prototype hard-codes an `isAhora` flag that nothing can set and otherwise picks "the first pending task". A deterministic rule is required:

```
pending   = tasks where !done
decided   = pending where dueDate != null OR priority != null        // not Inbox
duePool   = decided where dueDate != null AND dueDate <= today       // due today or overdue
rank(t)   = ( priorityRank,          // P1=1 < P2=2 < P3=3 < none=4
              dueDate ?: +∞,
              dueTime ?: +∞,
              createdAt )             // ascending
ahoraPool = duePool if non-empty else decided
ahora     = min rank of ahoraPool  (or none)
today3    = (duePool − ahora) ordered by rank, first 3
rest      = pending − ahora − today3           // includes Inbox items, future and undated tasks
```

Rationale: priority first (what matters), then how soon. Inbox items are never Ahora (undecided items don't "matter" yet) but stay reachable via "También pendiente · N". If nothing is decided, Ahora is absent (HOY-07). No manual pinning in V1.

### 6.3 Completing a task (single transactional operation, idempotent)

1. If already `done`, do nothing (protects against double notification taps / Foco+notification races).
2. `done = true`, `completedAt = now`, clear pending snooze, dismiss its notification.
3. If `recurrence != none`: create the **next occurrence** as a **new** task (§6.5) in the same transaction.
4. Re-plan reminders (ARCHITECTURE §10).
5. UI: Snackbar **"Tarea completada"** with **Deshacer** `[G]` (Spec allows one action; 22 dp checkboxes are easy to mis-tap). Undo reopens the task **and removes the generated next occurrence**.

**Reopen** (from Completadas): `done = false`, `completedAt = null`. If its reminder instant is already past, the reminder is marked consumed (no retroactive notification).

### 6.4 Postponing

`dueDate = today + 1` (device zone). Time and reminder are kept, so a 18:00 reminder moves to tomorrow 18:00. Tasks with no date get tomorrow's date. Snooze/consumed state is reset. **Deshacer** restores the previous date/time/reminder state in one transaction, and only if the task has not been edited since (otherwise it does nothing). The recurrence anchor is untouched.

**Editing a task that changed meanwhile** (e.g. completed from the notification while the sheet is open): Guardar never resurrects, uncompletes or overwrites it — the sheet closes and a neutral Snackbar says "Esta tarea ya no está pendiente". Only editable fields are ever written.

### 6.5 Recurrence `[G]` (design lists the options, defines no behaviour)

- The anchor is set to the occurrence's date whenever the user creates/edits a recurring task and changes its date or repeat rule; it is empty for non-recurring tasks; successors inherit it; postponing does not move it.
- Only when a task is **completed** does the next occurrence appear (no backlog of missed occurrences, ever).
- Next date = the first date in the series **strictly after `max(today, dueDate)`**:
  daily = +1 day steps, weekly = +7 day steps, monthly = `anchor.plusMonths(k)` with `anchor` = the series' original first due date (so Jan 31 → Feb 28 → Mar 31, no drift).
- The next occurrence copies title, notes, time, reminder flag, priority, recurrence, duration, list, anchor; it is a **new row** (history stays intact).
- Deleting an open recurring task ends the series. Postponing keeps the series.

### 6.6 Labels & formats

| Item | Rule |
|---|---|
| Date label | **Hoy** · **Mañana** · otherwise `d MMM` in es-ES (e.g. "15 sep"), with year if not the current year · no date → **"Sin fecha"** (Tareas, Ahora card) `[D]` |
| Priority tag | Text **P1/P2/P3** always shown (never colour alone). No priority → **no tag** `[X]` (Prototype drew a 40 %-opacity "—", which fails contrast and adds noise). |
| Time | Device 12/24 h preference. |
| Tag styles | P1 = filled (accent-100/800), P2 = 1 px accent outline, P3 = neutral filled (neutral-100/800); dark values per Prototype `DARK`. |

### 6.7 Greeting bands `[G]`

05:00–11:59 **"Buenos días"** · 12:00–19:59 **"Buenas tardes"** · 20:00–04:59 **"Buenas noches"**. Re-evaluated every minute while Hoy is visible.

### 6.8 Reminder semantics (product level; engineering in ARCHITECTURE §10)

- One reminder per task, at exactly `dueDate + dueTime`, only if `reminderEnabled`. `[D]`
- Editing date/time/toggle resets the reminder. Completing, deleting or postponing cancels/moves it. **A reminder notification that is already visible is removed when its task is edited (date/time/reminder), disabled, completed, deleted or postponed** — a stale card must never stay actionable, and "+10 MIN" on it must never override a newer schedule.
- **Missed reminders** `[G]`: if the device was off, in a restrictive state, or the app was force-stopped, a reminder that is ≤ **12 h** late is delivered **once**, late, when the app next gets a chance to run; older ones are silently dropped. Never repeated, never escalated, never stacked into a "you missed N" message (P-3). Restoring from backup does not replay old reminders (same rule).
- Setting a reminder for a moment already in the past does not notify retroactively (EDT-05 helper line).
- Consequence of the platform: if the user force-stops the app, or an OEM battery manager kills alarms, reminders can be lost until the app is next opened. Ajustes › Recordatorios explains this in one plain sentence; it is not a promise the app can override.

## 7. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-01 | **Offline**: merged manifest has no `INTERNET` permission (lint-checked in CI). Font and icons are bundled. |
| NFR-02 | **Capture speed**: Bandeja Guardar → row visible ≤ 100 ms after tap; cold start to first Hoy frame ≤ 1.5 s on a mid-range device (target, measured in M7). |
| NFR-03 | **Reminder reliability** (permissions granted): posts within ±60 s of due time in normal conditions; survives reboot, app update and time/zone change; recovers after process death at the next receiver or app start; never duplicates; never fires for completed/deleted/rescheduled tasks. **Honest limit:** a process kill in the few milliseconds between the database commit and the alarm call can lose that one arming until the next launch (ARCHITECTURE R2-3). Doze/OEM limits in ARCHITECTURE §10.7. |
| NFR-04 | **No data loss** on process death or crash mid-write; migrations are non-destructive; destructive fallback is forbidden. |
| NFR-05 | **Privacy**: no analytics/crash SDKs; release logs never contain task text. |
| NFR-06 | **Localisation**: Spanish (es-ES) only in V1, all copy in string resources, plurals via resources. |
| NFR-07 | **Platform**: minSdk 26, compile/target 36. Phone portrait is primary; landscape, foldables and tablets must not break (content column capped at 640 dp, centred). Edge-to-edge with correct insets. |
| NFR-08 | **Themes**: System / Light / Dark, switchable live. No first-frame flash of the wrong theme. |
| NFR-09 | **Motion**: M3 standard `MotionScheme`, 200–300 ms, no particles/confetti; respects "remove animations". |

## 8. Permissions (product view; details in ARCHITECTURE §11)

| Permission | Why | When requested |
|---|---|---|
| `POST_NOTIFICATIONS` (Android 13+) | Show reminders | In context: first time the reminder switch is turned on. If denied the switch may stay on, with an inline plain-language hint + "Abrir ajustes"; never a blocking dialog. |
| `SCHEDULE_EXACT_ALARM` (special access, Android 12+) | On-time reminders | Never up-front. When the switch is turned on and exact alarms are not allowed, an inline hint offers "Permitir"; meanwhile the reminder is still scheduled with a best-effort (possibly minutes-late) alarm. **[OD-2]** |
| `RECEIVE_BOOT_COMPLETED` | Re-arm reminders after reboot | Install-time (normal permission). |

### 8.1 Degradation matrix (must be implemented and tested)

| Notifications | Exact alarms | Behaviour |
|---|---|---|
| ✔ | ✔ | Exact, on time. |
| ✔ | ✘ | Best-effort alarm (may be late by minutes); inline hint. |
| ✘ | any | Nothing can be shown; reminders are consumed silently; hint in editor and Ajustes. Task data unaffected. |

## 9. Visual system (Compose implementation contract)

Derived from `styles.css` (Modernist) and the Prototype. Reimplement as M3 `ColorScheme` + `AhoraTokens` `[D]`.

### 9.1 Colour

| Token | Light | Dark | Note |
|---|---|---|---|
| `bg` | `#f3f2f2` | `#1a1817` | |
| `surface` | `#eae9e9` | `#252221` | cards, inputs, sheet |
| `text` | `#201e1d` | `#f3f1ef` | |
| `accent` | `#ec3013` | `#ec3013` | single accent, both modes; **chrome/icons/FAB fill only** |
| `divider` | text @ 40 % | `#f3f1ef` @ 24 % | decorative 2 px rules only |
| P1 tag | `#fff2ef` / `#7c1405` | `#3a1510` / `#ffb3a3` | fill / text |
| P3 tag | `#f8f4f4` / `#444141` | `#2a2726` / `#e5e2e1` | fill / text |
| Pressed accent | accent-600 `#dd2b0f` | accent-400 `#ff9783` | `[D]` Spec §10 |

### 9.2 Accessibility corrections to the design tokens **[OD-4b]** `[X]`

Spec §11 promises **text ≥ 4.5:1 in both themes** and controls at 3:1. Measured with the WCAG 2.x formula on the handoff's own values (scratch script, not committed; numbers are reproducible from the hex values in §9.1):

| Where the design uses it | Measured | Requirement | Resolution (new semantic role) |
|---|---|---|---|
| Primary button label (`bg` colour on `#ec3013`, 14 sp/800) | **3.76:1** | 4.5 | `accentFill` = **accent-600 `#dd2b0f`** with label `#ffffff` → **4.74:1**; pressed = accent-700 (7.17:1) |
| Selected chip text (white on `#ec3013`, 11–12.5 sp) | **4.20:1** | 4.5 | same `accentFill` + white |
| FAB glyph (white on `#ec3013`) | 4.20:1 | 3 (icon) | keeps `#ec3013` — passes as an icon |
| Accent used as small **text** (P2 tag, ghost buttons, "Ahora" kicker, "También pendiente") light | 3.76:1 (accent) / 6.41:1 (accent-700) | 4.5 | `accentText` light = **accent-700 `#ae1800`** |
| Same, **dark** — Prototype leaves `accent-700` in dark mode | **2.47:1** | 4.5 | `accentText` dark = **accent-400 `#ff9783`** (8.43:1) |
| P2 tag text = `#ec3013` on dark bg | 4.21:1 | 4.5 | use `accentText` for the glyphs; keep accent for the 1 px outline |
| Secondary text via opacity: .55 / .60 (light) | **3.66 / 4.23:1** | 4.5 | `textSecondary` = text @ **70 %** (5.79 light, 8.18 dark) |
| Checkbox / input outlines use `divider` (light 2.41:1, dark 2.07:1) | **< 3:1** | 3 (WCAG 1.4.11) | `controlOutline` = text @ **55 %** (3.66 light, 5.50 dark); `divider` stays decorative |

Net visual change: the filled red is one ramp step deeper (`#dd2b0f`) on text-bearing fills, small red text is deeper in light / lighter in dark, and secondary text is a touch stronger. All colours come from the design's own ramps. If declined, the app fails its own §11 — see OD-4b.

### 9.3 Shape, type, space, elevation, motion

- **Radius 0 dp everywhere** `[D]` — `Shapes` override. M3 pieces that hard-code round shapes get custom composables: **Switch** (Prototype draws a square 40×22 switch) → `AhoraSwitch` built on `toggleable(role = Role.Switch)` with `stateDescription`; `NavigationBarItem` indicator pill is disabled (transparent), active state = accent colour like the Prototype. **Documented exceptions:** `DatePicker`/`TimePicker` internal day cells/clock dial.
- **Type**: Archivo only (400 / 600 / 800), **bundled in the APK** (no downloadable fonts; app is offline). Scale from the Prototype:

| Role | Size / weight | Used for |
|---|---|---|
| `focusClock` | 44 / 800 | Foco clock |
| `headline` | 24 / 800 | Hoy title, Foco title |
| `title` | 22 / 800 | Bandeja & Tareas titles |
| `sheetTitle` | 19 / 800 | Sheet heading |
| `cardTitle` | 17 / 800 | Ahora title |
| `body` | 14 / 400 | rows, inputs; buttons 14 / 800 |
| `bodySmall` | 13 / 400–600 | sub-copy, "También pendiente", snackbar |
| `label` | 12–12.5 / 600 | chips, field labels |
| `caption` | 11 / 400–600 | tags, date labels |
| `kicker` | 10 / 600, caps, +0.1 em | section headers |

- **Space**: 4 / 8 / 12 / 16 / 24 / 32 dp. **Minimum touch target 48×48 dp** (Prototype buttons are 44 px and date/priority buttons 38–40 px → raise to 48 dp) `[X]`.
- **Elevation**: soft ink-tinted shadow in light; hairline border + ambient darkness in dark. Never M3 `tonalElevation`. `[D]`
- **States**: pressed = ramp step (§9.1), never generic ripple opacity; disabled 45 %; focus = 2 px accent outline, 2 dp offset. `[D]`
- **Snackbar**: `SnackbarHost`, one action max. The Spec's ~2.2 s is kept for action-less messages; messages **with an action (Deshacer) use the platform "Short" duration** so the action stays usable and accessibility timeouts apply. `[X]`
- **Icons**: Lucide, bundled as vector drawables — `home`, `inbox`, `check-square`, `check`, `plus`, `settings`, `bell`, `x`, `chevron-down`, `chevron-up`, `trash-2`. The ⌂ ◧ ☑ glyphs in the Prototype are placeholders `[D]`.

## 10. Accessibility requirements (testable)

| ID | Requirement |
|---|---|
| A11Y-01 | Priority is never colour-only: text "P1/P2/P3" always present. `[D]` |
| A11Y-02 | Contrast per §9.2 in both themes. `[D]`/`[X]` |
| A11Y-03 | All interactive targets ≥ 48×48 dp (checkboxes, chips, nav, buttons, FAB is 56). `[D]` |
| A11Y-04 | Every control exposes role + state to TalkBack (`contentDescription`, `stateDescription` on checkbox/switch; row semantics merge title+priority+date). `[D]` |
| A11Y-05 | Layouts work at system font scale up to **200 %** (no clipped copy, no overlapping FAB; segmented priority and chip rows wrap/stack). `[G]` |
| A11Y-06 | Visible keyboard/D-pad focus indicator (2 px accent outline). `[D]` |
| A11Y-07 | Snackbar timeouts honour accessibility timeout settings. |
| A11Y-08 | Notification action labels are announced; actions are reachable without opening the app. |
| A11Y-09 | Reduced-motion / animator-scale 0 must leave every flow fully usable. |

## 11. Acceptance summary (Definition of Done per feature)

A feature is done when: its IDs above pass automated tests where automatable (ARCHITECTURE §15); light/dark screenshots reviewed; TalkBack pass at 100 % and 200 % font scale; no new lint errors; and — for anything touching reminders — the on-device matrix in ARCHITECTURE §15.4 passes on at least one physical device or emulator per Android 12, 14 and 16.

## 12. Design deviations & resolved conflicts (audit trail)

| # | Design says | Problem | Resolution |
|---|---|---|---|
| DD-1 | Text ≥ 4.5:1 both themes | Primary button 3.76:1, selected chips 4.20:1, dark accent text 2.47:1, secondary text ≥ .55–.6 opacity fails, control outlines < 3:1 | §9.2, **OD-4b** |
| DD-2 | Prototype stores `date: 'hoy' \| 'mañana'` | Relative tokens go stale at midnight; overdue impossible | Real local dates; overdue handled (§6.2) |
| DD-3 | Spec §1: Settings in a `TopAppBar`; Prototype screens have none | Nowhere to put the icon | Title-less top bar with settings icon (§3.2) |
| DD-4 | Spec §4: field placeholder = "¿Qué tienes en la cabeza?" | Prototype uses it as heading; placeholder is "Ej. Llamar al dentista" | Prototype wins (INB-01) |
| DD-5 | Spec §12 says M3 `Switch`; Prototype draws a square switch; radius 0 everywhere | M3 `Switch` shape is not overridable | Custom `AhoraSwitch` (§9.3) |
| DD-6 | Prototype `isAhora` flag, no UI to set it | Undefined selection | Computed ranking (§6.2, OD-4) |
| DD-7 | Prototype buttons 44 px, date/priority 38–40 px | < 48 dp touch target (Spec §11) | 48 dp minimum |
| DD-8 | Snackbar ~2.2 s with an action | Too short to use Undo / accessibility timeouts | Short duration when an action is present |
| DD-9 | Bandeja chips (Hoy·Fecha·Prioridad) in Spec + gallery, absent from the interactive Prototype | Unclear if required | Implement (INB-05) |
| DD-10 | Foco clock + presets, no timer behaviour | Undefined | OD-3 |
| DD-11 | Ajustes has 5 section names only | Undefined content | OD-6 |
| DD-12 | No delete, no clear-date/priority, no "empty Hoy" copy | Product holes | EDT-09 (OD-5), EDT-03/06, HOY-07 |
| DD-13 | "WorkManager como respaldo tras reinicio" (Spec §12) | WorkManager is not a reboot backup for exact reminders; adds a dependency for no reliability gain | AlarmManager + boot/time receivers; see DECISIONS D-08 |
| DD-14 | 🔔 emoji, red "A" notification tile | Preview artefacts | Lucide `bell`; monochrome small icon |

## 13. Traceability (Spec section → this document)

| Spec § | Topic | Here |
|---|---|---|
| 1 | Information architecture | §3 |
| 2 | Tokens | §9 |
| 3 | Hoy | §5.1, §6.2 |
| 4 | Bandeja | §5.2 |
| 5 | Tareas | §5.3 |
| 6 | Create/edit sheet | §5.4 |
| 7 | Foco | §5.5 |
| 8 | Notification | §5.6, §6.8 |
| 9 | Ajustes | §5.7 |
| 10 | States & interaction | §9.3 |
| 11 | Accessibility | §9.2, §10 |
| 12 | Compose mapping / persistence | ARCHITECTURE |
| 13 | Review notes | P-1…P-3, §10 |
