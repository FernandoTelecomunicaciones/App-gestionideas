#!/usr/bin/env python3
"""AHORA release-validation matrix runner (docs/RELEASE.md).

Drives ONE device or emulator through the priority checks with adb + uiautomator and prints PASS/FAIL per check.
It installs the DEBUG apk (it needs the adb-driven `DebugCommandReceiver` to seed tasks), so it proves the platform
behaviour of the engine and the UI, not the release/R8 build (see scripts/release_smoke.py for that).

    python scripts/device_matrix.py --serial emulator-5554
    python scripts/device_matrix.py --avd ahora_api26            # boots the AVD, runs, shuts it down
    python scripts/device_matrix.py --serial R58M... --only launch,quick_capture,reminders

Standard library only. Needs `adb` (ANDROID_HOME/platform-tools or PATH) and, for --avd, `emulator`.
Takes ~12 min per device (real alarms are waited for); `--skip reboot,reminders` for a 2-minute pass.
"""
import argparse, io, json, os, re, shutil, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "com.fernando.ahora"
ACT = PKG + "/.MainActivity"
RCV = PKG + "/.debug.DebugCommandReceiver"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEBUG_APK = os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)


def sdk_root():
    for v in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(v):
            return os.environ[v]
    return os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")


def tool(name, sub):
    found = shutil.which(name)
    if found:
        return found
    for ext in ("", ".exe"):
        p = os.path.join(sdk_root(), sub, name + ext)
        if os.path.exists(p):
            return p
    raise SystemExit(f"{name} not found (set ANDROID_HOME)")


