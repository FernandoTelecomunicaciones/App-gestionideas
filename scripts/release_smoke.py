#!/usr/bin/env python3
"""AHORA signed-release smoke (docs/RELEASE.md, D-35).

Exercises a RELEASE apk (R8, no debug hook) on one device or emulator using only the real UI and the system:
signature + installed metadata, launch, capture, persistence across force-stop and process death, edit, Hoy -> Foco ->
Terminar, four REAL reminders made through the editor and the time dial (permission dialog included), notification
delivery/body/actions, ABRIR after process death, HECHO, +10 MIN with its real re-delivery, reboot recovery with the
app never opened, and a crash-log check.

    python scripts/release_smoke.py --serial emulator-5554 --apk app/build/outputs/apk/release/app-release.apk
    python scripts/release_smoke.py --serial <physical serial> --apk ... --skip-reboot      # phone with a lock screen

The signer certificate SHA-256 is printed so it can be compared with the owner's key. `--allow-debug-key` is ONLY for
rehearsing the script with a debug-signed build; a debug-signed run is never the final release test.
Takes ~30 min (real alarms). Standard library only.
"""
import argparse, glob, os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device_matrix import ACT, PKG, REPO, Dev, RESULTS, record, sdk_root  # noqa: E402


def apksigner():
    found = sorted(glob.glob(os.path.join(sdk_root(), "build-tools", "*", "apksigner*")), reverse=True)
    found = [f for f in found if f.endswith((".bat", "apksigner"))]
    if not found:
        raise SystemExit("apksigner not found (Android SDK build-tools)")
    return found[0]


def verify_signature(apk, allow_debug):
    env = dict(os.environ)
    if "JAVA_HOME" not in env and os.path.isdir(r"C:\Program Files\Android\Android Studio\jbr"):
        env["JAVA_HOME"] = r"C:\Program Files\Android\Android Studio\jbr"
    r = subprocess.run([apksigner(), "verify", "--print-certs", "-v", apk], capture_output=True, env=env)
    out = (r.stdout + r.stderr).decode("utf-8", "replace")
    verifies = out.lstrip().startswith("Verifies")
    v2 = "APK Signature Scheme v2): true" in out
    dn = (re.search(r"Signer.*certificate DN: (.*)", out) or [None, "?"])[1].strip()
    sha = (re.search(r"Signer.*certificate SHA-256 digest: (\w+)", out) or [None, "?"])[1]
    debug = "Android Debug" in dn
    status = "PASS" if verifies and v2 and (not debug or allow_debug) else "FAIL"
    record("signature", status, f"verifies={verifies} v2={v2} signer='{dn}' SHA-256={sha}" +
           ("  [DEBUG KEY - rehearsal only]" if debug else ""))
    return status == "PASS"


def hide_ime(d):
    if "mInputShown=true" in d.sh("dumpsys input_method | grep mInputShown"):
        d.sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)


def pick_time(d, h24, minute):
    h12 = h24 % 12 or 12
    ok = (d.tap(f"{h12} o'clock", 6) or d.tap(f"{h12} en punto", 2)) and \
         (d.tap(f"{minute} minutes", 6) or d.tap(f"{minute} minutos", 2)) and d.tap("PM" if h24 >= 12 else "AM", 6)
    return ok and d.tap("Aceptar", 6)


def create_task(d, title, priority=None, at=None, reminder=False):
    """Create through the real editor. `at` = (hour24, minute) for today."""
    if not d.tap("Nueva tarea"):
        return False
    time.sleep(1.2)
    d.sh(f"input text {title}")
    time.sleep(0.6)
    hide_ime(d)
    if at or priority or reminder:
        d.tap("Hoy")
    if priority:
        d.tap(f"P{priority} · " + {1: "Importante", 2: "Normal", 3: "Puede esperar"}[priority])
    if at:
        if not d.tap("Sin hora") or not pick_time(d, *at):
            return False
    if reminder:
        sw = d.find_switch(d.ui(), "Avisarme")
        if sw is None:
            return False
        x, y = d.center(sw)
        d.sh(f"input tap {x} {y}")
        time.sleep(2)
        if "permissioncontroller" in d.package_on_top():
            d.tap("Allow", 4) or d.tap("Permitir", 2)
            time.sleep(1)
    hide_ime(d)
    ok = d.tap("Guardar")
    time.sleep(1.5)
    return ok and d.find(d.ui(), "Nueva tarea", cls="TextView") is None


