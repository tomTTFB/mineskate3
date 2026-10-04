# MineSkate 3

A NeoForge mod for Minecraft 1.21.1 that puts Skate 3 into the game. Press
**J** and you drop onto a board driven by Skate 3's own physics, tricks,
grinds and camera, skating on the blocks around you.

It runs the [Skate 3 Rust Engine](https://github.com/SK8-ENGINE/skate-3-rust-engine)
inside Minecraft, the same way
[2010 Rust Rewrite Mashup](https://github.com/chasmlol/2010-rust-rewrite-mashup)
runs it inside MW2.

**Status: early and untested in game.** The native engine side builds and its
tests pass, but nobody has played this build yet. Expect rough edges; see
[Known gaps](#known-gaps).

## What you need

- Minecraft 1.21.1 with NeoForge 21.1.x.
- **Skate 3 for Xbox 360, extracted**: its `default.xex` with the game's
  `data` folder beside it. ISO files do not work; extract the disc first
  (for example `extract-xiso -x "Skate 3.iso"`).
- **Python 3.11 or newer** (3.13 recommended) for the one-time data
  conversion. The setup screen can install the `numpy` and `Pillow` packages
  it needs.
- An Xbox-style controller is recommended. Keyboard and mouse work too.

No game files are included in this mod.

## First run

1. Install the jar in your `mods` folder and start the game.
2. Join a world and press **J**. The setup screen opens.
3. Select your `default.xex` (or paste its path) and press **Convert**. The
   converted data goes to `<minecraft folder>/mineskate3/skate-data/`. Your
   game files are never modified.
4. Close the screen and press **J** again to skate.

## Controls

| Action | Controller | Keyboard + mouse |
| --- | --- | --- |
| Toggle skate mode | click both sticks | **J** |
| Steer / lean | left stick | W A S D |
| Flick-it tricks | right stick | move the mouse |
| A / B / X / Y | A / B / X / Y | Space / R / Left Shift / F |
| Bumpers | LB / RB | Q / E |
| Triggers | LT / RT | left / right mouse button |
| Back | Back | Tab |
| Stick clicks | L3 / R3 | C / V |
| D-pad | D-pad | arrow keys |

What each button does is Skate 3's own mapping.

Controllers are picked up automatically, in this order:

1. **XInput (Windows)**: Xbox controllers, and anything Steam Input presents
   as one, read through the Skate engine's own raw XInput code.
2. **Any controller GLFW knows**: Minecraft's GLFW plus the bundled
   [SDL_GameControllerDB](https://github.com/mdqinc/SDL_GameControllerDB)
   mappings cover PlayStation, Switch Pro and most generic pads, on Windows,
   macOS and Linux.
3. Otherwise keyboard and mouse.

The HUD line shows which one is in use. If it says a controller was "not
recognised", that pad has no mapping; on Windows, running it through Steam
Input usually fixes that.

## How it works

| Piece | Where | What |
| --- | --- | --- |
| Skate simulation | `native/crates/skate-*` | Skate 3 physics, animation graph and grind code from the Skate 3 Rust Engine, run headless (`skate-host`, from the mashup) |
| JNI bridge | `native/crates/skate-mc` | session thread, block collision, grind rails from block edges, retargeting onto the Minecraft player model, board mesh |
| Mod | `src/main/java/dev/mineskate3` | J key, input, collision gathering, camera, rendering, multiplayer pose relay |
| Converter | `converter/` | the engine's Python converters, run on your own `default.xex` |

Minecraft blocks become collision triangles around the skater (rebuilt as
you move). Ledges where the ground drops away are found automatically and
become grind rails. The skater's skeleton poses the vanilla player model
(your own skin); your board is the real Skate 3 board. Other players with
the mod see you skating; their view of your board is a box fitted to it.

## Building

```
cd native && cargo build --release -p skate-mc && cd ..
./gradlew build
```

The jar picks up the native library for your own platform from
`native/target/release`. GitHub Actions builds the library for Windows,
Linux and macOS and packages all of them into one jar
(`.github/workflows/build.yml`).

## Known gaps

- Only singleplayer and servers with the mod installed are expected to work.
  The server relaxes its movement checks for skaters; vanilla servers will
  rubber-band you.
- The camera mixin, retargeting directions and Skate's facing direction were
  written from the engine source and have not yet been checked in game.
- Blocks are refreshed every two seconds while skating, so a block you place
  may take a moment to become solid for the board.
- Armour and held items are not drawn while skating.
- No controller vibration yet.

## Licence

GPL-3.0-only (see `LICENSE`), as required by the Skate 3 Rust Engine. Parts
adapted from the mashup are Apache-2.0; see `NOTICE`.
