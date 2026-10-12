"""Prepare Skate 3's Trick Guide from the user's extracted game.

Writes, under <assets>/private/trickguide:
- runtime/trickguide.json: the original menu movie (tricks/trickguide) in the
  scoring HUD's runtime format, plus every movie it imports from, under
  "libraries", so its shared controls can be resolved;
- tricks.json: the trick and basics records the menu asks FETrickTutorial
  for (name and description labels, demo clip, input slots, camera tracks,
  category reference), with VLT inheritance resolved;
- menu.json: the menu tree built from them (trick_guide_layout.py), and the
  runtime movie flattened into one character table;
- clips/*.abin: the guide's demo animations from scene.big;
- park/: the demo set (DIST_TrickGuide), see trick_guide_park.py.

and trickguide-actions.txt beside it: a readable listing of the movie's
bytecode, used to implement the game functions it calls.

No executable or game launch. Output contains copyrighted assets and stays
private.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import sys

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent)]

from vendor.skate3_ui.project import extract_project
from vendor.skate3_ui.scene_graph import AssetCache, SceneFlattener
from vendor.skate3_ui.actions import Actions
from tools.owned_game.big import BigArchive
from tools.asset_pipeline.vlt import hash64
from prepare_hud import font_mapping
import trick_guide_layout
import trick_guide_park

MOVIE = 'data/fe/source/screens/tricks/trickguide'
PREFIXES = (MOVIE, 'data/fe/source/controls/', 'data/fe/source/helper/')
CLIPS = 'data/scene/trickguide/'

# Field hashes in the trick definition and basics classes (see
# find_trick_guide.py's report): both store the clip under the same field.
CLIP = 'Hash_3B71EA27D53D7C4F'
NAME = ('Hash_843613E915014627', 'Hash_7296FFD6010D73EF')
DESCRIPTION = ('Hash_4663ADFED2CD5EF7', 'Hash_273DF7D4EE7A4C1C')
INPUTS = ('Hash_03ACC9F200807B87', 'Hash_6211C49D1F9B0367')
CAMERAS = ('Hash_3D3A79F16DCB54BB', 'Hash_B91DF5222FE184E3')
CATEGORY = 'Hash_06513CC28A532524'

# AVM1 names for the listing; APT keeps these and adds its own above 0xA0.
OPCODES = {
    0x00: 'End', 0x04: 'NextFrame', 0x05: 'PrevFrame', 0x06: 'Play', 0x07: 'Stop',
    0x0A: 'Add', 0x0B: 'Subtract', 0x0C: 'Multiply', 0x0D: 'Divide', 0x0E: 'Equals',
    0x0F: 'Less', 0x10: 'And', 0x11: 'Or', 0x12: 'Not', 0x13: 'StringEquals',
    0x14: 'StringLength', 0x15: 'StringExtract', 0x17: 'Pop', 0x18: 'ToInteger',
    0x1C: 'GetVariable', 0x1D: 'SetVariable', 0x20: 'SetTarget2', 0x21: 'StringAdd',
    0x22: 'GetProperty', 0x23: 'SetProperty', 0x24: 'CloneSprite', 0x25: 'RemoveSprite',
    0x26: 'Trace', 0x30: 'RandomNumber', 0x34: 'GetTime', 0x3A: 'Delete',
    0x3B: 'Delete2', 0x3C: 'DefineLocal', 0x3D: 'CallFunction', 0x3E: 'Return',
    0x3F: 'Modulo', 0x40: 'NewObject', 0x41: 'DefineLocal2', 0x42: 'InitArray',
    0x43: 'InitObject', 0x44: 'TypeOf', 0x45: 'TargetPath', 0x46: 'Enumerate',
    0x47: 'Add2', 0x48: 'Less2', 0x49: 'Equals2', 0x4A: 'ToNumber', 0x4B: 'ToString',
    0x4C: 'PushDuplicate', 0x4D: 'StackSwap', 0x4E: 'GetMember', 0x4F: 'SetMember',
    0x50: 'Increment', 0x51: 'Decrement', 0x52: 'CallMethod', 0x53: 'NewMethod',
    0x54: 'InstanceOf', 0x55: 'Enumerate2', 0x60: 'BitAnd', 0x61: 'BitOr',
    0x62: 'BitXor', 0x63: 'BitLShift', 0x64: 'BitRShift', 0x65: 'BitURShift',
    0x66: 'StrictEquals', 0x67: 'Greater', 0x68: 'StringGreater', 0x69: 'Extends',
    0x81: 'GotoFrame', 0x87: 'StoreRegister', 0x88: 'ConstantPool', 0x8B: 'SetTarget',
    0x8C: 'GoToLabel', 0x8E: 'DefineFunction2', 0x94: 'With', 0x96: 'Push',
    0x99: 'Jump', 0x9A: 'GetURL2', 0x9B: 'DefineFunction', 0x9D: 'If', 0x9E: 'Call',
    0x9F: 'GotoFrame2',
}


def key_id(name):
    return int(name[5:], 16) if name.startswith('Hash_') else hash64(name)


def bundle_name(cache, name):
    return cache.normalize_bundle(name)


class MissingBundle(Exception):
    def __init__(self, name):
        super().__init__(f'APT bundle not extracted: {name}')
        self.name = name


def collect_bundles(cache, root):
    """The movie and every movie it imports from, transitively."""
    order, pending = [], [bundle_name(cache, root)]
    while pending:
        name = pending.pop()
        if name in order:
            continue
        if name not in cache.bundles or 'timeline' not in cache.bundles[name]:
            raise MissingBundle(name)
        order.append(name)
        for item in cache.load_bundle(name)['imports'].values():
            pending.append(bundle_name(cache, item['file']))
    return order


def library(cache, output, name):
    """One movie in the scoring HUD's runtime layout."""
    bundle = cache.load_bundle(name)
    apt_path = output / 'raw' / (name + '.apt')
    actions = Actions(apt_path.read_bytes(), apt_path.with_suffix('.const').read_bytes())
    blocks = {}
    for c in bundle['characters'].values():
        for f in c.get('frames', []):
            for control in f['controls']:
                if control['type_name'] in ('do_action', 'do_init_action'):
                    offset = control.get('actions_offset', 0)
                    if offset:
                        blocks[str(offset)] = actions.stream(offset)
    shapes, fonts, unresolved = {}, {}, []
    for c in bundle['characters'].values():
        if c['type_name'] == 'shape':
            scene = SceneFlattener(cache, lambda *_: {}).flatten(name, c['id'])
            unresolved += scene['unresolved']
            shapes[str(c['id'])] = scene['primitives']
        elif c['type_name'] == 'font':
            fonts[c['font']['name']] = cache.font_asset(c['font']['name'])
    return {
        'bundle': name,
        'apt_sha256': hashlib.sha256(apt_path.read_bytes()).hexdigest(),
        'characters': list(bundle['characters'].values()),
        'imports': list(bundle['imports'].values()),
        'exports': bundle['apt'].get('exports', []),
        'shapes': shapes, 'fonts': fonts, 'actions': blocks,
        'unresolved_shapes': unresolved,
    }


