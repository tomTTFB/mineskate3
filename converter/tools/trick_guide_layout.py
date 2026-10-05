"""Runtime layout for the Trick Guide: one flattened movie and its menu.

The guide's menu movie imports eight shared front-end controls. The HUD
runtime plays a single character table, so every movie is folded into it:
library characters get ids offset by 1000 per movie and their action blocks
by 1,000,000, imports resolve to the exporting movie's character, and the
init actions run in dependency order. menu.json is the tree the original
FETrickTutorial serves, built from the trick and basics records.

Usable on its own to upgrade an installed guide without the game:

    python trick_guide_layout.py <assets>/private/trickguide
"""
from pathlib import Path
import copy
import json
import sys

ID_STRIDE = 1000
ACTION_STRIDE = 1_000_000

# Sk8::FE::tTrickGuideInputSlot is (gesture, button, connector). Gesture
# values name gesture_item's g_* exports; derived from which tricks use
# which value (see find_trick_guide.py). Grind gestures are drawn as their
# frontside form, as the original movie only ships those.
GESTURES = {
    1: 'grind_fs_5_0', 2: 'grind_fs_salad', 3: 'grind_fs_bluntslide', 4: 'grind_fs_boardslide',
    5: 'grind_fs_crooks', 6: 'grind_fs_lipslide', 7: 'grind_fs_noseblunt', 8: 'grind_fs_overcrooks',
    9: 'grind_fs_salad', 10: 'grind_fs_noseslide', 11: 'grind_fs_tailslide', 12: 'grind_nose_grind',
    13: 'grind_fs_feeble', 14: 'grind_fs_smith', 15: 'grind_fs_willie', 16: 'grind_fs_overwillie',
    17: 'nollie', 18: 'nollie_360_flip', 19: 'nollie_360_hardflip', 20: 'nollie_360_heelflip',
    21: 'nollie_360_inward_heel', 22: 'nollie_360popshuvit', 23: 'nollie_fs_360popshuvit',
    24: 'nollie_fs_popshuvit', 25: 'nollie_hardflip', 26: 'nollie_heelflip',
    27: 'nollie_inward_heelflip', 28: 'nollie_kickflip', 29: 'nollie_popshuvit',
    30: 'nollie_varial_flip', 31: 'nollie_varial_heelflip', 32: 'nollie_fs_popshuvit_late',
    33: 'nollie_popshuvit_late', 34: 'nollie_underflip_shuv', 35: 'nollie_underflip_heelflip',
    36: 'nollie_underflip_kickflip', 37: 'ollie', 38: 'ollie_360_flip', 39: 'ollie_360_hardflip',
    40: 'ollie_360_heelflip', 41: 'ollie_360_inward_heelflip', 42: 'ollie_360popshuvit',
    43: 'ollie_fs_360popshuvit', 44: 'ollie_fs_popshuvit', 45: 'ollie_hardflip',
    46: 'ollie_heelflip', 47: 'ollie_inward_heelflip', 48: 'ollie_kickflip', 49: 'ollie_popshuvit',
    50: 'ollie_varial_heelflip', 51: 'ollie_varial_kickflip', 52: 'ollie_underflip_kickflip',
    53: 'ollie_underflip_shuv', 54: 'ollie_fs_popshuvit_late', 55: 'ollie_popshuvit_late',
    56: 'ollie_underflip_heelflip', 57: 'revert', 59: 'ollie',
}
# Button values, named as the game's <B:...> text markup and button_item2
# name them. From the guide descriptions and the tutorial hints.
BUTTONS = {
    3: 'A', 4: 'B', 5: 'Y', 6: 'X', 7: 'R_Trigger', 8: 'L_Trigger', 9: 'L_Bumper',
    10: 'R_Bumper', 11: 'L_Bumper', 12: 'R_Bumper', 13: 'Stick_Right_Up', 14: 'Stick_Right_Down',
    15: 'Stick_Right_L', 16: 'Stick_Right_R', 22: 'Stick_Left', 23: 'Stick_Right',
}
CONNECTORS = {1: 'Plus', 2: 'Arrow', 3: 'Or'}

