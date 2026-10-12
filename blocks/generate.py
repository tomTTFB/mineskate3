"""Writes MineSkate 3's skate park block assets into the mod's resources.

The ramps' OBJ models are built from the same side profiles as
dev.mineskate3.block.RampBlock.Profile (keep the two in step), and every
texture is drawn here from noise and lines. Rerun after changing anything:

    python blocks/generate.py

Standard library only.
"""
from pathlib import Path
import json
import math
import random
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
ASSETS = ROOT / 'assets/mineskate3'
DATA = ROOT / 'data'
MOD = 'mineskate3'

# Must match RampBlock.Profile.
DECK = 0.125
CURVE = 8


def quarter_pipe():
    reach = 1 - DECK
    points = [(reach * math.sin(math.pi / 2 * i / CURVE), 1 - math.cos(math.pi / 2 * i / CURVE))
              for i in range(CURVE + 1)]
    return points + [(1.0, 1.0)]


RAMPS = {
    'ramp': [(0.0, 0.0), (1.0, 1.0)],
    'long_ramp_low': [(0.0, 0.0), (1.0, 0.5)],
    'long_ramp_high': [(0.0, 0.5), (1.0, 1.0)],
    'quarter_pipe': quarter_pipe(),
}
NAMES = {
    'grind_rail': 'Grind Rail',
    'ramp': 'Ramp',
    'long_ramp_low': 'Long Ramp (Bottom)',
    'long_ramp_high': 'Long Ramp (Top)',
    'quarter_pipe': 'Quarter Pipe',
}


# --- textures ----------------------------------------------------------------

def png(path, pixels):
    """Writes 16x16 RGB `pixels` (rows of (r, g, b)) as a PNG."""
    raw = b''.join(b'\0' + bytes(c for px in row for c in px) for row in pixels)

    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))

    header = struct.pack('>IIBBBBB', len(pixels[0]), len(pixels), 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header)
                     + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(colour, k):
    return tuple(clamp(c * k) for c in colour)


def ramp_surface(rng):
    """Smooth sealed plywood with faint grain and a seam across the top row."""
    base = (184, 150, 104)
    rows = []
    for y in range(16):
        grain = 1 + 0.04 * math.sin(y * 1.7 + rng.random())
        row = []
        for x in range(16):
            k = grain + rng.uniform(-0.03, 0.03)
            if y == 0:
                k *= 0.78
            row.append(shade(base, k))
        rows.append(row)
    return rows


def ramp_side(rng):
    """Framing: plywood skin with a darker stud down each side and a bottom plate."""
    base = (150, 112, 70)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            k = 1 + rng.uniform(-0.05, 0.05)
            if x in (0, 15) or y == 15:
                k *= 0.72
            elif x in (1, 14) or y == 14:
                k *= 0.88
            row.append(shade(base, k))
        rows.append(row)
    return rows