class Dev:
    def __init__(self, serial):
        self.s = serial
        self.adb_path = tool("adb", "platform-tools")

    def adb(self, *a, timeout=120, raw=False):
        r = subprocess.run([self.adb_path, "-s", self.s, *a], capture_output=True, timeout=timeout)
        return r.stdout if raw else r.stdout.decode("utf-8", "replace").replace("\r", "")

    def sh(self, cmd, timeout=120):
        return self.adb("shell", cmd, timeout=timeout)

    # ---- state -------------------------------------------------------------------------------------------------
    def wait_boot(self, timeout=300):
        self.adb("wait-for-device", timeout=timeout)
        end = time.time() + timeout
        while time.time() < end:
            if self.sh("getprop sys.boot_completed").strip() == "1":
                time.sleep(3)
                return True
            time.sleep(2)
        return False

    @property
    def sdk(self):
        return int(self.sh("getprop ro.build.version.sdk").strip())

    def wake(self):
        self.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent 82; cmd statusbar collapse")

    def epoch(self):
        return int(self.sh("date +%s").strip())

    # ---- UI ----------------------------------------------------------------------------------------------------
    def ui(self, tries=4):
        for _ in range(tries):
            self.sh("uiautomator dump /sdcard/w.xml")
            x = self.adb("exec-out", "cat", "/sdcard/w.xml", raw=True).decode("utf-8", "replace")
            if x.lstrip().startswith("<?xml") or x.lstrip().startswith("<hierarchy"):
                try:
                    return ET.fromstring(x)
                except ET.ParseError:
                    pass
            time.sleep(1)
        raise RuntimeError("uiautomator dump failed")

    @staticmethod
    def label(n):
        return n.get("text") or n.get("content-desc") or ""

    @staticmethod
    def center(n):
        x0, y0, x1, y1 = map(int, re.findall(r"\d+", n.get("bounds")))
        return (x0 + x1) // 2, (y0 + y1) // 2

    def find(self, root, text, ci=False, cls=None):
        for n in root.iter("node"):
            for v in (n.get("text", ""), n.get("content-desc", "")):
                if (v.lower() == text.lower()) if ci else (v == text):
                    if cls is None or cls in n.get("class", ""):
                        return n
        return None

    def find_switch(self, root, name_part):
        """The checkable node whose own or descendant label contains `name_part` (the label beside it is not clickable)."""
        def labels(n):
            return [self.label(n)] + [l for c in n for l in labels(c)]
        return next((n for n in root.iter("node")
                     if n.get("checkable") == "true" and any(name_part in l for l in labels(n))), None)

    def has(self, text, ci=False):
        return self.find(self.ui(), text, ci) is not None

    def wait_text(self, text, timeout=15, ci=False):
        end = time.time() + timeout
        while time.time() < end:
            n = self.find(self.ui(), text, ci)
            if n is not None:
                return n
            time.sleep(0.7)
        return None

    def tap(self, text, timeout=8, ci=False, cls=None):
        end = time.time() + timeout
        while True:
            n = self.find(self.ui(), text, ci, cls)
            if n is not None:
                x, y = self.center(n)
                self.sh(f"input tap {x} {y}")
                time.sleep(1.0)
                return True
            if time.time() > end:
                return False
            time.sleep(0.7)

    def edit_field(self, root):
        return next((n for n in root.iter("node") if "EditText" in n.get("class", "")), None)

    def package_on_top(self):
        return self.sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")

    # ---- app ---------------------------------------------------------------------------------------------------
    def launch(self, fresh=False):
        """`fresh` force-stops first (clears any open sheet/dialog). NOTE: force-stop cancels the app's alarms; the
        next start re-arms them, so never use it while a reminder is pending and the app must not be opened."""
        self.wake()
        if fresh:
            self.sh(f"am force-stop {PKG}")
        self.sh(f"am start -n {ACT}")
        time.sleep(3)

    def broadcast(self, *extras):
        self.sh(f"am broadcast -a {PKG}.debug.CMD -n {RCV} " + " ".join(extras))

    def seed(self, title, minutes=None, reminder=True, priority=0, date=None, time_=None):
        extras = ["--es cmd create", f"--es title {title}", f"--ez reminder {'true' if reminder else 'false'}",
                  f"--ei priority {priority}"]
        if date:
            extras += [f"--es date {date}", f"--es time {time_}"]
        else:
            extras.append(f"--ei in_minutes {minutes}")
        self.broadcast(*extras)
        time.sleep(2)

    def logs(self, cmd, settle=2.5):
        self.sh("logcat -c")
        self.broadcast(f"--es cmd {cmd}")
        time.sleep(settle)
        return self.sh("logcat -d -s AhoraDebug:I")

    def tasks(self):
        out = []
        for m in re.finditer(r"task id=(\d+) '(.*?)' due=(\S+) rev=(\d+) rem=(\w+) fired=(\S+) snooze=(\S+) done=(\w+) rec=(\S+)",
                             self.logs("list")):
            out.append(dict(id=int(m[1]), title=m[2], due=m[3], rev=int(m[4]), rem=m[5] == "true", fired=m[6],
                            snooze=m[7], done=m[8] == "true", rec=m[9]))
        return out

    def task(self, title):
        return next((t for t in self.tasks() if t["title"] == title), None)

    def caps(self):
        m = re.search(r"caps sdk=(\d+) canScheduleExact=(\w+) notificationsGranted=(\w+) needsRuntimeNotif=(\w+)",
                      self.logs("caps"))
        return dict(sdk=int(m[1]), exact=m[2] == "true", granted=m[3] == "true", runtime=m[4] == "true") if m else None

    def notifications(self):
        """The app's posted notifications, parsed from `dumpsys notification --noredact`."""
        dump = self.sh("dumpsys notification --noredact", timeout=60)
        out = []
        for rec in re.split(r"(?=NotificationRecord\()", dump):
            if f"pkg={PKG}" not in rec.split("\n")[0]:
                continue
            title = re.search(r"android\.title=(?:String \()?(.*?)\)?\s*$", rec, re.M)
            text = re.search(r"android\.text=(?:String \()?(.*?)\)?\s*$", rec, re.M)
            out.append(dict(id=re.search(r" id=(\d+)", rec)[1], title=title[1] if title else "",
                            text=text[1] if text else "", actions=re.findall(r'\[\d\] "(.*?)" ->', rec)))
        return out

    def wait_notification(self, title, timeout=200):
        end = time.time() + timeout
        while time.time() < end:
            n = next((x for x in self.notifications() if x["title"] == title), None)
            if n:
                return n
            time.sleep(4)
        return None

    def open_shade(self):
        self.sh("cmd statusbar expand-notifications || service call statusbar 1")
        time.sleep(2)

    def shade_tap(self, title, target):
        """Tap `target` (an action label, or the body when target is None) of the card titled `title`."""
        self.open_shade()
        for attempt in range(3):
            root = self.ui()
            card = self.find(root, title)
            if card is None:
                self.sh("input swipe 540 1800 540 600 300")  # scroll the shade
                continue
            node = card if target is None else self.find(root, target, ci=True)
            if node is None:  # collapsed card: pull it open
                x, y = self.center(card)
                self.sh(f"input swipe {x} {y} {x} {y + 250} 300")
                time.sleep(1.2)
                continue
            x, y = self.center(node)
            self.sh(f"input tap {x} {y}")
            time.sleep(2)
            return True
        return False

    def kill_background(self):
        """Process death that keeps the alarms (unlike force-stop)."""
        self.sh("input keyevent KEYCODE_HOME")
        time.sleep(1)
        self.sh(f"am kill {PKG}")
        time.sleep(1)

    def a11y_issues(self):
        """Interactive nodes with no name (own or descendant text/description) or a target under 48 dp.
        Only controls fully inside the app window are judged: a row or button scrolled half out of the viewport is
        reported clipped (a few px high, its label gone), which is not a defect."""
        dens = int(re.search(r"(\d+)", self.sh("wm density").split("Override")[-1]).group(1))
        root = self.ui()
        first = next(root.iter("node"))
        wx0, wy0, wx1, wy1 = map(int, re.findall(r"\d+", first.get("bounds")))
        issues = []

        def names(n):
            out = [n.get("text", ""), n.get("content-desc", "")]
            for c in n:
                out += names(c)
            return [o for o in out if o]

        def walk(n):
            if n.get("clickable") == "true" or n.get("checkable") == "true" or "EditText" in n.get("class", ""):
                x0, y0, x1, y1 = map(int, re.findall(r"\d+", n.get("bounds")))
                w, h = (x1 - x0) * 160 // dens, (y1 - y0) * 160 // dens
                inside = y0 > wy0 and y1 < wy1 and x0 >= wx0 and x1 <= wx1
                if inside:
                    # Text fields are named by their hint (placeholder); everything else needs its own name.
                    if not names(n) and "EditText" not in n.get("class", ""):
                        issues.append(f"unnamed {n.get('class').split('.')[-1]} {w}x{h}dp {n.get('bounds')}")
                    elif w < 48 or h < 48:
                        issues.append(f"small {w}x{h}dp '{' / '.join(names(n))[:30]}' {n.get('bounds')}")
            for c in n:
                walk(c)

        walk(root)
        return issues


