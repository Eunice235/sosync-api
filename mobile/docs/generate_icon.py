"""Generates the app launcher icon into assets/icon/.

    python docs/generate_icon.py        (run from the mobile/ directory; needs Pillow)

The mark is the same one the app shows on its splash screen and on the SOS button: the
Material `sos_rounded` glyph in white, on a red gradient running top-left to bottom-right from
AppTheme.danger to AppTheme.dangerDeep. It is rendered from the Material Icons font that ships
with the Flutter SDK rather than redrawn, so the icon on the home screen and the button inside
the app are the same shape, not two approximations of it.

Three files come out, because Android draws launcher icons two different ways:

  icon.png             Legacy icon (Android 7 and older, and the web favicon): the full red
                       circle with the glyph, transparent outside the circle.
  icon_background.png  Adaptive icon back layer (Android 8+): full-bleed red gradient. The
                       launcher masks it to whatever shape the phone uses - circle, squircle,
                       teardrop - so it must fill the whole square.
  icon_foreground.png  Adaptive icon front layer: the white glyph alone, sized to stay inside
                       the safe zone every launcher mask is guaranteed to show. It doubles as
                       the monochrome layer for Android 13 themed icons.

Everything is drawn at 2x and downsampled, so the glyph edges are smooth at every density.
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

SIZE = 1024
SUPERSAMPLE = 2

# AppTheme.danger and AppTheme.dangerDeep, so the icon and the app are one colour.
DANGER = (0xE0, 0x31, 0x31)
DANGER_DEEP = (0x8B, 0x0F, 0x16)
WHITE = (255, 255, 255, 255)

# Icons.sos_rounded. Confirmed against the font's own `codepoints` file, not guessed.
SOS_GLYPH = chr(0xF081F)

OUT_DIR = os.path.join("assets", "icon")


def find_material_font() -> str:
    candidates = []
    for root in filter(None, [os.environ.get("FLUTTER_ROOT"), r"C:\flutter", "/usr/local/flutter"]):
        candidates.append(
            os.path.join(root, "bin", "cache", "artifacts", "material_fonts",
                         "materialicons-regular.otf")
        )
    for path in candidates:
        if os.path.exists(path):
            return path
    sys.exit(
        "Material Icons font not found. Set FLUTTER_ROOT to your Flutter SDK directory."
    )


def diagonal_gradient(size: int) -> Image.Image:
    """Top-left to bottom-right, matching LinearGradient(topLeft -> bottomRight) in the app."""
    ramp = Image.linear_gradient("L").resize((size, size))
    # linear_gradient runs top to bottom; rotating 45 degrees and cropping turns it diagonal.
    diag = ramp.rotate(45, expand=True, resample=Image.BICUBIC)
    left = (diag.width - size) // 2
    top = (diag.height - size) // 2
    mask = diag.crop((left, top, left + size, top + size))
    return Image.composite(
        Image.new("RGB", (size, size), DANGER_DEEP),
        Image.new("RGB", (size, size), DANGER),
        mask,
    ).convert("RGBA")


def glyph_layer(size: int, ink_width_fraction: float, font_path: str) -> Image.Image:
    """The white SOS glyph, centred on the pixels it actually paints.

    Not on the font's reported bounding box: this icon font's metrics overstate the glyph's
    vertical extent, and centring on them left the mark sitting visibly high - about 9% of
    the icon above centre. So the glyph is drawn once onto a generous scratch canvas, cropped
    to the pixels that are genuinely non-transparent, scaled to the target width, and only
    then placed. That centres it optically whatever the font claims about itself.
    """
    font = ImageFont.truetype(font_path, size)
    scratch = Image.new("RGBA", (size * 3, size * 3), (0, 0, 0, 0))
    ImageDraw.Draw(scratch).text((size, size), SOS_GLYPH, font=font, fill=WHITE)

    ink_box = scratch.getchannel("A").getbbox()
    if ink_box is None:
        sys.exit("The SOS glyph rendered as nothing - wrong font or codepoint.")
    ink = scratch.crop(ink_box)

    target_w = int(size * ink_width_fraction)
    target_h = round(ink.height * target_w / ink.width)
    ink = ink.resize((target_w, target_h), Image.LANCZOS)

    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.paste(ink, ((size - target_w) // 2, (size - target_h) // 2), ink)
    return layer


def downsample(image: Image.Image) -> Image.Image:
    return image.resize((SIZE, SIZE), Image.LANCZOS)


def main() -> None:
    font_path = find_material_font()
    os.makedirs(OUT_DIR, exist_ok=True)
    big = SIZE * SUPERSAMPLE

    # ── Legacy: red circle, white glyph, transparent corners ────────────────────────
    circle_mask = Image.new("L", (big, big), 0)
    # A hair of inset so antialiased edges are not clipped by the canvas.
    inset = big // 64
    ImageDraw.Draw(circle_mask).ellipse((inset, inset, big - inset, big - inset), fill=255)
    legacy = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    legacy.paste(diagonal_gradient(big), (0, 0), circle_mask)
    # Glyph at 58% of the circle: large enough to read at 48px, with room to breathe.
    legacy = Image.alpha_composite(legacy, glyph_layer(big, 0.58, font_path))
    downsample(legacy).save(os.path.join(OUT_DIR, "icon.png"))

    # ── Adaptive background: full-bleed gradient ─────────────────────────────────────
    downsample(diagonal_gradient(big)).save(os.path.join(OUT_DIR, "icon_background.png"))

    # ── Adaptive foreground: glyph inside the safe zone ─────────────────────────────
    # Launchers show the centre 72/108 of the layer (66.7%) and only guarantee a 66/108
    # circle. At 39% of the layer the glyph is 0.39 / 0.667 = 58% of the visible icon - the
    # same proportion as the legacy icon - and its corners sit at a radius of 0.21 against a
    # safe radius of 0.31, so nothing clips on any mask shape.
    #
    # This relies on adaptive_icon_foreground_inset: 0 in pubspec.yaml. The tool's default
    # 16% inset would shrink this a second time, and the SOS would render noticeably smaller
    # on Android 8+ than anywhere else. Size is decided here, once.
    downsample(glyph_layer(big, 0.39, font_path)).save(
        os.path.join(OUT_DIR, "icon_foreground.png")
    )

    for name in ("icon.png", "icon_background.png", "icon_foreground.png"):
        path = os.path.join(OUT_DIR, name)
        print(f"{path}: {os.path.getsize(path):,} bytes")


if __name__ == "__main__":
    main()