def rail(rng):
    """Brushed steel, lighter along the middle where it is ground smooth."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            k = 0.82 + 0.25 * math.exp(-((x - 7.5) / 3.5) ** 2) + rng.uniform(-0.03, 0.03)
            row.append(shade((150, 155, 162), k))
        rows.append(row)
    return rows


# --- models --------------------------------------------------------------------

def to_block(u, v):
    """A ramp facing north (its back toward -z): depth u runs south to north, width v west to east."""
    return v, 1 - u


def ramp_obj(name, points):
    """The ramp as an OBJ: surface quads under `surface`, everything else under `side`."""
    vertices, uvs, faces = [], [], {'surface': [], 'side': []}

    def quad(material, normal, corners):
        """corners: four (u, v, h, tu, tv); wound so the face looks along `normal` (u, v, h)."""
        world = []
        for u, v, h, tu, tv in corners:
            x, z = to_block(u, v)
            world.append((x, h, z))
        nx, nz = to_block(normal[0], normal[1])
        nz -= 1  # to_block maps directions with an offset; undo it
        n = (nx, normal[2], nz)
        a, b, c = world[0], world[1], world[2]
        e = [b[i] - a[i] for i in range(3)]
        f = [c[i] - a[i] for i in range(3)]
        cross = (e[1] * f[2] - e[2] * f[1], e[2] * f[0] - e[0] * f[2], e[0] * f[1] - e[1] * f[0])
        if sum(cross[i] * n[i] for i in range(3)) < 0:
            world.reverse()
            corners = list(reversed(corners))
        ids = []
        for (x, y, z), (_, _, _, tu, tv) in zip(world, corners):
            vertices.append((x, y, z))
            uvs.append((tu, tv))
            ids.append(len(vertices))
        faces[material].append(ids)

    # Texture runs along the surface by distance travelled, wrapping each block.
    travelled = [0.0]
    for (ua, ha), (ub, hb) in zip(points, points[1:]):
        travelled.append(travelled[-1] + math.hypot(ub - ua, hb - ha))
    scale = 1 / travelled[-1]
    for k, ((ua, ha), (ub, hb)) in enumerate(zip(points, points[1:])):
        ta, tb = travelled[k] * scale, travelled[k + 1] * scale
        quad('surface', (-(hb - ha), 0, ub - ua),
             [(ua, 0, ha, 0, ta), (ub, 0, hb, 0, tb), (ub, 1, hb, 1, tb), (ua, 1, ha, 1, ta)])
        for v, nv in ((0, -1), (1, 1)):
            if ha <= 0 and hb <= 0:
                continue
            quad('side', (0, nv, 0),
                 [(ua, v, 0, ua, 0), (ub, v, 0, ub, 0), (ub, v, hb, ub, hb), (ua, v, ha, ua, ha)])
    front, back = points[0][1], points[-1][1]
    if front > 0:
        quad('side', (-1, 0, 0), [(0, 0, 0, 0, 0), (0, 1, 0, 1, 0), (0, 1, front, 1, front), (0, 0, front, 0, front)])
    if back > 0:
        quad('side', (1, 0, 0), [(1, 0, 0, 0, 0), (1, 1, 0, 1, 0), (1, 1, back, 1, back), (1, 0, back, 0, back)])
    quad('side', (0, 0, -1), [(0, 0, 0, 0, 0), (1, 0, 0, 0, 1), (1, 1, 0, 1, 1), (0, 1, 0, 1, 0)])

    lines = [f'# MineSkate 3 {name}, generated by blocks/generate.py', f'mtllib {MOD}:models/block/ramp.mtl',
             f'o {name}']
    lines += [f'v {x:.6f} {y:.6f} {z:.6f}' for x, y, z in vertices]
    lines += [f'vt {u:.6f} {v:.6f}' for u, v in uvs]
    for material, polys in faces.items():
        lines.append(f'usemtl {material}')
        lines += ['f ' + ' '.join(f'{i}/{i}' for i in ids) for ids in polys]
    return '\n'.join(lines) + '\n'


MTL = """# MineSkate 3 ramps, generated by blocks/generate.py
newmtl surface
map_Kd #surface
newmtl side
map_Kd #side
"""


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def ramp_model(name):
    return {
        'loader': 'neoforge:obj',
        'model': f'{MOD}:models/block/{name}.obj',
        'flip_v': True,
        'parent': 'minecraft:block/block',
        'textures': {
            'surface': f'{MOD}:block/ramp_surface',
            'side': f'{MOD}:block/ramp_side',
            'particle': f'{MOD}:block/ramp_surface',
        },
    }


RAIL_MODEL = {
    'parent': 'minecraft:block/block',
    'textures': {'rail': f'{MOD}:block/grind_rail', 'particle': f'{MOD}:block/grind_rail'},
    'elements': [
        {
            'from': [7, 6, 0], 'to': [9, 8, 16],
            'faces': {
                'north': {'uv': [7, 6, 9, 8], 'texture': '#rail', 'cullface': 'north'},
                'south': {'uv': [7, 6, 9, 8], 'texture': '#rail', 'cullface': 'south'},
                'east': {'uv': [0, 7, 16, 9], 'texture': '#rail'},
                'west': {'uv': [0, 7, 16, 9], 'texture': '#rail'},
                'up': {'uv': [7, 0, 9, 16], 'texture': '#rail'},
                'down': {'uv': [7, 0, 9, 16], 'texture': '#rail'},
            },
        },
        {
            'from': [7, 0, 7], 'to': [9, 6, 9],
            'faces': {
                side: {'uv': [7, 10, 9, 16], 'texture': '#rail'} for side in ('north', 'south', 'east', 'west')
            } | {'down': {'uv': [7, 7, 9, 9], 'texture': '#rail', 'cullface': 'down'}},
        },
    ],
}


def main():
    rng = random.Random(3)
    textures = ASSETS / 'textures/block'
    png(textures / 'ramp_surface.png', ramp_surface(rng))
    png(textures / 'ramp_side.png', ramp_side(rng))
    png(textures / 'grind_rail.png', rail(rng))

    models = ASSETS / 'models/block'
    models.mkdir(parents=True, exist_ok=True)
    (models / 'ramp.mtl').write_text(MTL, encoding='utf-8')
    for name, points in RAMPS.items():
        (models / f'{name}.obj').write_text(ramp_obj(name, points), encoding='utf-8')
        write_json(models / f'{name}.json', ramp_model(name))
        write_json(ASSETS / f'blockstates/{name}.json', {'variants': {
            f'facing={facing}': {'model': f'{MOD}:block/{name}', 'y': y}
            for facing, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}})
    write_json(models / 'grind_rail.json', RAIL_MODEL)
    write_json(ASSETS / 'blockstates/grind_rail.json', {'variants': {
        'axis=z': {'model': f'{MOD}:block/grind_rail'},
        'axis=x': {'model': f'{MOD}:block/grind_rail', 'y': 90}}})
    for name in NAMES:
        write_json(ASSETS / f'models/item/{name}.json', {'parent': f'{MOD}:block/{name}'})

    # Drops, tools and recipes.
    for name in NAMES:
        write_json(DATA / MOD / f'loot_table/blocks/{name}.json', {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{MOD}:{name}'}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}],
            'random_sequence': f'{MOD}:blocks/{name}',
        })
    write_json(DATA / 'minecraft/tags/block/mineable/pickaxe.json', {'values': [f'{MOD}:grind_rail']})
    write_json(DATA / 'minecraft/tags/block/mineable/axe.json', {'values': [f'{MOD}:{n}' for n in RAMPS]})

    def shaped(name, count, pattern, key):
        write_json(DATA / MOD / f'recipe/{name}.json', {
            'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': pattern,
            'key': {k: {'item': v} if ':' in v else {'tag': v[1:]} for k, v in key.items()},
            'result': {'id': f'{MOD}:{name}', 'count': count}})

    shaped('grind_rail', 6, ['III', ' I '], {'I': 'minecraft:iron_ingot'})
    shaped('ramp', 4, ['  P', ' PP', 'PPP'], {'P': '#minecraft:planks'})
    shaped('long_ramp_low', 6, ['  S', 'SSS'], {'S': '#minecraft:wooden_slabs'})
    shaped('long_ramp_high', 2, ['L', 'L'], {'L': f'{MOD}:long_ramp_low'})
    shaped('quarter_pipe', 4, ['P  ', 'P  ', 'PPI'], {'P': '#minecraft:planks', 'I': 'minecraft:iron_ingot'})

    lang = ASSETS / 'lang/en_us.json'
    entries = json.loads(lang.read_text(encoding='utf-8'))
    entries['itemGroup.mineskate3.skate_park'] = 'MineSkate 3 Skate Park'
    for name, title in NAMES.items():
        entries[f'block.{MOD}.{name}'] = title
    lang.write_text(json.dumps(entries, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
