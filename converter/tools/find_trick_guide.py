"""Look for Skate 3's Trick Guide in the user's extracted game, read only.

Lists front-end screens and other archive entries whose paths look related
(trick guide, tips, demos), and English strings that look like the guide's
text. Nothing is copied or converted; the report is printed and also written
to trick-guide-report.txt next to this script.

    python find_trick_guide.py --game "path/to/Skate 3"
"""
import argparse
import re
import tempfile
from pathlib import Path

from vendor.skate3_ui.big import BigArchive
from vendor.skate3_ui.language import pair_language_tables
from vendor.skate3_ui.project import find_big_directory

PATH_WORDS = re.compile(r'trick|guide|tip|tutorial|demo|help|school|learn|howto|pause', re.I)
TEXT_WORDS = re.compile(r'trick ?guide|stale ?fish|turn the board sideways|back hand|flick', re.I)
LABEL_WORDS = re.compile(r'trick_?guide|trickguide|tricktip|trick_tip|_desc|tutorial|how_?to', re.I)
LANGUAGE = ('data/fe/languages/labels/language_labels_global_skate3ng.bin',
            'data/fe/languages/english/language_english_global_skate3ng.bin')
LIMIT = 60


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game', type=Path, required=True,
                        help='Folder holding default.xex and the data folder')
    args = parser.parse_args()
    big = find_big_directory(args.game)
    lines = [f'BIG folder: {big}']

    language = {}
    screens = set()
    for archive_path in sorted(big.glob('*.big')):
        try:
            archive = BigArchive(archive_path)
        except Exception as error:  # Not every .big need parse; say so and go on.
            lines.append(f'\n{archive_path.name}: could not read ({error})')
            continue
        paths = [e.path.replace('\\', '/') for e in archive.entries]
        hits = [p for p in paths if PATH_WORDS.search(p)]
        lines.append(f'\n{archive_path.name}: {len(paths)} entries, {len(hits)} matching paths')
        lines += [f'  {p}' for p in hits[:LIMIT]]
        if len(hits) > LIMIT:
            lines.append(f'  ... {len(hits) - LIMIT} more')
        for entry in archive.entries:
            p = entry.path.replace('\\', '/').lower()
            if p.startswith('data/fe/source/screens/') and p.endswith('.apt'):
                screens.add(p[len('data/fe/source/screens/'):-4])
            if p in LANGUAGE:
                language[p] = archive.read(entry)

    lines.append(f'\nAll front-end screens ({len(screens)}):')
    lines += [f'  {s}' for s in sorted(screens)]

    if len(language) == 2:
        with tempfile.TemporaryDirectory() as temporary:
            files = []
            for i, key in enumerate(LANGUAGE):
                f = Path(temporary) / f'{i}.bin'
                f.write_bytes(language[key])
                files.append(f)
            table = pair_language_tables(*files)
        entries = table['entries']
        matches = [e for e in entries
                   if TEXT_WORDS.search(str(e.get('value', ''))) or LABEL_WORDS.search(str(e.get('label', '')))]
        lines.append(f'\nEnglish strings: {len(entries)} total, {len(matches)} matching')
        for e in matches[:LIMIT]:
            value = ' '.join(str(e.get('value', '')).split())
            lines.append(f"  {str(e.get('label', '')).strip()} = {value[:140]}")
        if len(matches) > LIMIT:
            lines.append(f'  ... {len(matches) - LIMIT} more')
    else:
        lines.append('\nEnglish language table not found')

    report = '\n'.join(lines)
    print(report)
    out = Path(__file__).with_name('trick-guide-report.txt')
    out.write_text(report + '\n', encoding='utf-8')
    print(f'\nReport written to {out}')


if __name__ == '__main__':
    main()
