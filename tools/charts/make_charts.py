"""Draws the comparison charts of docs/images/ from the raw bench results of docs/mesures/.

  python tools/charts/make_charts.py

Each chart is written as an SVG, then rendered to a 1600x900 PNG with a headless Edge or Chrome.
Colours: categorical slots 1-3 of the dark palette (blue, orange, aqua), checked for colour-blind separation.
"""
import glob
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RESULTS = os.path.join(ROOT, "docs", "mesures")
OUT = os.path.join(ROOT, "docs", "images")
BROWSERS = [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
            r"C:\Program Files\Google\Chrome\Application\chrome.exe"]

W, H = 1600, 900
SURFACE, INK, INK2, MUTED, GRID, AXIS = "#1a1a19", "#f0efec", "#c3c2b7", "#898781", "#2c2c2a", "#55554f"
BLUE, ORANGE, AQUA = "#3987e5", "#d95926", "#199e70"
FONT = "'Segoe UI', system-ui, sans-serif"
LEFT, PLOT_X0, PLOT_X1, RIGHT = 72, 402, 1268, 1528
PLOT_Y0, PLOT_Y1 = 262, 770
BAR_H, BAR_GAP, RADIUS = 40, 4, 6


def result(label):
    files = sorted(glob.glob(os.path.join(RESULTS, "crowd_%s_*.json" % label)))
    if not files:
        sys.exit("missing result file for " + label)
    with open(files[-1], encoding="utf-8") as f:
        return json.load(f)


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, s, size, fill=INK, weight=400, anchor="start"):
    return '<text x="%.1f" y="%.1f" font-size="%d" font-weight="%d" fill="%s" text-anchor="%s">%s</text>' % (
        x, y, size, weight, fill, anchor, esc(s))


def bar(x0, y, width, colour):
    """A bar anchored on the baseline, with rounded data end."""
    r = min(RADIUS, width / 2)
    x1 = x0 + width
    return '<path d="M%.1f %.1f H%.1f Q%.1f %.1f %.1f %.1f V%.1f Q%.1f %.1f %.1f %.1f H%.1f Z" fill="%s"/>' % (
        x0, y, x1 - r, x1, y, x1, y + r, y + BAR_H - r, x1, y + BAR_H, x1 - r, y + BAR_H, x0, colour)


def chart(name, title, subtitle, legend, groups, axis_max, axis_step, footnote, limit=None):
    """groups: (label, sublabel, [value per series], gain text). legend: (colour, label) per series."""
    scale = (PLOT_X1 - PLOT_X0) / axis_max
    o = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" font-family="%s">' % (W, H, W, H, FONT),
         '<rect width="%d" height="%d" fill="%s"/>' % (W, H, SURFACE),
         text(LEFT, 112, title, 44, weight=700),
         text(LEFT, 160, subtitle, 26, fill=INK2)]
    x = LEFT
    for colour, label in legend:
        o.append('<rect x="%d" y="199" width="28" height="28" rx="5" fill="%s"/>' % (x, colour))
        o.append(text(x + 40, 222, label, 26))
        x += 40 + 14.2 * len(label) + 48
    # grid, axis labels
    v = 0
    while v <= axis_max + 1e-9:
        gx = PLOT_X0 + v * scale
        if limit is None or abs(v - limit[0]) > 1e-9:
            o.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="%s" stroke-width="%s"/>' % (
                gx, PLOT_Y0, gx, PLOT_Y1, AXIS if v == 0 else GRID, 2 if v == 0 else 1.5))
        o.append(text(gx, 802, "%g ms" % v, 22, fill=MUTED, anchor="middle"))
        v += axis_step
    if limit is not None:
        lx = PLOT_X0 + limit[0] * scale
        o.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="%s" stroke-width="2" stroke-dasharray="8 7"/>' % (
            lx, PLOT_Y0 - 2, lx, PLOT_Y1, INK2))
        o.append(text(lx + 12, PLOT_Y0 + 18, limit[1], 22, fill=INK2))
    # groups
    n = len(groups)
    series = len(legend)
    block = series * BAR_H + (series - 1) * BAR_GAP
    for i, (label, sublabel, values, gain) in enumerate(groups):
        centre = PLOT_Y0 + (PLOT_Y1 - PLOT_Y0) * (i + 0.5) / n + (16 if limit is not None else 0)
        o.append(text(LEFT, centre - 4, label, 32, weight=700))
        o.append(text(LEFT, centre + 30, sublabel, 22, fill=INK2))
        top = centre - block / 2
        for k, value in enumerate(values):
            y = top + k * (BAR_H + BAR_GAP)
            width = value * scale
            o.append(bar(PLOT_X0, y, width, legend[k][0]))
            o.append(text(PLOT_X0 + width + 16, y + BAR_H / 2 + 9, "%.1f ms" % value, 26, weight=700))
        o.append(text(RIGHT, centre + 11, gain, 32, weight=700, anchor="end"))
    o.append(text(LEFT, 858, footnote, 22, fill=MUTED))
    o.append("</svg>")
    os.makedirs(OUT, exist_ok=True)
    svg = os.path.join(OUT, name + ".svg")
    with open(svg, "w", encoding="utf-8") as f:
        f.write("\n".join(o))
    render(svg, os.path.join(OUT, name + ".png"))


