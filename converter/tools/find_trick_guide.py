"""Look at Skate 3's Trick Guide in the user's extracted game, read only.

Reports, for the guide only:
- English strings that belong to it (labels and the text around the
  "Stale Fish" description),
- the string constants of its menu movie (tricks/trickguide), which name the
  game functions it calls,
- the demo animations in scene.big (data/scene/trickguide/*.abin), with a
  test parse of a few of them,
- the guide's demo set models (DIST_TrickGuide*.rx2).

Nothing is copied or converted. The report is printed and also written to
trick-guide-report.txt in the current folder.

    cd converter/tools
    python find_trick_guide.py --game "path/to/Skate 3"

--game defaults to the current folder.
"""
import argparse
import re
import sys
import tempfile
from pathlib import Path

try:
    from vendor.skate3_ui.big import BigArchive
    from vendor.skate3_ui.language import pair_language_tables
    from vendor.skate3_ui.project import find_big_directory
    from vendor.skate3_ui.actions import Actions
    from vendor.skate3_anim.abin_importer import AbinFile
except ModuleNotFoundError:
    sys.exit('Run this from the mod\'s converter/tools folder (it needs the vendor folder beside it), '
             'and point --game at your Skate 3 folder.')

LANGUAGE = ('data/fe/languages/labels/language_labels_global_skate3ng.bin',
            'data/fe/languages/english/language_english_global_skate3ng.bin')
MOVIE = 'data/fe/source/screens/tricks/trickguide'
CLIPS = 'data/scene/trickguide/'
LABEL = re.compile(r'trick_?guide|tricktip|trick_tip|_tg_|^id_tg', re.I)
TEXT = re.compile(r'stale fish|turn the board sideways', re.I)
STRING_LIMIT = 400
PARSE_SAMPLES = 3


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game', type=Path, default=Path.cwd(),
                        help='Folder holding default.xex and the data folder (default: current folder)')
    args = parser.parse_args()
    big = find_big_directory(args.game)
    lines = [f'BIG folder: {big}']

    wanted = {}  # path -> bytes, from the last archive that has it
    clips = {}   # path -> (archive, entry), read lazily: there are hundreds
    sets = []
    for archive_path in sorted(big.glob('*.big')):
        try:
            archive = BigArchive(archive_path)
        except Exception as error:
            lines.append(f'{archive_path.name}: could not read ({error})')
            continue
        for entry in archive.entries:
            p = entry.path.replace('\\', '/').lower()
            if p in LANGUAGE or p in (MOVIE + '.apt', MOVIE + '.const'):
                wanted[p] = archive.read(entry)
            elif p.startswith(CLIPS) and p.endswith('.abin'):
                clips[p] = (archive, entry)
            elif 'dist_trickguide' in p:
                sets.append(f'  {archive_path.name}: {entry.path} ({entry.unpacked_size} bytes)')

    lines.append('\n== Trick guide strings')
    if all(k in wanted for k in LANGUAGE):
        with tempfile.TemporaryDirectory() as temporary:
            files = []
            for i, key in enumerate(LANGUAGE):
                f = Path(temporary) / f'{i}.bin'
                f.write_bytes(wanted[key])
                files.append(f)
            entries = pair_language_tables(*files)['entries']
        hits = {i for i, e in enumerate(entries) if LABEL.search(str(e['label'] or ''))}
        # The descriptions may sit under labels the pattern misses; show the
        # neighbourhood of a known one so the labelling scheme is visible.
        for i, e in enumerate(entries):
            if TEXT.search(str(e['value'] or '')):
                hits.update(range(max(0, i - 8), min(len(entries), i + 9)))
        lines.append(f'{len(hits)} strings')
        for i in sorted(hits)[:STRING_LIMIT]:
            e = entries[i]
            value = ' '.join(str(e['value'] or '').split())
            lines.append(f"  [{i}] {str(e['label'] or '').strip()} = {value[:200]}")
        if len(hits) > STRING_LIMIT:
            lines.append(f'  ... {len(hits) - STRING_LIMIT} more')
    else:
        lines.append('English language table not found')

    lines.append('\n== Menu movie constants (tricks/trickguide)')
    if MOVIE + '.apt' in wanted and MOVIE + '.const' in wanted:
        actions = Actions(wanted[MOVIE + '.apt'], wanted[MOVIE + '.const'])
        strings = sorted({c['value'] for c in actions.constants if c['kind'] == 1})
        lines.append(f'{len(strings)} distinct strings')
        lines += [f'  {s}' for s in strings]
    else:
        lines.append('trickguide.apt/.const not found')

    lines.append(f'\n== Demo animations ({len(clips)} in {CLIPS})')
    lines += [f'  {Path(p).name}' for p in sorted(clips)]
    for p in sorted(clips)[:PARSE_SAMPLES]:
        archive, entry = clips[p]
        try:
            abin = AbinFile(archive.read(entry))
            parts = abin.hierarchy.num_parts if abin.hierarchy else 'none'
            described = ', '.join(f'{c.header.name} ({c.num_frames} frames @ {c.fps:g} fps)'
                                  for c in abin.clips) or 'none'
            lines.append(f'  parse {Path(p).name}: skeleton parts {parts}, '
                         f'clips {described}, poses {len(abin.poses)}')
        except Exception as error:
            lines.append(f'  parse {Path(p).name}: failed ({type(error).__name__}: {error})')

    lines.append('\n== Demo set models')
    lines += sets or ['  none found']

    report = '\n'.join(lines)
    print(report)
    out = Path.cwd() / 'trick-guide-report.txt'
    out.write_text(report + '\n', encoding='utf-8')
    print(f'\nReport written to {out}')


if __name__ == '__main__':
    main()