def listing(name, lib, out):
    out.append(f'==== {name}')
    out.append('imports: ' + ', '.join(f"{i['name']} <- {i['file']} (id {i['character_id']})"
                                       for i in lib['imports']) if lib['imports'] else 'imports: none')
    out.append('exports: ' + ', '.join(f"{e['name']} = {e['character_id']}" for e in lib['exports'])
               if lib['exports'] else 'exports: none')
    kinds = {}
    for c in lib['characters']:
        kinds.setdefault(c['type_name'], []).append(c['id'])
    for kind, ids in sorted(kinds.items()):
        out.append(f'{kind}: {ids}')

    def rows(code, depth):
        for row in code:
            op = row['opcode']
            text = OPCODES.get(op, f'op_{op:02X}')
            if 'operand' in row:
                text += f' {row["operand"]!r}'
            if 'target' in row:
                text += f' -> {row["target"]}'
            if 'values' in row:
                text += ' ' + ', '.join(repr(v['value']) for v in row['values'])
            if 'name' in row:
                text += f' {row["name"]}(' + ', '.join(p['name'] for p in row['parameters']) + ')'
            out.append(f'{"  " * depth}{row["offset"]:6d} {text}')
            if 'body' in row:
                rows(row['body'], depth + 1)

    for offset, code in sorted(lib['actions'].items(), key=lambda kv: int(kv[0])):
        owners = [f"{c['type_name']} {c['id']} frame {i}"
                  for c in lib['characters'] for i, f in enumerate(c.get('frames', []))
                  for ctl in f['controls'] if str(ctl.get('actions_offset')) == offset]
        out.append(f'-- block {offset} ({"; ".join(owners) or "unknown owner"})')
        rows(code, 1)


