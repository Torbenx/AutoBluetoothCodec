"""Draws the app icon concepts as SVGs on the 108dp adaptive icon grid and builds a preview sheet.

Run: python3 design/icon-concepts/generate.py, then render sheet.html, e.g. with render.js.
"""
import math
from pathlib import Path

OUT = Path(__file__).parent

COLOR = dict(
    bg="#D6E3FF",      # the theme's primaryContainer
    ink="#274777",     # onPrimaryContainer
    wave="#2F6BDB",    # Codec's collar
    accent="#E58E1A",  # the digital part
    light="#F9F9FF",   # a phone screen
    fur="#E0AC6E", fur_shade="#C68E4F", snout="#F2D6AE", eye="#33291F",
)


def mono(fg, bg):
    """A themed icon has one color and alpha, cutouts show the background."""
    mid = "#" + "".join(f"{(int(fg[i:i + 2], 16) + int(bg[i:i + 2], 16)) // 2:02X}" for i in (1, 3, 5))
    return dict(bg=bg, ink=fg, wave=fg, accent=fg, light=bg, fur=fg, fur_shade=fg, snout=mid, eye=bg)


MONO_LIGHT = mono("#274777", "#D6E3FF")
MONO_DARK = mono("#D6E3FF", "#1B2B45")


def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def poly(points):
    return "M" + " L".join(f"{fmt(x)} {fmt(y)}" for x, y in points)


def sine(x0, x1, y, amp, cycles, phase=0.0, n=48):
    return poly([(x0 + (x1 - x0) * i / n, y - amp * math.sin(phase + 2 * math.pi * cycles * i / n)) for i in range(n + 1)])


def stroke(d, color, width, opacity=1.0):
    return (f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{fmt(width)}" stroke-linecap="round" '
            f'stroke-linejoin="round" opacity="{fmt(opacity)}"/>')


def rect(x, y, w, h, r, color, opacity=1.0):
    return f'<rect x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" rx="{fmt(r)}" fill="{color}" opacity="{fmt(opacity)}"/>'


def bars(p, xs, halves, y, width):
    fade = [1 - 0.6 * i / max(1, len(xs) - 1) for i in range(len(xs))]
    return "".join(stroke(f"M{fmt(x)} {fmt(y - h)} V{fmt(y + h)}", p["accent"], width, o) for x, h, o in zip(xs, halves, fade))


def is_mono(p):
    return p["fur"] == p["ink"]


def headphones(p, cx, cy, r, cup_w, cup_h, band, color, gap=0.0):
    """A band of radius r centered on (cx, cy) and two cups hanging from its ends, cut out from what's behind by gap."""
    d = f"M{fmt(cx - r)} {fmt(cy + 2)} V{fmt(cy)} A{fmt(r)} {fmt(r)} 0 0 1 {fmt(cx + r)} {fmt(cy)} V{fmt(cy + 2)}"
    out = ""
    for c, grow in ((p["bg"], gap), (color, 0)) if gap else ((color, 0),):
        out += stroke(d, c, band + 2 * grow)
        for x in (cx - r, cx + r):
            out += rect(x - cup_w / 2 - grow, cy - grow, cup_w + 2 * grow, cup_h + 2 * grow, cup_w / 2.4 + grow, c)
    return out


def phone(p, x, y, w, h):
    return rect(x, y, w, h, w * 0.22, p["ink"]) + rect(x + w * 0.16, y + h * 0.1, w * 0.68, h * 0.74, w * 0.08, p["light"])


# The concepts. Each draws the foreground in a 108 x 108 viewport, the key content within the 66dp safe circle.

def wave_to_bars(p):
    smooth = stroke(sine(23, 51, 54, 12, 1.5), p["wave"], 5)
    return smooth + bars(p, [57, 63.5, 70, 76.5, 83], [11, 7, 12.5, 5, 8], 54, 4.5)


def phone_and_headphones(p):
    link = stroke(sine(43, 57, 56, 3.5, 1.5), p["wave"], 3.5)
    return phone(p, 24, 39, 16, 30) + link + headphones(p, 72, 50, 11, 7, 14, 4, p["ink"])


