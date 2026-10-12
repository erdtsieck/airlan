"""Recreates the Play Store screenshots (docs/play-store/screenshots) on an emulator.

Start three stand-in units (tools/fake-unit.mjs) on three addresses of this machine that the
emulator can reach (10.0.2.2 is the host's loopback; any other address of the host works),
for example:

  node tools/fake-unit.mjs --host 127.0.0.1    --id fa4e00000001 --power on  --mode heat --temp 21.5 --indoor 19.5 --outdoor 8.5
  node tools/fake-unit.mjs --host 172.31.176.1 --id fa4e00000002 --power on  --mode cool --temp 22   --indoor 24.5 --outdoor 8.5
  node tools/fake-unit.mjs --host 172.28.0.1   --id fa4e00000003 --power off --mode cool --temp 20   --indoor 21   --outdoor 8.5

then, with a debug build installed on a 1080x2400 emulator:

  python tools/play-screenshots.py --serial emulator-5580 --hosts 10.0.2.2,172.31.176.1,172.28.0.1

It replaces the app's state, so use an emulator. Needs Pillow. Captures at 1080x2400 and
crops to 1080x1920: Play wants phone screenshots in 9:16.
"""
import argparse, json, os, subprocess, tempfile, time
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--hosts", default="10.0.2.2,172.31.176.1,172.28.0.1", help="addresses of the three fake units, as the emulator reaches them")
parser.add_argument("--adb", default="adb", help="path to adb, if it is not on the PATH")
options = parser.parse_args()

ADB = options.adb
SERIAL = options.serial
HOSTS = options.hosts.split(",")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "play-store", "screenshots")
TMP = tempfile.mkdtemp(prefix="airlan-shots-")
PKG = "io.github.erdtsieck.airlan"


def adb(*args, check=True):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", check=check).stdout


def sh(cmd):
    return adb("shell", cmd)


def ui():
    sh("uiautomator dump /sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def tap_text(text):
    import re
    xml = ui()
    for node in xml.split("<node"):
        if f'text="{text}"' in node or f'content-desc="{text}"' in node:
            x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            return
    raise SystemExit(f"not on screen: {text}")


def state(lang_living, lang_bed, lang_study):
    now = int(time.time() * 1000)
    s = {
        "deviceId": "airlan-shots",
        "units": [
            {"airconId": "fa4e00000001", "name": lang_living, "host": HOSTS[0], "scheme": "HTTP", "offAt": now + 120 * 60_000 - 30_000, "accountMigrated": True},
            {"airconId": "fa4e00000002", "name": lang_bed, "host": HOSTS[1], "scheme": "HTTP", "accountMigrated": True},
            {"airconId": "fa4e00000003", "name": lang_study, "host": HOSTS[2], "scheme": "HTTP", "accountMigrated": True},
        ],
    }
    path = os.path.join(TMP, "state.json")
    with open(path, "w") as f:
        json.dump(s, f)
    adb("push", path, "/data/local/tmp/s.json")
    sh(f"run-as {PKG} cp /data/local/tmp/s.json files/state.json")


def shot(name, lang):
    time.sleep(4)
    raw = os.path.join(TMP, f"{lang}-{name}.png")
    with open(raw, "wb") as f:
        f.write(subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)
    img = Image.open(raw)
    assert img.size == (1080, 2400), img.size
    img.crop((0, 0, 1080, 1920)).save(os.path.join(OUT, lang, f"{name}.png"), optimize=True)


def demo_status_bar():
    sh("settings put global sysui_demo_allowed 1")
    clock = time.strftime("%H%M", time.gmtime())  # the emulator runs on UTC
    for extra in [
        "-e command enter",
        f"-e command clock -e hhmm {clock}",
        "-e command network -e wifi show -e level 4 -e fully true",
        "-e command network -e mobile hide",
        "-e command battery -e level 100 -e plugged false",
        "-e command notifications -e visible false",
    ]:
        sh(f"am broadcast -a com.android.systemui.demo {extra}")


NAMES = {"en": ("Living room", "Bedroom", "Study"), "nl": ("Woonkamer", "Slaapkamer", "Werkkamer")}
LOCALES = {"en": "en-US", "nl": "nl-NL"}

sh("settings put system font_scale 1.0")
sh("wm density reset")
sh("cmd uimode night no")
for lang in ("en", "nl"):
    living, bed, study = NAMES[lang]
    sh(f"am force-stop {PKG}")
    sh(f"cmd locale set-app-locales {PKG} --locales {LOCALES[lang]}")
    state(living, bed, study)
    demo_status_bar()
    sh(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(6)
    tap_text(living)
    shot("1-living-room", lang)
    tap_text(bed)
    shot("2-bedroom", lang)
    tap_text("Manage units" if lang == "en" else "Airco’s beheren")
    shot("3-manage", lang)
    sh(f"am force-stop {PKG}")
    sh("cmd uimode night yes")
    state(living, bed, study)
    demo_status_bar()
    sh(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(6)
    tap_text(living)
    shot("4-dark", lang)
    sh("cmd uimode night no")
sh("am broadcast -a com.android.systemui.demo -e command exit")
print("done")