# ------------------------------------------------------------------------------------------------------------------
RESULTS = []


def instant_epoch(text):
    """`2026-09-21T07:14:00.011Z` (how the debug receiver prints an Instant) -> epoch seconds."""
    from datetime import datetime
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def record(cid, status, detail=""):
    RESULTS.append(dict(id=cid, status=status, detail=detail))
    print(f"  [{status:4}] {cid}" + (f" - {detail}" if detail else ""))


def check(cid):
    def deco(fn):
        fn.cid = cid
        fn.order = len(CHECKS)
        CHECKS.append(fn)
        return fn
    return deco


CHECKS = []
TAG = ""


@check("launch")
def c_launch(d):
    d.sh("logcat -c")
    d.launch()
    focus = d.package_on_top()
    crashed = "FATAL EXCEPTION" in d.sh("logcat -d -b crash")
    ok = PKG in focus and not crashed and (d.has("¿Qué toca ahora?") or d.has("Nada pendiente."))
    record("launch", "PASS" if ok else "FAIL", "Hoy shown, no crash" if ok else f"focus={focus.strip()[:80]} crash={crashed}")


@check("caps")
def c_caps(d):
    c = d.caps()
    if not c:
        return record("caps", "FAIL", "no caps line")
    want_runtime = c["sdk"] >= 33
    ok = c["runtime"] == want_runtime and (c["granted"] is (not want_runtime))
    record("caps", "PASS" if ok else "FAIL",
           f"sdk={c['sdk']} runtimeNotifPermission={c['runtime']} notificationsGranted={c['granted']} "
           f"exactAllowed={c['exact']} (fresh install)")