def arc_phone_to_headphones(p):
    p0, p1, p2 = (33, 50), (52.5, 16), (72, 50)

    def at(t):
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        dx = 2 * (1 - t) * (p1[0] - p0[0]) + 2 * t * (p2[0] - p1[0])
        dy = 2 * (1 - t) * (p1[1] - p0[1]) + 2 * t * (p2[1] - p1[1])
        length = math.hypot(dx, dy)
        return x, y, -dy / length, dx / length

    def offset(t):
        return 3.5 * math.sin(2 * math.pi * 1.5 * (t - 0.08) / 0.42)

    points = []
    for i in range(41):
        t = 0.08 + 0.42 * i / 40
        x, y, nx, ny = at(t)
        points.append((x + nx * offset(t), y + ny * offset(t)))
    smooth = stroke(poly(points), p["wave"], 4)
    squares = ""
    for i, t in enumerate([0.6, 0.72, 0.84]):
        x, y, nx, ny = at(t)
        o = 0
        size = 6 - 0.7 * i
        squares += rect(x + nx * o - size / 2, y + ny * o - size / 2, size, size, 1.2, p["accent"], 1 - 0.15 * i)
    return phone(p, 26, 52, 14, 25) + smooth + squares + headphones(p, 72, 61, 9, 6, 12, 3.5, p["ink"])


def headphones_around_wave(p):
    smooth = stroke(sine(39, 55, 60, 7, 1.0), p["wave"], 4)
    return headphones(p, 54, 50, 24, 11, 22, 5, p["ink"]) + smooth + bars(p, [60, 65, 70], [6, 3.5, 5], 60, 3.5)


def dog_head(p, ears=True, mouth=True):
    out = ""
    if ears:
        for s in (-1, 1):
            out += (f'<path d="M{fmt(54 + s * 14)} 40 C{fmt(54 + s * 26)} 38 {fmt(54 + s * 27)} 56 {fmt(54 + s * 24)} 66 '
                    f'C{fmt(54 + s * 22)} 72 {fmt(54 + s * 15)} 68 {fmt(54 + s * 15)} 60 Z" fill="{p["fur_shade"]}"/>')
    ring = f' stroke="{p["bg"]}" stroke-width="2.5"' if is_mono(p) else ""
    out += f'<ellipse cx="54" cy="55" rx="18" ry="18.5" fill="{p["fur"]}"{ring}/>'
    out += f'<ellipse cx="54" cy="64.5" rx="10.5" ry="8" fill="{p["snout"]}"/>'
    out += f'<ellipse cx="54" cy="60.5" rx="4" ry="3" fill="{p["eye"] if p["eye"] != p["bg"] else p["ink"]}"/>'
    out += f'<circle cx="46.5" cy="51" r="2.3" fill="{p["eye"]}"/><circle cx="61.5" cy="51" r="2.3" fill="{p["eye"]}"/>'
    if mouth:
        out += stroke("M50.5 66.5 Q54 69.5 57.5 66.5", p["eye"] if p["eye"] != p["bg"] else p["ink"], 1.6)
    return out


def codec_with_headphones(p):
    return dog_head(p, ears=False) + headphones(p, 54, 50, 21, 9, 17, 4.5, p["wave"], gap=1.5 if is_mono(p) else 0)


def codec_fetches_wave(p):
    smooth = stroke(sine(22, 55, 67, 3.5, 1.5), p["wave"], 4.5)
    return dog_head(p, mouth=False) + smooth + bars(p, [60, 65.5, 71, 76.5, 82], [5, 3, 5.5, 2.5, 4], 67, 4)


CONCEPTS = [
    ("1c", "Wave becomes sound bars", "A smooth wave that turns into fading bars and is sent off.", wave_to_bars),
    ("2a", "Phone and headphones", "The phone, a short wave link and the headphones. Shows how little room two devices get.", phone_and_headphones),
    ("C1", "Wave arcs from phone to headphones",
     "Combined: the wave leaves the phone smooth and arrives at the headphones digitized.", arc_phone_to_headphones),
    ("C2", "Headphones around a wave", "Combined without the phone: smooth audio is digitized between the ear cups.", headphones_around_wave),
    ("D1", "Codec wears headphones", "The mascot, the headphones in its collar blue.", codec_with_headphones),
    ("D2", "Codec fetches the wave", "The mascot carries the wave like a stick, smooth on one side, digital on the other.", codec_fetches_wave),
]


