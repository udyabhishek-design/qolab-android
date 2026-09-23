#!/usr/bin/env python3
"""
Height-budget validation for the compact Qolab Login layout (v1.4.1).
Replicates Android text metrics closely enough to verify the
keyboard-closed height budget at representative device classes.
"""
import sys

# Roboto metrics: line height ≈ 1.1765 * sp (ascent+descent), plus
# lineSpacingMultiplier where set. Include minimum touch rounding.
def line_h(sp, mult=1.0):
    return sp * 1.1765 * mult

def validate(name, vh, vw):
    # default tier applies for vh < 860
    if vh >= 860:
        top_pad, headline, field = 20, 32, 56
    else:
        top_pad, headline, field = 12, 28, 54

    yellow_lines = 4 * line_h(15, 1.12)
    yellow_pad = 12 + 12            # top+bottom padding
    yellow_accent = 8 + 3           # margin + line
    yellow = yellow_lines + yellow_pad + yellow_accent

    # Subcopy beside card at this width (3 lines max; compute lines)
    # 14sp ≈ 7.3px/char average; available = vw - 48 (screen pads) - 12 (gap) - card_w
    card_w = 14 + 16 + 5 * 7.6      # pads + widest word "Rooms"/"Ideas" (5ch bold 15sp ≈ 38) → 14+16+38=68 → use measured ≈ 90
    card_w = 90
    sub_avail = (vw - 48 - 12 - card_w) * 1.0
    sub_len = len("Your AI-powered workspace for a more connected team.")
    sub_w = sub_len * 7.3
    sub_lines = min(3, max(2, int(sub_w / sub_avail) + 1))
    sub = sub_lines * line_h(14, 1.25)

    headline_row = max(2 * line_h(headline, 1.04) + 2, yellow + 0) if False else 2 * line_h(headline, 1.04) + 2
    # subcopy row = max(sub, yellow)
    sub_row = max(sub, yellow)

    brand = line_h(21)
    total = (
        top_pad + brand + 14 + headline_row + 10 + sub_row
        + 18                       # form marginTop
        + line_h(13) + 5 + field   # email label + gap + field
        + 12 + line_h(13) + 5 + field  # password
        + 40                       # forgot
        + 10 + field               # CTA margin + button
        + 16                       # scroll bottom pad
    )
    ok = total <= vh
    print(f"{name:14s} {vw}x{vh}: budget={total:6.1f}dp  viewport={vh}dp  "
          f"{'FITS (no scroll)' if ok else 'NEEDS SCROLL'}  margin={vh-total:+.1f}dp")
    return ok

cases = [
    ("Moto G32", 780, 360),
    ("small 360x800", 800, 360),
    ("Pixel 393x873", 873, 393),
    ("412x915", 915, 412),
]
sys.exit(0 if all(validate(n, vh, vw) for n, vh, vw in cases) else 1)