@check("navigation")
def c_navigation(d):
    heads = [("BANDEJA", "¿Qué tienes en la cabeza?"), ("TAREAS", "Tareas"), ("HOY", "¿Qué toca ahora?")]
    for tab, head in heads:
        if not (d.tap(tab) and d.wait_text(head, 6) is not None):
            return record("navigation", "FAIL", f"{tab} -> '{head}' not shown")
    if not (d.tap("Ajustes") and d.wait_text("RECORDATORIOS", 6) is not None):
        return record("navigation", "FAIL", "Ajustes not shown")
    d.sh("input keyevent KEYCODE_BACK")
    ok = d.wait_text("¿Qué toca ahora?", 6) is not None
    record("navigation", "PASS" if ok else "FAIL", "tabs + Ajustes + back (warm)" if ok else "back from Ajustes did not reach Hoy")


@check("quick_capture")
def c_capture(d):
    title = "Matriz_captura"
    if not d.tap("BANDEJA"):
        return record("quick_capture", "FAIL", "no BANDEJA tab")
    root = d.ui()
    field = d.edit_field(root)
    if field is None:
        return record("quick_capture", "FAIL", "no capture field")
    x, y = d.center(field)
    d.sh(f"input tap {x} {y}")
    time.sleep(1)
    d.sh(f"input text {title}")
    time.sleep(0.5)
    d.sh("input keyevent 66")
    time.sleep(1.5)
    shown = d.find(d.ui(), title) is not None
    t = d.task(title)
    ok = shown and t is not None and not t["done"]
    record("quick_capture", "PASS" if ok else "FAIL", f"listed={shown} in Room={t is not None}")


@check("persistence")
def c_persist(d):
    d.sh(f"am force-stop {PKG}")
    d.launch()
    d.tap("BANDEJA")
    shown = d.find(d.ui(), "Matriz_captura") is not None
    t = d.task("Matriz_captura")
    ok = shown and t is not None
    record("persistence", "PASS" if ok else "FAIL", f"after force-stop + relaunch: listed={shown} in Room={t is not None}")


@check("edit")
def c_edit(d):
    d.tap("BANDEJA")
    if not d.tap("Matriz_captura"):
        return record("edit", "FAIL", "row not tappable")
    if d.wait_text("Editar tarea", 8) is None:
        return record("edit", "FAIL", "editor did not open")
    root = d.ui()
    field = next((n for n in root.iter("node") if "EditText" in n.get("class", "") and n.get("text") == "Matriz_captura"), None)
    if field is None:
        return record("edit", "FAIL", "title field not prefilled")
    x, y = d.center(field)
    d.sh(f"input tap {x} {y}")
    time.sleep(0.8)
    d.sh("input keyevent KEYCODE_MOVE_END")
    d.sh("input text _editada")
    time.sleep(0.8)
    d.sh("input keyevent KEYCODE_BACK")  # hides the IME only
    time.sleep(1)
    if not d.tap("Guardar"):
        return record("edit", "FAIL", "no Guardar")
    time.sleep(1.5)
    t = d.task("Matriz_captura_editada")
    ok = t is not None and d.find(d.ui(), "Matriz_captura_editada") is not None
    record("edit", "PASS" if ok else "FAIL", "title edited, saved, shown" if ok else f"saved={t is not None}")