def tricks(collections):
    rows = json.loads(collections.read_text(encoding='utf-8'))['collections']
    by_key = {(r['class'], r['key']): r for r in rows}

    def field(row, name):
        """VLT inheritance: a row without the field takes its parent's."""
        seen = set()
        while row is not None and id(row) not in seen:
            seen.add(id(row))
            if name in row['fields']:
                return row['fields'][name]
            row = by_key.get((row['class'], row['parent'])) if row['parent'] else None
        return None

    def first(row, names):
        for name in names:
            value = field(row, name)
            if value is not None:
                return value
        return None

    def text(row, names):
        value = first(row, names)
        return value['data'] if value else None

    def slots(row):
        out = []
        for name in INPUTS:
            value = field(row, name)
            for item in (value or {}).get('array', {}).get('items', []):
                raw = bytes.fromhex(item)
                out.append([int.from_bytes(raw[i:i + 4], 'big') for i in range(0, len(raw), 4)])
        return out

    def reference(row, name):
        value = field(row, name)
        if not value:
            return None
        raw = bytes.fromhex(value['data'])
        if value['type'] == 'Attrib::RefSpec':  # class, then key
            return {'class': raw[:8].hex().upper(), 'key': raw[8:16].hex().upper()}
        return {'key': raw[:8].hex().upper()}  # Attrib::Gen::ClassRefSpec_*: class implied

    classes = {r['class'] for r in rows if CLIP in r['fields']}
    result = []
    for r in rows:
        if r['class'] not in classes:
            continue
        result.append({
            'class': r['class'], 'key': r['key'], 'key_id': f"{key_id(r['key']):016X}",
            'parent': r['parent'] or None,
            'name': text(r, NAME), 'description': text(r, DESCRIPTION), 'clip': text(r, (CLIP,)),
            'inputs': slots(r),
            'cameras': [reference(r, name) for name in CAMERAS],
            'category': reference(r, CATEGORY),
        })
    wanted = {c['key'] for r in result for c in r['cameras'] if c}
    cameras = [{'class': r['class'], 'key': r['key'], 'parent': r['parent'] or None, 'fields': r['fields']}
               for r in rows if f"{key_id(r['key']):016X}" in wanted]
    return {'format': 'skate3-trick-guide-tricks', 'version': 1, 'records': result,
            'camera_tracks': cameras}


PARK = ('data/content/world/models/dist_trickguide.rx2',
        'data/content/world/models/dist_trickguide_textures.rx2')


def park_sources(game, destination):
    """The demo set's model and texture dictionary, as the disc ships them."""
    archive = BigArchive(game / 'data' / 'big' / 'miscload.big')
    destination.mkdir(parents=True, exist_ok=True)
    found = []
    for e in archive.entries:
        path = e.path.replace('\\', '/').lower()
        if path in PARK:
            (destination / Path(path).name).write_bytes(archive.read(e))
            found.append(path)
    if len(found) != len(PARK):
        raise ValueError(f'Trick guide set missing from miscload.big: {sorted(set(PARK) - set(found))}')


def clips(game, destination):
    archive = BigArchive(game / 'data' / 'big' / 'scene.big')
    entries = [e for e in archive.entries if e.path.replace('\\', '/').lower().startswith(CLIPS)
               and e.path.lower().endswith('.abin')]
    destination.mkdir(parents=True, exist_ok=True)
    names = []
    for e in entries:
        name = Path(e.path.replace('\\', '/')).name.lower()
        (destination / name).write_bytes(archive.read(e))
        names.append(name)
    return sorted(names)


