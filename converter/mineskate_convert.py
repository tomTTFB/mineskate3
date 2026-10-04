"""Converts the Skate 3 data MineSkate 3's skate mode reads, from the player's
own extracted default.xex: animation banks, state graphs, input and physics
settings, and the skater model the board is taken from.

Runs the converters of SK8-ENGINE/skate-3-rust-engine (the `tools` tree beside
this file, from the same revision as native/crates). Nothing is downloaded and
nothing from the game is bundled. Adapted from chasmlol/2010-rust-rewrite-mashup
skate/converter/iw4l_skate_convert.py (Apache-2.0).

    python mineskate_convert.py --xex <path to default.xex> --out <folder>

Writes <folder>/assets on success; progress lines go to stdout.
"""
from pathlib import Path
import argparse, shutil, sys, tempfile, traceback

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

REQUIRED = [
    'data/big/miscload.big',
    'data/big/miscboot.big',
    'data/big/db.big',
    'data/content/createacharacter.big',
]


def convert(xex, out):
    xex = xex.resolve()
    if xex.suffix.lower() == '.iso':
        raise RuntimeError('ISO files are not supported. Extract the disc and select its default.xex.')
    if xex.name.lower() != 'default.xex' or not xex.is_file():
        raise RuntimeError(f'Select default.xex from an extracted Skate 3 (Xbox 360) game folder, not {xex.name}.')
    game = xex.parent
    missing = [path for path in REQUIRED if not (game / path).is_file()]
    if missing:
        raise RuntimeError('This folder is missing Skate 3 game data (' + ', '.join(missing) +
                           '). Keep the data folder beside default.xex.')

    from tools.asset_pipeline import asset_exports as exports

    out = out.resolve()
    stage = out.with_name(out.name + '.partial')
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir(parents=True)

    def report(text):
        print(text, flush=True)

    with tempfile.TemporaryDirectory(prefix='mineskate3-', dir=stage.parent) as work, \
            (stage / 'conversion.log').open('w', encoding='utf-8') as log:
        work = Path(work)
        converted = exports.core(game, stage, work, report, log)
        exports.character(game, stage, work, report, log, converted)

    assets = stage / 'assets'
    for needed in ('private/skater.glb', 'private/game.json', 'private/stock/physics-skeletons.json',
                   'private/stock/skater-collections.json'):
        if not (assets / needed).is_file():
            raise RuntimeError(f'Conversion finished without {needed}.')
    shutil.rmtree(out, ignore_errors=True)
    stage.rename(out)
    report('Skate 3 data ready')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--xex', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if sys.version_info < (3, 11):
        print('ERROR: Python 3.11 or newer is required.', flush=True)
        return 2
    try:
        convert(args.xex, args.out)
    except Exception as error:
        traceback.print_exc()
        print(f'ERROR: {error}', flush=True)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