@check("hoy_foco")
def c_foco(d):
    today = d.sh("date +%Y-%m-%d").strip()
    d.seed("Matriz_foco", reminder=False, priority=1, date=today, time_="23:58")
    d.sh(f"am force-stop {PKG}")
    d.launch()
    if d.wait_text("AHORA", 8) is None or d.find(d.ui(), "Matriz_foco") is None:
        return record("hoy_foco", "FAIL", "Hoy has no Ahora card for the P1 task")
    if not d.tap("Empezar"):
        return record("hoy_foco", "FAIL", "no Empezar")
    root = d.wait_text("Terminar", 8)
    clock = any(re.fullmatch(r"\d\d:\d\d", d.label(n)) for n in d.ui().iter("node"))
    if root is None or not clock:
        return record("hoy_foco", "FAIL", f"Foco incomplete (Terminar={root is not None}, clock={clock})")
    d.tap("Terminar")
    time.sleep(1.5)
    t = d.task("Matriz_foco")
    ok = t is not None and t["done"] and d.has("¿Qué toca ahora?")
    record("hoy_foco", "PASS" if ok else "FAIL", "Ahora -> Empezar -> Foco (clock) -> Terminar -> done, back on Hoy" if ok else f"done={t and t['done']}")


@check("permission_dialog")
def c_perm_dialog(d):
    if d.sdk < 33:
        return record("permission_dialog", "SKIP", "no runtime notification permission below API 33")
    d.sh(f"pm revoke {PKG} android.permission.POST_NOTIFICATIONS")
    d.launch(fresh=True)
    d.tap("Nueva tarea")
    d.tap("Hoy")
    d.tap("Sin hora")
    time.sleep(1.5)
    for ok_label in ("OK", "Aceptar", "Confirm"):
        if d.tap(ok_label, timeout=2):
            break
    sw = d.find_switch(d.ui(), "Avisarme")
    if sw is None:
        d.sh("input keyevent KEYCODE_BACK")
        return record("permission_dialog", "FAIL", "reminder switch not reachable")
    x, y = d.center(sw)
    d.sh(f"input tap {x} {y}")
    time.sleep(2)
    top = d.package_on_top()
    shown = "permissioncontroller" in top or "grantpermissions" in top.lower()
    if shown:
        for lbl in ("Allow", "Permitir"):
            if d.tap(lbl, timeout=3):
                break
    time.sleep(1.5)
    granted = (d.caps() or {}).get("granted")
    d.sh("input keyevent KEYCODE_BACK")
    d.sh("input keyevent KEYCODE_BACK")
    record("permission_dialog", "PASS" if shown and granted else "FAIL",
           f"system dialog appeared on the switch tap={shown}; granted after Allow={granted}")


@check("reminders")
def c_reminders(d):
    """Real alarms: delivery, notification body, HECHO, +10 MIN, ABRIR (cold), body (warm)."""
    if d.sdk >= 33:
        d.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    if d.sdk >= 31:
        d.sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")
    d.launch(fresh=True)

    def deliver(title, cold=False):
        d.seed(title, minutes=1, reminder=True, priority=2)
        if cold:
            d.kill_background()
        return d.wait_notification(title, 200)

    # 1. delivery + content + HECHO
    n = deliver("Matriz_HECHO")
    if not n:
        return record("reminders", "FAIL", "reminder was not delivered within 200 s")
    body_ok = bool(n["text"]) and n["actions"] == ["HECHO", "+10 MIN", "ABRIR"]
    record("reminder_delivery", "PASS" if body_ok else "FAIL", f"body='{n['text']}' actions={n['actions']}")
    ok = d.shade_tap("Matriz_HECHO", "HECHO")
    t = d.task("Matriz_HECHO")
    gone = not any(x["title"] == "Matriz_HECHO" for x in d.notifications())
    record("action_HECHO", "PASS" if ok and t and t["done"] and gone else "FAIL", f"done={t and t['done']} card dismissed={gone}")

    # 2. +10 MIN
    n = deliver("Matriz_MAS10")
    if not n:
        return record("action_MAS10", "FAIL", "second reminder not delivered")
    ok = d.shade_tap("Matriz_MAS10", "+10 MIN")
    t = d.task("Matriz_MAS10")
    snooze = instant_epoch(t["snooze"]) if t and t["snooze"] != "null" else None
    delta = round(snooze - d.epoch()) if snooze else None
    gone = not any(x["title"] == "Matriz_MAS10" for x in d.notifications())
    good = ok and delta is not None and 540 <= delta <= 600 and not t["done"] and gone
    record("action_MAS10", "PASS" if good else "FAIL", f"snooze in {delta}s (expect ~600), task kept open, card dismissed={gone}")

    # 3. ABRIR after process death (cold)
    n = deliver("Matriz_ABRIR", cold=True)
    if not n:
        return record("action_ABRIR_cold", "FAIL", "cold reminder not delivered")
    d.shade_tap("Matriz_ABRIR", "ABRIR")
    foco = d.wait_text("Terminar", 8) is not None and d.find(d.ui(), "Matriz_ABRIR") is not None
    d.sh("input keyevent KEYCODE_BACK")
    back = d.wait_text("¿Qué toca ahora?", 6) is not None
    record("action_ABRIR_cold", "PASS" if foco and back else "FAIL", f"Foco opened={foco}; back -> Hoy={back}")

    # 4. body while the app is open on another tab (warm)
    d.seed("Matriz_CUERPO", minutes=1, reminder=True, priority=2)
    d.launch()
    d.tap("BANDEJA")
    n = d.wait_notification("Matriz_CUERPO", 200)
    if not n:
        return record("action_body_warm", "FAIL", "warm reminder not delivered")
    d.shade_tap("Matriz_CUERPO", None)
    hoy = d.wait_text("¿Qué toca ahora?", 8) is not None
    record("action_body_warm", "PASS" if hoy else "FAIL", f"body tap from Bandeja -> Hoy={hoy}")


