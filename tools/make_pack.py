#!/usr/bin/env python3
"""
Generates the GodGear resource pack (pixel-art icons, armour textures, item/equipment definitions)
and zips it deterministically to GodGearPack.zip. Edit the drawing code below to restyle the items.
Usage: python3 make_pack.py [output_dir]      (needs only Python 3, no extra libraries)
"""
import hashlib, json, os, struct, sys, zipfile, zlib

OUT = sys.argv[1] if len(sys.argv) > 1 else "."
PACK_DIR = os.path.join(OUT, "resourcepack")

PAL = {
    "O": (20, 12, 36, 255),    # outline
    "D": (59, 26, 107, 255),   # dark purple
    "M": (109, 52, 201, 255),  # mid purple
    "L": (181, 140, 255, 255), # light purple
    "G": (255, 210, 63, 255),  # gold
    "g": (184, 134, 11, 255),  # dark gold
    "W": (255, 255, 255, 255), # white
    "C": (92, 243, 255, 255),  # cyan glow
    "W1": (255, 255, 255, 255), # convergence 1 - white
    "W2": (70, 130, 255, 255),  # convergence 2 - blue
    "W3": (140, 0, 210, 255),   # convergence 3 - violet
    "T":  (255, 240, 200, 255), # trophy shine
    "P":  (255, 120, 180, 255), # ambrosia - pink flesh
    "Rn": (200, 30, 60, 255),   # ambrosia - red rind
    "Sd": (120, 200, 60, 255),  # ambrosia - stem/leaf
}

# ---------------------------------------------------------------- tiny PNG writer
def write_png(path, w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(px[(x, y)])
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)

