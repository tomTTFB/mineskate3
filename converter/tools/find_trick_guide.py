"""Look at Skate 3's Trick Guide in the user's extracted game, read only.

Earlier passes located the guide's menu movie (tricks/trickguide), its
strings (ID_TRICK_*), demo clips (data/scene/trickguide/*.abin) and demo set
(DIST_TrickGuide*.rx2). The menu asks a native FETrickTutorial object for its
items, so this pass looks for that data and test-parses demo clips:

- every file in the smaller archives (db, miscload, fedata) and default.xex
  that mentions a demo clip name or a trick guide string label,
- a test parse of a few demo clips, now through the chunked-capable reader,
- whether the skeleton the clips are authored against matches across clips.

Nothing is copied or converted. The report is printed and also written to
trick-guide-report.txt in the current folder.

    python converter/tools/find_trick_guide.py --game "path/to/Skate 3"

--game defaults to the current folder.
"""
import argparse
import re
import sys
from pathlib import Path

# The archive reader is imported as tools.owned_game, as the converter does.
HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent)]
try:
    from tools.owned_game.big import BigArchive
    from vendor.skate3_ui.project import find_big_directory
    from vendor.skate3_anim.abin_importer import AbinFile
except ModuleNotFoundError:
    sys.exit('Keep this script in the mod\'s converter/tools folder (it needs the folders beside it), '
             'and point --game at your Skate 3 folder.')

CLIPS = 'data/scene/trickguide/'
SEARCHED = ('db.big', 'miscload.big', 'fedata.big', 'fedynamic.big')
NEEDLES = (b'trickguide_', b'TRICKGUIDE', b'TrickGuide', b'TrickTutorial', b'GRAB_STALE_GRAB')
PARSE = ('trickguide_stale_011.abin', 'trickguide_kickflip_011.abin',
         'trickguide_bs_nose_grind_011.abin', 'trickguide_bindpose1.abin')
CONTEXT_LIMIT = 12


def strings_near(data, at, span=200):
    """Printable runs around a hit, which usually show the record it sits in."""
    window = data[max(0, at - span):at + span]
    return [s.decode('latin-1') for s in re.findall(rb'[\x20-\x7e]{5,}', window)][:CONTEXT_LIMIT]


def search(name, data, lines):
    found = False
    for needle in NEEDLES:
        hits = [m.start() for m in re.finditer(re.escape(needle), data)]
        if not hits:
            continue
        if not found:
            lines.append(f'  {name} ({len(data)} bytes)')
            found = True
        lines.append(f'    "{needle.decode()}" x{len(hits)}; first hit nearby: '
                     + ' | '.join(strings_near(data, hits[0])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game', type=Path, default=Path.cwd(),
                        help='Folder holding default.xex and the data folder (default: current folder)')
    args = parser.parse_args()
    big = find_big_directory(args.game)
    lines = [f'BIG folder: {big}', '\n== Files mentioning the trick guide']

    xex = args.game / 'default.xex'
    if xex.is_file():
        search('default.xex', xex.read_bytes(), lines)
        lines.append('  (default.xex is usually compressed, so a miss there proves little)')
    else:
        lines.append(f'  no default.xex in {args.game}')

    clips = {}
    for archive_path in sorted(big.glob('*.big')):
        try:
            archive = BigArchive(archive_path)
        except Exception as error:
            lines.append(f'  {archive_path.name}: could not open ({error})')
            continue
        searched = archive_path.name.lower() in SEARCHED
        failures = 0
        for entry in archive.entries:
            p = entry.path.replace('\\', '/').lower()
            if p.startswith(CLIPS):
                clips[Path(p).name] = (archive, entry)
            if not searched or p.startswith(CLIPS):
                continue
            try:
                data = archive.read(entry)
            except Exception:
                failures += 1
                continue
            search(f'{archive_path.name}: {entry.path}', data, lines)
        if searched and failures:
            lines.append(f'  {archive_path.name}: {failures} entries could not be read')

    lines.append(f'\n== Demo clip test parse ({len(clips)} clips found)')
    skeletons = {}
    for name in PARSE:
        if name not in clips:
            lines.append(f'  {name}: not found')
            continue
        archive, entry = clips[name]
        try:
            data = archive.read(entry)
            abin = AbinFile(data)
            h = abin.hierarchy
            if h:
                skeletons[name] = (h.num_bones, tuple(h.parents))
            described = ', '.join(f'{c.header.name} ({c.num_frames} frames @ {c.fps:g} fps, '
                                  f'{len(c.parts)} parts)' for c in abin.clips) or 'none'
            lines.append(f'  {name}: {len(data)} bytes, compression {entry.compression}, '
                         f'skeleton {h.num_bones if h else "none"} bones / '
                         f'{h.num_parts if h else 0} parts, clips {described}, poses {len(abin.poses)}')
        except Exception as error:
            lines.append(f'  {name}: failed ({type(error).__name__}: {error})')
    if len(set(skeletons.values())) == 1 and len(skeletons) > 1:
        lines.append('  all parsed clips share one skeleton')
    elif skeletons:
        lines.append('  skeletons differ between clips: '
                     + ', '.join(f'{n} {b} bones' for n, (b, _) in skeletons.items()))

    report = '\n'.join(lines)
    print(report)
    out = Path.cwd() / 'trick-guide-report.txt'
    out.write_text(report + '\n', encoding='utf-8')
    print(f'\nReport written to {out}')


if __name__ == '__main__':
    main()