@check("notifications_denied")
def c_notif_denied(d):
    if d.sdk < 33:
        return record("notifications_denied", "SKIP", "runtime permission only exists on API 33+")
    d.sh(f"pm revoke {PKG} android.permission.POST_NOTIFICATIONS")
    d.launch()
    d.seed("Matriz_SINPERMISO", minutes=1, reminder=True, priority=2)
    time.sleep(110)
    posted = any(x["title"] == "Matriz_SINPERMISO" for x in d.notifications())
    t = d.task("Matriz_SINPERMISO")
    d.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    record("notifications_denied", "FAIL" if posted else "PASS",
           f"nothing posted without permission={not posted}; task kept={t is not None and not t['done']}; fired={t and t['fired']}")


@check("exact_alarm_degraded")
def c_exact_degraded(d):
    if d.sdk < 31:
        return record("exact_alarm_degraded", "SKIP", "SCHEDULE_EXACT_ALARM does not exist below API 31")
    d.sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM deny")
    time.sleep(2)
    d.launch()
    c = d.caps()
    d.seed("Matriz_INEXACTA", minutes=1, reminder=True, priority=2)
    t0 = time.time()
    n = d.wait_notification("Matriz_INEXACTA", 480)
    late = round(time.time() - t0)
    d.sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")
    record("exact_alarm_degraded", "PASS" if n else "FAIL",
           f"exact denied (canScheduleExact={c and c['exact']}); reminder due in <=60 s delivered after {late} s" if n
           else "not delivered within 8 min without exact-alarm access")


@check("a11y")
def c_a11y(d):
    problems = []
    for scale in ("1.0", "2.0"):
        d.sh(f"settings put system font_scale {scale}")
        d.launch(fresh=True)
        for name, nav in (("Hoy", "HOY"), ("Bandeja", "BANDEJA"), ("Tareas", "TAREAS")):
            d.tap(nav)
            problems += [f"[{scale}] {name}: {i}" for i in d.a11y_issues()]
        d.tap("Ajustes")
        problems += [f"[{scale}] Ajustes: {i}" for i in d.a11y_issues()]
        d.sh("input keyevent KEYCODE_BACK")
        d.tap("Nueva tarea")
        time.sleep(1.5)
        d.sh("input keyevent KEYCODE_BACK")  # hide the IME
        time.sleep(1)
        problems += [f"[{scale}] editor: {i}" for i in d.a11y_issues()]
        d.sh("input keyevent KEYCODE_BACK")
    d.sh("settings put system font_scale 1.0")
    record("a11y", "FAIL" if problems else "PASS",
           "; ".join(problems[:6]) if problems else "no unnamed or <48dp controls on 5 screens at 100% and 200% font")


