#!/usr/bin/env python3
"""Generate docs/assets/banner.png at 1280x640 for the VictorSMS README.

Layout follows the Victorious fleet README standard:
solid #0B0F1A field, app icon on the left inside a #00E5FF → #FF2BD6
gradient ring, Montserrat 800 title, cyan tagline, fleet footer, and
the hooded-V mark at the bottom right.

Run from the repository root:
    python3 scripts/generate_readme_banner.py
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "assets" / "banner.png"
HOODED = ROOT / "docs" / "assets" / "hooded-v.png"
# Higher-resolution official mark (wordmark cropped out). Fallback: repo logo.
ICON_CANDIDATES = [
    Path("/workspace/fleet-logos/victorsms-logo-square.png"),
    ROOT / "assets" / "logo.png",
]

W, H = 1280, 640
BG = (0x0B, 0x0F, 0x1A)
CYAN = (0x00, 0xE5, 0xFF)
MAGENTA = (0xFF, 0x2B, 0xD6)
WHITE = (0xFF, 0xFF, 0xFF)
FOOTER = (0xE6, 0xE6, 0xEF)

MONTSERRAT = "/usr/share/fonts/truetype/sand-box/google/Montserrat/Montserrat-VariableFont_wght.ttf"
INTER = "/usr/share/fonts/truetype/sand-box/google/Inter/Inter-VariableFont_opsz,wght.ttf"


def font_montserrat(size: int, weight: int) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(MONTSERRAT, size)
    f.set_variation_by_axes([weight])
    return f


def font_inter(size: int, weight: int) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(INTER, size)
    # Inter axes: optical size (14–32), weight (100–900)
    f.set_variation_by_axes([min(32, max(14, size)), weight])
    return f


def load_icon() -> Image.Image:
    src = next(p for p in ICON_CANDIDATES if p.is_file())
    im = Image.open(src).convert("RGB")
    # Crop the circular neon icon; drop the wordmark underneath.
    # Measured on the 720px official square mark (scaled if needed).
    if im.size == (720, 720):
        # Icon rows 34–566, cols 74–637; wordmark starts at row 569.
        box = (74, 32, 638, 566)
    elif im.size == (256, 256):
        box = (27, 12, 226, 201)
    else:
        w, h = im.size
        box = (int(w * 0.10), int(h * 0.04), int(w * 0.89), int(h * 0.79))
    crop = im.crop(box)
    side = max(crop.size)
    square = Image.new("RGB", (side, side), BG)
    ox = (side - crop.size[0]) // 2
    oy = (side - crop.size[1]) // 2
    square.paste(crop, (ox, oy))
    return square


def circularize(im: Image.Image, diameter: int) -> Image.Image:
    im = im.resize((diameter, diameter), Image.Resampling.LANCZOS)
    rgba = im.convert("RGBA")
    mask = Image.new("L", (diameter, diameter), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, diameter - 1, diameter - 1), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(0.6))
    out = Image.new("RGBA", (diameter, diameter), (0, 0, 0, 0))
    out.paste(rgba, (0, 0))
    out.putalpha(mask)
    return out


def gradient_ring(outer_d: int, thickness: int, scale: int = 3) -> Image.Image:
    """Horizontal cyan→magenta ring, drawn at `scale` and downsampled."""
    d = outer_d * scale
    t = thickness * scale
    cx = cy = d / 2
    r_out = d / 2 - 0.5
    r_in = r_out - t
    feather = scale
    px = Image.new("RGBA", (d, d), (0, 0, 0, 0))
    pix = px.load()
    for y in range(d):
        for x in range(d):
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r > r_out + feather or r < r_in - feather:
                continue
            if r > r_out:
                a = 1.0 - (r - r_out) / feather
            elif r < r_in:
                a = 1.0 - (r_in - r) / feather
            else:
                a = 1.0
            k = (x + 0.5) / d
            k = max(0.0, min(1.0, k))
            color = tuple(int(CYAN[i] + (MAGENTA[i] - CYAN[i]) * k) for i in range(3))
            pix[x, y] = color + (max(0, min(255, int(round(a * 255)))),)
    return px.resize((outer_d, outer_d), Image.Resampling.LANCZOS)


def punch_dark(im: Image.Image, threshold: int = 28) -> Image.Image:
    """Make near-black pixels of the hooded-V transparent so it sits on #0B0F1A."""
    rgba = im.convert("RGBA")
    pix = rgba.load()
    w, h = rgba.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = pix[x, y]
            if r < threshold and g < threshold and b < threshold + 8:
                pix[x, y] = (r, g, b, 0)
    return rgba


def main() -> None:
    canvas = Image.new("RGB", (W, H), BG).convert("RGBA")

    outer_d = 420
    thickness = 16
    gap = 14
    icon_d = outer_d - 2 * thickness - 2 * gap
    cx, cy = 80 + outer_d // 2, H // 2

    ring = gradient_ring(outer_d, thickness)
    icon = circularize(load_icon(), icon_d)

    ring_box = (cx - outer_d // 2, cy - outer_d // 2)
    icon_box = (cx - icon_d // 2, cy - icon_d // 2)
    canvas.alpha_composite(ring, ring_box)
    canvas.alpha_composite(icon, icon_box)

    d = ImageDraw.Draw(canvas)
    x0 = 560

    title_font = font_montserrat(82, 800)
    d.text((x0, 188), "VictorSMS", font=title_font, fill=WHITE)

    tag_font = font_inter(28, 550)
    tagline = "Matrix + native SMS and phone messaging for the Victorious fleet"
    max_tag_w = W - x0 - 56
    tw = d.textlength(tagline, font=tag_font)
    if tw > max_tag_w:
        for size in range(27, 19, -1):
            tag_font = font_inter(size, 550)
            tw = d.textlength(tagline, font=tag_font)
            if tw <= max_tag_w:
                break
    d.text((x0, 300), tagline, font=tag_font, fill=CYAN)

    rule_y = 354
    d.line((x0, rule_y, x0 + min(620, int(tw) + 8), rule_y), fill=(0x2A, 0x3A, 0x4A), width=2)

    footer_font = font_inter(24, 500)
    d.text((x0, 548), "Part of the Victorious Framework fleet", font=footer_font, fill=FOOTER)

    mark = punch_dark(Image.open(HOODED)).resize((64, 64), Image.Resampling.LANCZOS)
    canvas.alpha_composite(mark, (W - 48 - 64, H - 48 - 64))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(OUT, "PNG", optimize=True)
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