# The top level is built by the game's code, not stored with the records;
# these are its labels from the language table, grouping the category
# records by their key.
TOP = [
    ('ID_TRICK_TYPE_FLIP_TRICKS', 'ID_TRICK_TYPE_FLIP_TRICKS',
     ['8BCA54F004465329', 'CB594B32C8703AB5', '3A3E0D4AA1B87F7F', 'F478BDEC4B22DE46',
      '0D3E66B81A15B7B8']),
    ('ID_TRICK_TYPE_AIR_TRICKS', 'ID_TRICK_TYPE_AIR_GRABS_DESC',
     ['A22341F244B10D2D', 'BAE682122C32CC6B', '9921D2A55C1330D8']),
    ('ID_TRICK_TYPE_GRINDS_EDGE_TRICKS', 'ID_TRICK_FLIP_GRIND_GRINDS_DESC',
     ['0AE5C05D32117E6C', '76AF838034B4008B', '80BB1781D6ECA1B8', 'FF4ECA2182304401']),
    ('ID_TRICK_TYPE_PLANTS', 'ID_TRICK_TYPE_HAND_PLANTS_DESC',
     ['CD0900CAB3E45742', '2F66C87DF862EA07', '1DDA060512D85054']),
]
# Tricks that sit under the default category but have their own entry.
OTHER_TRICKS = ['ID_TRICK_GROUND_TRICK_MANUAL', 'ID_TRICK_GROUND_TRICK_NOSE_MANUAL',
                'ID_TRICK_HIPPYJUMP', 'ID_TRICK_NOCOMPLY', 'ID_TRICK_GROUND_TRICK_FS_GRAB',
                'ID_TRICK_GROUND_TRICK_BS_GRAB', 'ID_TRICK_GROUND_TRICK_DOUBLE_GRAB']
BASICS_PARENT = 'Hash_16D262026427DE03'
MANUALS = '046ED0CCBDA1091F'


def normalize(name):
    name = name.replace('\\', '/').lower()
    return 'data/fe/' + name if name.startswith('source/') else name


def flatten(runtime):
    """v1 runtime (main movie + libraries) to one character table."""
    movies = {runtime['source']['bundle']: runtime}
    movies.update(runtime.get('libraries', {}))
    main = runtime['source']['bundle']

    order = []  # dependency order, leaves first
    def visit(name, stack=()):
        if name in order:
            return
        if name in stack:
            raise ValueError(f'Import cycle through {name}')
        if name not in movies:
            raise ValueError(f'Imported movie not extracted: {name}')
        for item in movies[name].get('imports', []):
            visit(normalize(item['file']), stack + (name,))
        order.append(name)
    visit(main)
    index = {main: 0}
    for name in order:
        index.setdefault(name, len(index))

    local = {n: {c['id'] for c in movies[n]['characters']} for n in order}
    imports = {n: {i['character_id']: i for i in movies[n].get('imports', [])} for n in order}
    exports = {n: {e['name']: e['character_id'] for e in movies[n].get('exports', [])} for n in order}

    def gid(name, cid, depth=0):
        if depth > 16:
            raise ValueError('Import chain too deep')
        if cid in local[name]:
            return index[name] * ID_STRIDE + cid
        item = imports[name].get(cid)
        if item is None:
            raise ValueError(f'{name}: character {cid} is neither local nor imported')
        target = normalize(item['file'])
        if item['name'] not in exports[target]:
            raise ValueError(f"{target}: export {item['name']!r} is absent")
        return gid(target, exports[target][item['name']], depth + 1)

    def aid(name, offset):
        return index[name] * ACTION_STRIDE + offset if offset else 0

    characters, shapes, actions, linkage, init = [], {}, {}, {}, []
    for name in order:
        movie = movies[name]
        b = index[name]
        for c in sorted(movie['characters'], key=lambda c: c['id']):
            c = copy.deepcopy(c)
            c['id'] = gid(name, c['id'])
            c['bundle'] = b
            for frame in c.get('frames', []):
                for control in frame['controls']:
                    if control['type_name'].startswith('place') and control.get('flags', 0) & 2:
                        control['character_id'] = gid(name, control['character_id'])
                    if control.get('actions_offset'):
                        control['actions_offset'] = aid(name, control['actions_offset'])
            if c.get('text') and c['text'].get('font_id') is not None:
                c['text']['font_id'] = gid(name, c['text']['font_id'])
            characters.append(c)
        for key, primitives in movie['shapes'].items():
            shapes[str(gid(name, int(key)))] = primitives
        for key, code in movie['actions'].items():
            actions[str(aid(name, int(key)))] = code
        for export, cid in exports[name].items():
            if not export.startswith('__Packages.'):
                linkage.setdefault(str(gid(name, cid)), export)
        # Class definitions then registerClass calls: library roots are never
        # placed, so their frame-0 init actions run once at load.
        root = next((c for c in movie['characters'] if c['id'] == 0), None)
        for c in sorted(movie['characters'], key=lambda c: c['id']):
            for frame in c.get('frames', [])[:1] if c is root else c.get('frames', []):
                for control in frame['controls']:
                    if control['type_name'] == 'do_init_action' and control.get('actions_offset'):
                        init.append(aid(name, control['actions_offset']))

    exported = {str(index[n]): {e: gid(n, cid) for e, cid in exports[n].items()} for n in order}
    out = {k: v for k, v in runtime.items() if k not in ('libraries', 'imports')}
    out.update({
        'format': 'skate3-fe-screen', 'version': 2,
        'characters': characters, 'shapes': shapes, 'actions': actions,
        'exports': exported, 'linkage': linkage, 'init_order': init,
        'bundles': {str(index[n]): n for n in order},
    })
    return out