# ---------------------------------------------------------------- 16x16 canvas
class Canvas:
    def __init__(self):
        self.p = {}
    def put(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            self.p[(x, y)] = c
    def fill(self, x0, x1, y0, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c)
    def clear(self, x0, x1, y0, y1):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.p.pop((x, y), None)
    def outline(self):
        add = {}
        for (x, y) in self.p:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    n = (x + dx, y + dy)
                    if n not in self.p and 0 <= n[0] < 16 and 0 <= n[1] < 16:
                        add[n] = "O"
        self.p.update(add)
    def save(self, path):
        px = {}
        for y in range(16):
            for x in range(16):
                c = self.p.get((x, y))
                px[(x, y)] = PAL[c] if c else (0, 0, 0, 0)
        write_png(path, 16, 16, px)
        return px

def sword():
    c = Canvas()
    for j in range(9):
        c.put(5 + j, 10 - j, "L")
        if j >= 1:
            c.put(5 + j, 11 - j, "M")
    c.put(14, 1, "W"); c.put(13, 2, "W")
    for k in range(5):
        c.put(3 + k, 8 + k, "G")
    c.put(5, 10, "C")
    for (x, y) in [(4, 11), (3, 12), (2, 13)]:
        c.put(x, y, "g")
    c.put(1, 14, "G")
    c.outline(); return c

def axe():
    c = Canvas()
    for j in range(8):
        c.put(3 + j, 14 - j, "g")
    # slanted axe blade: wide cutting edge on the right/bottom, straight back on the left
    rows = {2: (9, 11), 3: (9, 12), 4: (9, 13), 5: (9, 14), 6: (10, 14), 7: (12, 14)}
    for y, (a, b) in rows.items():
        for x in range(a, b + 1):
            c.put(x, y, "M")
        c.put(b, y, "L")
    for x in range(10, 15):
        c.put(x, 7, "L") if x >= 12 else None
    c.put(9, 2, "G"); c.put(9, 3, "G"); c.put(9, 4, "G")   # gold spine
    c.put(14, 6, "W"); c.put(13, 5, "W"); c.put(11, 4, "D"); c.put(11, 5, "D")
    c.outline(); return c

def mace():
    c = Canvas()
    for j in range(8):
        c.put(2 + j, 14 - j, "g")
    for y in range(16):
        for x in range(16):
            dx, dy = x - 11, y - 5
            if dx * dx + dy * dy <= 9:
                c.put(x, y, "L" if dx + dy < -1 else ("D" if dx + dy > 2 else "M"))
    for (x, y) in [(11, 1), (15, 5), (7, 5), (14, 2), (8, 2), (14, 8), (8, 8)]:
        c.put(x, y, "G")
    c.put(11, 5, "C"); c.put(10, 4, "W")
    c.outline(); return c

def spear():
    c = Canvas()
    for j in range(10):
        c.put(2 + j, 14 - j, "g")
    for (x, y) in [(12, 4), (13, 3), (14, 2)]:
        c.put(x, y, "L")
        c.put(x - 1, y, "M"); c.put(x, y + 1, "M")
    c.put(15, 1, "W"); c.put(14, 1, "L")
    c.put(11, 5, "G"); c.put(10, 6, "G")
    c.outline(); return c

def helmet():
    c = Canvas()
    c.fill(7, 8, 1, 1, "G")
    c.fill(5, 10, 2, 2, "M")
    c.fill(4, 11, 3, 3, "M")
    c.fill(3, 12, 4, 10, "M")
    c.fill(3, 12, 6, 6, "G")
    c.fill(5, 10, 7, 8, "D")
    c.fill(5, 6, 7, 7, "C"); c.fill(9, 10, 7, 7, "C")
    c.fill(3, 4, 10, 11, "D"); c.fill(11, 12, 10, 11, "D")
    c.fill(5, 6, 3, 3, "L"); c.fill(3, 3, 4, 5, "L")
    c.outline(); return c

def chestplate():
    c = Canvas()
    c.fill(4, 11, 3, 13, "M")
    c.fill(2, 5, 3, 6, "M"); c.fill(10, 13, 3, 6, "M")
    c.fill(2, 4, 7, 10, "D"); c.fill(11, 13, 7, 10, "D")
    c.clear(6, 9, 3, 4)
    c.fill(7, 8, 5, 12, "G")
    c.fill(4, 11, 12, 12, "g")
    c.fill(7, 8, 8, 8, "C")
    c.fill(4, 4, 3, 6, "L"); c.fill(2, 2, 3, 6, "L")
    c.outline(); return c

def leggings():
    c = Canvas()
    c.fill(4, 11, 2, 5, "M")
    c.fill(4, 7, 6, 13, "M"); c.fill(8, 11, 6, 13, "M")
    c.clear(7, 8, 8, 13)
    c.fill(4, 11, 2, 2, "G")
    c.fill(7, 8, 3, 3, "C")
    c.fill(4, 6, 13, 13, "G"); c.fill(9, 11, 13, 13, "G")
    c.fill(4, 4, 6, 12, "L"); c.fill(9, 9, 6, 12, "L")
    c.outline(); return c

def boots():
    c = Canvas()
    c.fill(2, 6, 8, 12, "M"); c.fill(9, 13, 8, 12, "M")
    c.fill(2, 7, 12, 13, "D"); c.fill(9, 14, 12, 13, "D")
    c.fill(2, 6, 8, 8, "G"); c.fill(9, 13, 8, 8, "G")
    c.fill(2, 2, 9, 11, "L"); c.fill(9, 9, 9, 11, "L")
    c.put(4, 10, "C"); c.put(11, 10, "C")
    c.outline(); return c

def convergence(level):
    color = {1: "W1", 2: "W2", 3: "W3"}[level]
    def draw():
        c = Canvas()
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                d = (dx * dx + dy * dy) ** 0.5
                if d <= 5.5:
                    c.put(x, y, color if d <= 2.5 else ("W" if d <= 3.2 else "D"))
        for k in range(level):
            ang = k * 2
            c.put(7 + ang - level, 2, "G")
            c.put(7 - ang + level, 13, "G")
        c.put(7, 2, "G"); c.put(8, 2, "G"); c.put(7, 13, "G"); c.put(8, 13, "G")
        c.outline(); return c
    return draw

def trophy(stage):
    tint = {1: "G", 2: "L", 3: "W3"}[stage]
    def draw():
        c = Canvas()
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                d = abs(dx) + abs(dy)
                if d <= 6:
                    c.put(x, y, "T" if d <= 2 else (tint if d <= 4 else "D"))
        for (x, y) in [(7, 1), (8, 1), (1, 7), (1, 8), (14, 7), (14, 8), (7, 14), (8, 14)]:
            c.put(x, y, "G")
        c.outline(); return c
    return draw

def ambrosia():
    c = Canvas()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 8.5
            d = (dx * dx + (dy * 0.85) ** 2) ** 0.5
            if d <= 5.5:
                c.put(x, y, "Rn" if d > 4.3 else ("G" if d > 3.3 else "P"))
    c.put(6, 9, "W"); c.put(9, 6, "C")
    for (x, y) in [(6, 2), (7, 1), (8, 1), (9, 2)]:
        c.put(x, y, "Sd")
    c.outline(); return c

def template():
    c = Canvas()
    c.fill(2, 13, 2, 13, "G")
    c.fill(3, 12, 3, 12, "D")
    for y in range(3, 13):
        for x in range(3, 13):
            d = abs(x - 7.5) + abs(y - 7.5)
            if d <= 4.5:
                c.put(x, y, "W" if d <= 1.5 else ("C" if d <= 2.5 else "L"))
    for (x, y) in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        c.put(x, y, "W")
    c.outline(); return c

ITEMS = {
    "god_sword": (sword, "handheld"), "god_axe": (axe, "handheld"),
    "god_mace": (mace, "handheld"), "god_spear": (spear, "handheld"),
    "god_helmet": (helmet, "generated"), "god_chestplate": (chestplate, "generated"),
    "god_leggings": (leggings, "generated"), "god_boots": (boots, "generated"),
    "god_template": (template, "generated"),
    "convergence_1": (convergence(1), "generated"), "convergence_2": (convergence(2), "generated"),
    "convergence_3": (convergence(3), "generated"),
    "trophy_1": (trophy(1), "generated"), "trophy_2": (trophy(2), "generated"), "trophy_3": (trophy(3), "generated"),
    "ambrosia": (ambrosia, "generated"),
}

# ---------------------------------------------------------------- armour worn-texture (64x32)
def armour_texture(path, leggings_variant):
    px = {}
    for y in range(32):
        for x in range(64):
            t = y / 31.0
            r = int(59 + (109 - 59) * t); g = int(26 + (52 - 26) * t); b = int(107 + (201 - 107) * t)
            if ((x // 4) + (y // 4)) % 2 == 0:
                r, g, b = min(255, r + 14), min(255, g + 10), min(255, b + 22)
            if x % 8 == 0 or y % 8 == 0:
                r, g, b = 20, 12, 36
            elif (x % 8 == 4 and y % 8 == 4):
                r, g, b = 92, 243, 255
            if (y % 16 in (7, 8)) and not leggings_variant:
                r, g, b = 255, 210, 63
            if leggings_variant and x % 16 in (7, 8):
                r, g, b = 255, 210, 63
            px[(x, y)] = (r, g, b, 255)
    write_png(path, 64, 32, px)

# ---------------------------------------------------------------- write the pack
def main():
    A = os.path.join(PACK_DIR, "assets", "godgear")
    previews = []
    for name, (fn, parent) in ITEMS.items():
        previews.append(fn().save(os.path.join(A, "textures", "item", name + ".png")))
        os.makedirs(os.path.join(A, "models", "item"), exist_ok=True)
        os.makedirs(os.path.join(A, "items"), exist_ok=True)
        with open(os.path.join(A, "models", "item", name + ".json"), "w") as f:
            json.dump({"parent": "minecraft:item/" + parent,
                       "textures": {"layer0": "godgear:item/" + name}}, f, indent=2)
        with open(os.path.join(A, "items", name + ".json"), "w") as f:
            json.dump({"model": {"type": "minecraft:model", "model": "godgear:item/" + name}}, f, indent=2)

    armour_texture(os.path.join(A, "textures", "entity", "equipment", "humanoid", "god.png"), False)
    armour_texture(os.path.join(A, "textures", "entity", "equipment", "humanoid_leggings", "god.png"), True)
    os.makedirs(os.path.join(A, "equipment"), exist_ok=True)
    with open(os.path.join(A, "equipment", "god.json"), "w") as f:
        json.dump({"layers": {"humanoid": [{"texture": "godgear:god"}],
                              "humanoid_leggings": [{"texture": "godgear:god"}]}}, f, indent=2)

    # pack icon = the template scaled x4
    t = previews[-1]
    big = {(x, y): t[(x // 4, y // 4)] for y in range(64) for x in range(64)}
    write_png(os.path.join(PACK_DIR, "pack.png"), 64, 64, big)

    # min_format/max_format: wide range so the pack loads on 26.1.x and later (Minecraft 1.21.9+ style meta)
    with open(os.path.join(PACK_DIR, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "GodGear - custom looks for god items",
                            "min_format": 46, "max_format": 999}}, f, indent=2)

    # preview sheet (icons x8) for a quick look
    sheet = {}
    W = len(previews) * 16 * 8
    for i, ic in enumerate(previews):
        for y in range(128):
            for x in range(128):
                c = ic[(x // 8, y // 8)]
                sheet[(i * 128 + x, y)] = c if c[3] else (40, 40, 48, 255)
    write_png(os.path.join(OUT, "preview.png"), W, 128, sheet)

    # deterministic zip
    zpath = os.path.join(OUT, "GodGearPack.zip")
    files = []
    for root, _, fs in os.walk(PACK_DIR):
        for fn in fs:
            full = os.path.join(root, fn)
            files.append((os.path.relpath(full, PACK_DIR).replace(os.sep, "/"), full))
    files.sort()
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, full in files:
            zi = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            with open(full, "rb") as fh:
                z.writestr(zi, fh.read())
    sha1 = hashlib.sha1(open(zpath, "rb").read()).hexdigest()
    print("Wrote", zpath)
    print("SHA-1:", sha1)

main()