def prepare(game: Path, assets: Path, work: Path, collections: Path):
    target = assets / 'private' / 'trickguide'
    cache_root = work / 'ui'
    prefixes = list(PREFIXES)
    for _ in range(32):
        extract_project(game, cache_root, prefixes=tuple(prefixes), update=True)
        cache = AssetCache(cache_root)
        try:
            names = collect_bundles(cache, MOVIE)
            break
        except MissingBundle as missing:
            # An import outside the expected folders: extract it too.
            if missing.name in prefixes:
                raise
            prefixes.append(missing.name)
    else:
        raise RuntimeError('Trick guide imports did not settle')
    mappings = font_mapping(collections, cache)
    libraries = {n: library(cache, cache_root, n) for n in names}
    main = libraries[names[0]]
    language = {row['label'].strip(): row['value'] for row in json.loads(
        (cache_root / 'metadata/languages/english_global.json').read_text(encoding='utf-8'))['entries']}
    fonts = {}
    for lib in libraries.values():
        fonts.update(lib['fonts'])
    runtime = {
        'format': 'skate3-fe-screen', 'version': 1,
        'source': {'bundle': names[0], 'apt_sha256': main['apt_sha256'],
                   'collections_sha256': hashlib.sha256(collections.read_bytes()).hexdigest()},
        'characters': main['characters'], 'shapes': main['shapes'], 'fonts': fonts,
        'actions': main['actions'], 'imports': main['imports'],
        'libraries': {n: libraries[n] for n in names[1:]},
        'font_mappings': mappings, 'language': language,
        'unresolved_fonts': [f for f, asset in fonts.items() if asset is None],
    }

    shutil.rmtree(target, ignore_errors=True)
    (target / 'runtime').mkdir(parents=True)
    lines = []
    for n in names:
        listing(n, libraries[n], lines)
    (target / 'trickguide-actions.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    # Shape and font textures are written by the cache; copy the ones used.
    for lib in libraries.values():
        for primitives in lib['shapes'].values():
            for p in primitives:
                if p['texture']:  # Solid fills carry only a colour.
                    copy_texture(cache_root, target, p['texture']['rgba'])
    for asset in fonts.values():
        if asset:
            copy_texture(cache_root, target, asset['texture'])
    (target / 'runtime' / 'trickguide.json').write_text(json.dumps(runtime, separators=(',', ':')) + '\n',
                                                         encoding='utf-8')
    data = tricks(collections)
    (target / 'tricks.json').write_text(json.dumps(data, indent=1) + '\n', encoding='utf-8')
    # One character table and the menu tree, as the runtime plays them.
    trick_guide_layout.upgrade(target)
    clip_names = clips(game, target / 'clips')
    park_sources(game, target / 'park' / 'source')
    try:
        park_meshes, _ = trick_guide_park.convert(target)
    except Exception as e:  # The menu and demos still work on a plain backdrop.
        park_meshes = 0
        print(f'WARNING: trick guide set not converted: {e}', flush=True)

    print(f'Trick guide: {len(names)} movie(s), {len(data["records"])} trick records, '
          f'{len(clip_names)} demo clips, {park_meshes} set meshes: {target}', flush=True)
    for lib in libraries.values():
        if lib['unresolved_shapes']:
            print(f"WARNING: {lib['bundle']}: unresolved shapes {lib['unresolved_shapes'][:10]}", flush=True)
    if runtime['unresolved_fonts']:
        print('WARNING: unresolved fonts: ' + ', '.join(runtime['unresolved_fonts']), flush=True)
    return target


def copy_texture(cache_root, target, relative):
    source = (cache_root / relative).resolve()
    if not source.is_relative_to(cache_root.resolve()):
        raise ValueError(f'Texture path escapes the cache: {relative}')
    destination = target / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        return True
    try:
        shutil.copyfile(source, destination)
    except OSError as error:
        print(f'WARNING: trick guide texture {relative}: {error}', flush=True)
        return False
    return True


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--game', type=Path, required=True, help='Folder holding default.xex and data')
    p.add_argument('--assets', type=Path, required=True, help='Converted assets folder (skate-data/assets)')
    p.add_argument('--work', type=Path, required=True, help='Scratch folder for the extraction cache')
    args = p.parse_args()
    prepare(args.game.resolve(), args.assets.resolve(), args.work.resolve(),
            args.assets.resolve() / 'private' / 'stock' / 'skater-collections.json')