def render(svg, png):
    browser = next((b for b in BROWSERS if os.path.exists(b)), None)
    if browser is None:
        print("no Edge or Chrome found: only the SVG was written for", svg)
        return
    if os.path.exists(png):
        os.remove(png)
    subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--window-size=%d,%d" % (W, H), "--default-background-color=1a1a19ff",
                    "--screenshot=" + png, "file:///" + svg.replace("\\", "/")],
                   capture_output=True, timeout=120)
    print("written:" if os.path.exists(png) else "NOT rendered:", png)


def percent_shorter(off, on):
    return "%.0f%% shorter" % (100.0 * (off - on) / off)


def times_shorter(off, on):
    return "%.1f\u00d7 shorter" % (off / on)


def main():
    layouts = [("Mixed crowd", "scattered + 2 chunks of 100", result("v2_mixed")),
               ("Packed", "125 per chunk", result("v2_dense")),
               ("Scattered", "500 around each player", result("v2_spread"))]
    whole_mod = [(BLUE, "Cobblemon 1.8.4 alone"), (ORANGE, "With Cobblemon Faster AI")]
    same_run = "Mod switched off and on every 100 ticks in the same run: same world, same Pokémon. Lithium installed on both sides."

    chart("tick-average", "Average server tick with 3000 wild Pokémon",
          "Time the server needs for one tick, three ways of spreading the Pokémon. Lower is better.",
          whole_mod,
          [(label, sub, [r["off"]["tickTime"]["meanMs"], r["on"]["tickTime"]["meanMs"]],
            percent_shorter(r["off"]["tickTime"]["meanMs"], r["on"]["tickTime"]["meanMs"])) for label, sub, r in layouts],
          60, 10, same_run)

    chart("tick-spikes", "Lag spikes with 3000 wild Pokémon",
          "The slowest 1% of server ticks (99th percentile). Lower is better.",
          whole_mod,
          [(label, sub, [r["off"]["tickTime"]["p99Ms"], r["on"]["tickTime"]["p99Ms"]],
            times_shorter(r["off"]["tickTime"]["p99Ms"], r["on"]["tickTime"]["p99Ms"])) for label, sub, r in layouts],
          250, 50, same_run, limit=(50, "50 ms: above it the server drops below 20 TPS"))

    slow = [("Beyond 48 blocks", "1 tick in 2", result("v2_slow48")),
            ("Beyond 32 blocks", "1 tick in 4", result("v2_slow32"))]
    chart("slow-motion", "Optional far slow motion: average server tick",
          "3000 wild Pokémon; those far from every player get 1 tick in N. Off by default. Lower is better.",
          [(ORANGE, "Cobblemon Faster AI"), (AQUA, "Faster AI + far slow motion")],
          [(label, sub, [r["off"]["tickTime"]["meanMs"], r["on"]["tickTime"]["meanMs"]],
            percent_shorter(r["off"]["tickTime"]["meanMs"], r["on"]["tickTime"]["meanMs"])) for label, sub, r in slow],
          40, 10, "Slow motion switched off and on every 100 ticks in the same run. Owned, battling, hurt or nearby Pokémon are never slowed.")


if __name__ == "__main__":
    main()
