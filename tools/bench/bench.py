"""Runs one crowd bench on the bench server and prints its result lines.

  python bench.py <label> <mode: alternate|on|off> <sites> <perSite> <hotspots> <hotspotSize> <ticks> <block> <seed>
                  [--toggle=switchA,switchB] [--jfr]

--toggle: alternate only these config switches (the rest of the mod stays on); default: the whole mod.
--jfr: a Flight Recorder profile of the measured ticks is written to build/jfr/<label>.jfr (read it with jfrtool.py).
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = r"C:\tmp\aibench-server\server-console.log"
JCMD = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\jcmd.exe"
JFR_DIR = os.path.join(os.path.dirname(os.path.dirname(HERE)), "build", "jfr")


def rcon(*cmds):
    return subprocess.run([sys.executable, os.path.join(HERE, "rcon.py"), *cmds], capture_output=True, text=True).stdout


def server_pid():
    out = subprocess.run([JCMD, "-l"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "fabric-server-launch.jar" in line:
            return line.split()[0]
    sys.exit("bench server not running")


def wait_for(needle, start_offset, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            f.seek(start_offset)
            text = f.read()
        if needle in text:
            return text
        if "fabric-server-launch.jar" not in subprocess.run([JCMD, "-l"], capture_output=True, text=True).stdout:
            print(text[-3000:])
            sys.exit("server is gone")
        if "crowd bench failed" in text:
            print(text[-3000:])
            sys.exit("bench failed")
        time.sleep(1)
    sys.exit("timeout waiting for " + needle)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    jfr = "--jfr" in sys.argv
    label, mode = args[0], args[1]
    sites, per_site, hotspots, hotspot_size, ticks, block, seed = args[2:9]
    offset = os.path.getsize(LOG)
    server_pid()
    toggle = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--toggle=")), "all")
    print(rcon("fasterai bench toggle " + toggle, "fasterai bench mode " + mode,
               f"fasterai bench crowd {sites} {per_site} {hotspots} {hotspot_size} {ticks} {block} {seed} {label}").strip())
    wait_for("measuring", offset, 900)
    pid = server_pid()
    if jfr:
        os.makedirs(JFR_DIR, exist_ok=True)
        subprocess.run([JCMD, pid, "JFR.start", "name=bench", "settings=profile"], capture_output=True, text=True)
    text = wait_for("STATS census", offset, 3600)
    if jfr:
        path = os.path.join(JFR_DIR, label + ".jfr")
        if os.path.exists(path):
            os.remove(path)
        subprocess.run([JCMD, pid, "JFR.stop", "name=bench", "filename=" + path], capture_output=True, text=True)
        print("jfr:", path)
    for line in text.splitlines():
        if "[bench]" in line and ("OFF" in line or "ON" in line or "STATS" in line or "done" in line or "CENSUS" in line):
            print(line.split("[bench]", 1)[1].rstrip())


if __name__ == "__main__":
    main()