def svg(draw, p, view="0 0 108 108", extra=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}"><rect width="108" height="108" fill="{p["bg"]}"/>'
            f'{draw(p)}{extra}</svg>')


GUIDES = ('<rect x="18" y="18" width="72" height="72" fill="none" stroke="#C0392B" stroke-width="0.6" stroke-dasharray="2 2"/>'
          '<circle cx="54" cy="54" r="33" fill="none" stroke="#C0392B" stroke-width="0.6" stroke-dasharray="2 2"/>')

VISIBLE = "18 18 72 72"


def sheet():
    rows = ""
    for key, name, note, draw in CONCEPTS:
        (OUT / f"{key}.svg").write_text(svg(draw, COLOR))
        color = svg(draw, COLOR, VISIBLE)
        rows += f"""
<section>
  <h2><span>{key}</span> {name}</h2>
  <p>{note}</p>
  <div class="tiles">
    <figure><div class="tile grid">{svg(draw, COLOR, extra=GUIDES)}</div><figcaption>108dp canvas, safe zone</figcaption></figure>
    <figure><div class="tile circle">{color}</div><figcaption>Circle</figcaption></figure>
    <figure><div class="tile squircle">{color}</div><figcaption>Squircle</figcaption></figure>
    <figure><div class="tile rounded">{color}</div><figcaption>Rounded square</figcaption></figure>
    <figure class="small"><div class="row">
      <div class="tile circle s48">{color}</div><div class="tile squircle s48">{color}</div>
    </div><figcaption>48dp, launcher size</figcaption></figure>
    <figure><div class="tile circle">{svg(draw, MONO_LIGHT, VISIBLE)}</div><figcaption>Themed, light</figcaption></figure>
    <figure><div class="tile circle">{svg(draw, MONO_DARK, VISIBLE)}</div><figcaption>Themed, dark</figcaption></figure>
  </div>
</section>"""
    (OUT / "sheet.html").write_text(f"""<!doctype html>
<html><head><meta charset="utf-8"><title>Codec Companion icon concepts</title>
<style>
  body {{ margin: 0; padding: 28px 32px; background: #F1F2F8; color: #191C20; font: 14px/1.4 system-ui, sans-serif; width: 1180px; }}
  h1 {{ font-size: 22px; margin: 0 0 4px; }}
  h1 + p {{ margin: 0 0 20px; color: #44474E; }}
  section {{ background: #fff; border-radius: 16px; padding: 16px 20px 12px; margin-bottom: 14px; }}
  h2 {{ font-size: 16px; margin: 0; }}
  h2 span {{ display: inline-block; min-width: 30px; color: #405F90; }}
  section > p {{ margin: 2px 0 12px 30px; color: #44474E; }}
  .tiles {{ display: flex; gap: 22px; align-items: flex-end; }}
  figure {{ margin: 0; display: flex; flex-direction: column; align-items: center; gap: 6px; }}
  figcaption {{ font-size: 12px; color: #74777F; }}
  .tile {{ width: 120px; height: 120px; overflow: hidden; }}
  .tile svg {{ display: block; width: 100%; height: 100%; }}
  .grid {{ width: 132px; height: 132px; border-radius: 4px; }}
  .circle {{ border-radius: 50%; }}
  .squircle {{ border-radius: 34%; }}
  .rounded {{ border-radius: 18%; }}
  .row {{ display: flex; gap: 10px; height: 120px; align-items: center; }}
  .s48 {{ width: 48px; height: 48px; }}
</style></head>
<body>
<h1>Codec Companion: app icon concepts</h1>
<p>Rough concepts on the 108dp adaptive icon grid. The dashed square is the 72dp area a launcher shows, the circle the 66dp safe zone.</p>
{rows}
</body></html>""")


if __name__ == "__main__":
    sheet()
