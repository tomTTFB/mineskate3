"""Look at Skate 3's Trick Guide data in the user's extracted game, read only.

Earlier passes found the guide's menu movie, strings, 312 demo clips and its
demo set, and that skatercollections.bin holds records linking each trick's
name and description labels to its clip (trickguide_hardflip_01, ...). This
pass converts that database the same way the converter does and prints:

- which collection classes reference trick guide clips, and how many rows,
- three such rows in full, with every field,
- every such row on one line: key, parent and its text fields,
- the parent chain of those rows (likely the guide's categories),
- rows of classes named after the guide, trickbook cameras or input slots.

Nothing is copied or converted to the install. The report is printed and also
written to trick-guide-report.txt in the current folder.

    python converter/tools/find_trick_guide.py --game "path/to/Skate 3"

--game defaults to the current folder.
"""
import argparse
import sys
import tempfile
from collections import Counter
from pathlib import Path

# Imported as tools.*, as the converter does.
HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent)]
try:
    from tools.owned_game.big import BigArchive
    from tools.asset_pipeline.vlt import convert as convert_vlt
except ModuleNotFoundError:
    sys.exit('Keep this script in the mod\'s converter/tools folder (it needs the folders beside it), '
             'and point --game at your Skate 3 folder.')

DATABASE = {'skaterschema.bin', 'skaterschema.vlt', 'skatercollections.bin', 'skatercollections.vlt'}
NAMED = ('trickguide', 'trickbook', 'tricktutorial', 'tutorial', 'inputslot')
FULL_ROWS = 3
NAMED_ROWS = 4


def texts(row):
    """Text fields of a row, arrays included."""
    out = {}
    for name, field in row['fields'].items():
        if field['type'] == 'EA::Reflection::Text':
            items = field.get('array', {}).get('text_items')
            out[name] = items if items is not None else field['data']
    return out


def mentions_clip(row):
    if any('trickguide_' in str(v).lower() for v in texts(row).values()):
        return True
    # Inline fixed-size strings come out as hex rather than text.
    for field in row['fields'].values():
        try:
            if b'trickguide_' in bytes.fromhex(str(field['data'])).lower():
                return True
        except ValueError:
            pass
    return False


def describe(row, indent='    '):
    lines = [f"{indent}class {row['class']}, key {row['key']}, parent {row['parent'] or '-'}"]
    for name, field in row['fields'].items():
        if 'array' in field:
            array = field['array']
            items = array.get('text_items', array.get('items'))
            lines.append(f"{indent}  {name} [{field['type']}] array: {str(items)[:300]}")
        else:
            data = str(field['data'])
            try:
                raw = bytes.fromhex(data).rstrip(b'\0')
                if raw and all(32 <= b < 127 for b in raw):
                    data += f' ("{raw.decode()}")'
            except ValueError:
                pass
            lines.append(f"{indent}  {name} [{field['type']}] = {data[:240]}")
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game', type=Path, default=Path.cwd(),
                        help='Folder holding default.xex and the data folder (default: current folder)')
    args = parser.parse_args()
    db = args.game / 'data' / 'big' / 'db.big'
    if not db.is_file():
        sys.exit(f'No data/big/db.big under {args.game}')

    with tempfile.TemporaryDirectory() as temporary:
        archive = BigArchive(db)
        archive.extract_entries([e for e in archive.entries if Path(e.path).name.lower() in DATABASE],
                                Path(temporary))
        stem = Path(temporary) / 'data' / 'db'
        names = (HERE / 'asset_pipeline' / 'names.txt').read_text(encoding='utf-8').splitlines()
        rows = convert_vlt(stem / 'skaterschema', stem / 'skatercollections', names)['collections']
    by_key = {r['key']: r for r in rows}
    lines = [f'{len(rows)} collection rows in skatercollections']

    guide = [r for r in rows if mentions_clip(r)]
    lines.append(f'\n== Classes whose rows name a trickguide_ clip ({len(guide)} rows)')
    for cls, n in Counter(r['class'] for r in guide).most_common():
        lines.append(f'  {cls}: {n}')

    lines.append(f'\n== {FULL_ROWS} of those rows in full')
    for r in guide[:FULL_ROWS]:
        lines += describe(r)

    lines.append('\n== Every such row: key | parent | text fields')
    for r in guide:
        t = '; '.join(f'{k}={v}' for k, v in texts(r).items())
        lines.append(f"  {r['key']} | {r['parent'] or '-'} | {t[:300]}")

    lines.append('\n== Parent chain of those rows')
    seen = set()
    for parent in Counter(r['parent'] for r in guide):
        chain = []
        while parent and parent not in seen and parent in by_key:
            seen.add(parent)
            chain.append(by_key[parent])
            parent = by_key[parent]['parent']
        for r in chain:
            lines += describe(r)

    lines.append('\n== Classes named after the guide, its cameras or input slots')
    classes = Counter(r['class'] for r in rows if any(w in r['class'].lower() for w in NAMED))
    for cls, n in classes.most_common():
        lines.append(f'  {cls}: {n} rows')
        for r in [r for r in rows if r['class'] == cls][:NAMED_ROWS]:
            lines += describe(r)

    report = '\n'.join(lines)
    print(report)
    out = Path.cwd() / 'trick-guide-report.txt'
    out.write_text(report + '\n', encoding='utf-8')
    print(f'\nReport written to {out}')


if __name__ == '__main__':
    main()