def status_of(d, title):
    """'done' | 'pending' | 'absent', read from Tareas (Completadas link for done)."""
    d.sh("cmd statusbar collapse")  # a shade action (HECHO, +10 MIN) leaves the shade open over the app
    time.sleep(1)
    d.tap("TAREAS")
    if d.has(title):
        return "pending"
    if d.tap("Completadas", 4):
        found = d.has(title)
        d.sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
        d.tap("HOY")
        return "done" if found else "absent"
    d.tap("HOY")
    return "absent"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--version-name", default="0.1.0")
    ap.add_argument("--version-code", default="1")
    ap.add_argument("--allow-debug-key", action="store_true")
    ap.add_argument("--skip-reboot", action="store_true")
    a = ap.parse_args()
    apk = os.path.abspath(a.apk)
    d = Dev(a.serial)

    print(f"== AHORA release smoke: {os.path.basename(apk)} on {a.serial}")
    if not verify_signature(apk, a.allow_debug_key):
        return 1
    d.wait_boot(60)
    d.wake()
    info = f"{d.sh('getprop ro.product.model').strip()} / Android {d.sh('getprop ro.build.version.release').strip()} (API {d.sdk})"
    kind = "emulator" if d.sh("getprop ro.kernel.qemu").strip() == "1" else "PHYSICAL DEVICE"
    print(f"   device: {info} / {kind}")
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        d.sh(f"settings put global {k} 0")
    d.sh("settings put system font_scale 1.0; settings put system accelerometer_rotation 0; settings put system user_rotation 0")

    d.adb("uninstall", PKG)
    out = d.adb("install", "-r", apk, timeout=240)
    if "Success" not in out:
        return record("install", "FAIL", out.strip()[-200:]) or 1
    pkg = d.sh(f"dumpsys package {PKG}", timeout=60)
    vn = (re.search(r"versionName=(\S+)", pkg) or [None, "?"])[1]
    vc = (re.search(r"versionCode=(\d+)", pkg) or [None, "?"])[1]
    flags = (re.search(r"\n\s+flags=\[(.*?)\]", pkg) or [None, ""])[1]
    perms = re.search(r"requested permissions:(.*?)(?:install permissions:|\n\s*User \d)", pkg, re.S)
    requested = re.findall(r"android\.permission\.\w+|com\.android\.alarm\.permission\.\w+", perms[1]) if perms else []
    good = vn == a.version_name and vc == a.version_code and "DEBUGGABLE" not in flags \
        and "INTERNET" not in " ".join(requested) and "DebugCommandReceiver" not in pkg
    record("installed_metadata", "PASS" if good else "FAIL",
           f"version {vn} ({vc}) expected {a.version_name} ({a.version_code}); debuggable={'DEBUGGABLE' in flags}; "
           f"INTERNET requested={'INTERNET' in ' '.join(requested)}; debug receiver present={'DebugCommandReceiver' in pkg}")
    if d.sdk >= 31:
        d.sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")  # what the user does in Ajustes; timing stays deterministic
    d.sh("logcat -c")

    d.launch(fresh=True)
    record("launch", "PASS" if d.wait_text("¿Qué toca ahora?", 10) is not None else "FAIL", "Hoy shown")

    # --- capture, persistence, edit (release Room + Compose + Hilt + serialization routes) ---------------------
    d.tap("BANDEJA")
    field = d.edit_field(d.ui())
    x, y = d.center(field)
    d.sh(f"input tap {x} {y}")
    d.sh("input text Smoke_captura")
    d.sh("input keyevent 66")
    time.sleep(1.5)
    listed = d.has("Smoke_captura")
    d.sh(f"am force-stop {PKG}")
    d.launch()
    d.tap("BANDEJA")
    after_stop = d.has("Smoke_captura")
    d.kill_background()
    d.launch()
    d.tap("BANDEJA")
    after_kill = d.has("Smoke_captura")
    record("capture_persistence", "PASS" if listed and after_stop and after_kill else "FAIL",
           f"listed={listed}; after force-stop={after_stop}; after process death={after_kill}")

    d.tap("Smoke_captura")
    edited = False
    if d.wait_text("Editar tarea", 8) is not None:
        f = next((n for n in d.ui().iter("node") if "EditText" in n.get("class", "") and n.get("text") == "Smoke_captura"), None)
        if f is not None:
            x, y = d.center(f)
            d.sh(f"input tap {x} {y}")
            d.sh("input keyevent KEYCODE_MOVE_END")
            d.sh("input text _editada")
            hide_ime(d)
            d.tap("Guardar")
            time.sleep(1.5)
            edited = d.has("Smoke_captura_editada")
    record("edit", "PASS" if edited else "FAIL", "title edited and saved")

    # --- Hoy -> Foco -> Terminar ---------------------------------------------------------------------------------
    made = create_task(d, "Smoke_foco", priority=1)
    d.tap("HOY")
    ahora = d.wait_text("AHORA", 8) is not None and d.has("Smoke_foco")
    foco = False
    if ahora and d.tap("Empezar"):
        foco = d.wait_text("Terminar", 8) is not None and any(re.fullmatch(r"\d\d:\d\d", d.label(n)) for n in d.ui().iter("node"))
        d.tap("Terminar")
        time.sleep(1.5)
    record("hoy_foco", "PASS" if made and ahora and foco and status_of(d, "Smoke_foco") == "done" else "FAIL",
           f"created={made} Ahora card={ahora} Foco+clock={foco} completed")

    # --- real reminders: A (cold ABRIR), B (HECHO), C (+10 MIN), D (reboot) --------------------------------------
    hh, mm = map(int, d.sh("date +%H:%M").strip().split(":"))
    start = hh * 60 + mm
    first = -(-(start + 7) // 5) * 5  # next multiple of 5 minutes that leaves ~4 min to create four tasks
    last = first + (15 if not a.skip_reboot else 10) + 12
    if last >= 24 * 60:
        return record("reminders", "FAIL", "too close to midnight for a same-day run; start again after 00:05") or 1
    slots = {"A": first, "B": first + 5, "C": first + 10, "D": first + 15}
    titles = {k: f"Smoke_{k}" for k in slots}
    created = {}
    for k, t in slots.items():
        if k == "D" and a.skip_reboot:
            continue
        created[k] = create_task(d, titles[k], at=(t // 60, t % 60), reminder=True)
    record("reminders_created", "PASS" if all(created.values()) else "FAIL",
           f"via editor + time dial: " + ", ".join(f"{k}@{slots[k]//60:02d}:{slots[k]%60:02d}={v}" for k, v in created.items())
           + f" (device time was {hh:02d}:{mm:02d})")
    d.kill_background()  # A must arrive with the process dead

    def wait(k, extra=240):
        return d.wait_notification(titles[k], max(60, (slots[k] - start) * 60 + extra))

    n = wait("A")
    if not n:
        record("reminder_A_delivery", "FAIL", "not delivered")
    else:
        record("reminder_A_delivery", "PASS" if n["text"] and n["actions"] == ["HECHO", "+10 MIN", "ABRIR"] else "FAIL",
               f"process was dead; body='{n['text']}' actions={n['actions']}")
        d.shade_tap(titles["A"], "ABRIR")
        foco = d.wait_text("Terminar", 8) is not None and d.find(d.ui(), titles["A"]) is not None
        d.tap("Terminar")
        time.sleep(1.5)
        record("action_ABRIR_cold", "PASS" if foco and status_of(d, titles["A"]) == "done" else "FAIL",
               f"Foco opened for the task={foco}; Terminar completed it")

    n = wait("B")
    if not n:
        record("action_HECHO", "FAIL", "B not delivered")
    else:
        d.shade_tap(titles["B"], "HECHO")
        gone = not any(x["title"] == titles["B"] for x in d.notifications())
        record("action_HECHO", "PASS" if gone and status_of(d, titles["B"]) == "done" else "FAIL", f"card dismissed={gone}; task done")

    n = wait("C")
    if not n:
        record("action_MAS10", "FAIL", "C not delivered")
    else:
        d.shade_tap(titles["C"], "+10 MIN")
        gone = not any(x["title"] == titles["C"] for x in d.notifications())
        pending = status_of(d, titles["C"]) == "pending"
        record("action_MAS10", "PASS" if gone and pending else "FAIL", f"card dismissed={gone}; task still open={pending}")

    if not a.skip_reboot:
        d.adb("reboot", timeout=30)
        time.sleep(8)
        if not d.wait_boot(300):
            record("reboot", "FAIL", "did not boot")
        else:
            d.wake()
            t0 = time.time()
            armed = False
            while time.time() - t0 < 120:
                if "FIRE_REMINDERS" in d.sh(f"dumpsys alarm | grep -A3 {PKG}"):
                    armed = True
                    break
                time.sleep(3)
            n = wait("D")
            record("reboot", "PASS" if armed and n else "FAIL",
                   f"alarm re-armed without opening the app={armed}; D delivered after reboot={n is not None}")
            if n:
                d.shade_tap(titles["D"], None)
                hoy = d.wait_text("¿Qué toca ahora?", 10) is not None
                record("action_body_cold", "PASS" if hoy else "FAIL", f"body tap after reboot (cold) opened Hoy={hoy}")

    # C's snooze must really come back ~10 minutes later
    n = d.wait_notification(titles["C"], (slots["C"] + 10 - start) * 60 + 300)
    if not n:
        record("snooze_redelivery", "FAIL", "C never came back after +10 MIN")
    else:
        d.shade_tap(titles["C"], "HECHO")
        record("snooze_redelivery", "PASS", "snoozed reminder was re-delivered and completed")

    crashed = "FATAL EXCEPTION" in d.sh("logcat -d -b crash")
    record("no_crash", "FAIL" if crashed else "PASS", "no FATAL EXCEPTION in the crash log for the whole run")

    fails = [r for r in RESULTS if r["status"] == "FAIL"]
    print(f"== {info} [{kind}]: {sum(r['status']=='PASS' for r in RESULTS)} pass, {len(fails)} fail")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