@check("reboot")
def c_reboot(d):
    if d.sdk >= 31:
        d.sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")
    if d.sdk >= 33:
        d.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    d.launch()
    d.seed("Matriz_REBOOT", minutes=8, reminder=True, priority=2)
    d.sh("input keyevent KEYCODE_HOME")
    t0 = time.time()
    d.adb("reboot", timeout=30)
    time.sleep(8)
    if not d.wait_boot(300):
        return record("reboot", "FAIL", "device did not finish booting")
    d.wake()
    booted = round(time.time() - t0)
    armed = None
    end = time.time() + 120  # NOTE: the app is never opened after the reboot
    while time.time() < end:
        if "FIRE_REMINDERS" in d.sh(f"dumpsys alarm | grep -A3 {PKG}"):
            armed = round(time.time() - t0) - booted
            break
        time.sleep(3)
    if armed is None:
        return record("reboot", "FAIL", "alarm NOT re-armed after boot (app never opened)")
    n = d.wait_notification("Matriz_REBOOT", 480)
    record("reboot", "PASS" if n else "FAIL",
           f"alarm re-armed {armed} s after boot without opening the app; delivered after reboot={n is not None}")


# ------------------------------------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--avd")
    ap.add_argument("--port", type=int, default=5556)
    ap.add_argument("--apk", default=DEBUG_APK)
    ap.add_argument("--only")
    ap.add_argument("--skip", default="")
    ap.add_argument("--keep-running", action="store_true")
    a = ap.parse_args()
    if not a.serial and not a.avd:
        ap.error("--serial or --avd")

    emu = None
    serial = a.serial
    if a.avd:
        serial = f"emulator-{a.port}"
        emu = subprocess.Popen([tool("emulator", "emulator"), "-avd", a.avd, "-port", str(a.port), "-no-snapshot",
                                "-no-boot-anim", "-no-audio", "-no-window", "-gpu", "swiftshader_indirect"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    d = Dev(serial)
    try:
        if not d.wait_boot(420):
            raise SystemExit("device did not boot")
        d.wake()
        info = f"{d.sh('getprop ro.product.model').strip()} / Android {d.sh('getprop ro.build.version.release').strip()} " \
               f"(API {d.sdk}) / {'emulator' if 'emulator' in serial or d.sh('getprop ro.kernel.qemu').strip() == '1' else 'PHYSICAL DEVICE'}"
        print(f"== AHORA device matrix: {info}  serial={serial}")
        for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            d.sh(f"settings put global {k} 0")
        d.sh("settings put system font_scale 1.0; settings put system accelerometer_rotation 0; settings put system user_rotation 0")
        d.sh("settings put secure show_ime_with_hard_keyboard 1")
        d.adb("uninstall", PKG)
        out = d.adb("install", "-r", a.apk, timeout=240)
        print("  install:", out.strip().splitlines()[-1] if out.strip() else "?")
        only = set(a.only.split(",")) if a.only else None
        skip = set(filter(None, a.skip.split(",")))
        for fn in CHECKS:
            if (only and fn.cid not in only) or fn.cid in skip:
                continue
            try:
                fn(d)
            except Exception as e:  # a broken check is a failed check, never a silent pass
                record(fn.cid, "FAIL", f"exception: {type(e).__name__}: {e}")
        os.makedirs(os.path.join(REPO, "build", "device-matrix"), exist_ok=True)
        path = os.path.join(REPO, "build", "device-matrix", f"api{d.sdk}-{a.avd or serial}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(dict(device=info, results=RESULTS), f, indent=1, ensure_ascii=False)
        fails = [r for r in RESULTS if r["status"] == "FAIL"]
        print(f"== {info}: {sum(r['status']=='PASS' for r in RESULTS)} pass, {len(fails)} fail, "
              f"{sum(r['status']=='SKIP' for r in RESULTS)} skip -> {path}")
        return 1 if fails else 0
    finally:
        if emu and not a.keep_running:
            d.adb("emu", "kill", timeout=30)
            time.sleep(3)
            emu.terminate()


if __name__ == "__main__":
    sys.exit(main())