def slot_strings(inputs):
    slots = []
    for gesture, button, connector in inputs:
        if not gesture and not button:
            continue
        slots.append([GESTURES.get(gesture, ''), BUTTONS.get(button, ''), CONNECTORS.get(connector, '')])
    return slots


def build_menu(data, language):
    records = data['records']
    by_key = {r['key_id']: r for r in records}

    def label(r):
        return language.get(r['name'] or '', r['name'] or '')

    def leaf(r):
        return {'name': r['name'], 'description': r['description'] or '', 'clip': r['clip'] or '',
                'inputs': slot_strings(r['inputs']),
                'camera': next((c['key'] for c in r['cameras'] if c), None)}

    groups = {}
    for r in records:
        if r['clip'] and r['name'] and r['category']:
            groups.setdefault(r['category']['key'], []).append(r)

    def category(key, name=None, description=None):
        rows = sorted(groups.get(key, []), key=label)
        record = by_key.get(key, {})
        return {'name': name or record.get('name'), 'description': description or record.get('description') or '',
                'children': [leaf(r) for r in rows]}

    top = []
    for name, description, keys in TOP:
        top.append({'name': name, 'description': description,
                    'children': [category(k) for k in keys if groups.get(k)]})
    others = [r for r in records if r['name'] in OTHER_TRICKS and r['clip']]
    seen, other_leaves = set(), []
    for r in sorted(others, key=label):
        if r['name'] not in seen:
            seen.add(r['name'])
            other_leaves.append(leaf(r))
    basics = [r for r in records if r['parent'] == BASICS_PARENT and r['name']]
    actions = [r for r in basics if any(any(s) for s in r['inputs'])]
    terms = [r for r in basics if not any(any(s) for s in r['inputs'])]
    actions = [leaf(r) for r in sorted(actions, key=label)]
    top.append({'name': 'ID_TRICK_TYPE_OTHER_TRICKS', 'description': '',
                'children': [category(MANUALS, 'ID_TRICK_TYPE_MANUALS')] + other_leaves})
    top.append({'name': 'ID_TRICK_TYPE_ACTIONS', 'description': '', 'children': actions})
    top.append({'name': 'ID_TRICK_TYPE_TERMINOLOGY', 'description': 'ID_TRICK_TYPE_TERMINOLOGY_DESC',
                'children': [leaf(r) for r in sorted(terms, key=label)]})
    return {'format': 'skate3-trick-guide-menu', 'version': 1,
            'name': 'ID_SCREEN_TRICK_GUIDE_TITLE', 'description': 'ID_TRICKGUIDE_CHOOSE_CATEGORY',
            'children': top}


def upgrade(folder: Path):
    folder = Path(folder)
    runtime_path = folder / 'runtime' / 'trickguide.json'
    runtime = json.loads(runtime_path.read_text(encoding='utf-8'))
    if runtime.get('version') == 1:
        runtime = flatten(runtime)
        runtime_path.write_text(json.dumps(runtime, separators=(',', ':')) + '\n', encoding='utf-8')
    data = json.loads((folder / 'tricks.json').read_text(encoding='utf-8'))
    menu = build_menu(data, runtime['language'])
    (folder / 'menu.json').write_text(json.dumps(menu, indent=1) + '\n', encoding='utf-8')
    return runtime, menu


if __name__ == '__main__':
    runtime, menu = upgrade(Path(sys.argv[1]))
    print(f"Trick guide runtime v{runtime['version']}: {len(runtime['characters'])} characters, "
          f"{sum(len(c['children']) for c in menu['children'])} menu entries", flush=True)
