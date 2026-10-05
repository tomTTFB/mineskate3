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

## Trick HUD

While skating you get Skate 3's own trick display: trick names as you land
them, sequence and line score, the combo multiplier, the line timer and
clean/sketchy landings. It is the original HUD movie from your disc, run by
the Skate engine's own player, next to the real scoring. Minecraft's
crosshair, hotbar and health bars are hidden while you skate.

If you converted your data before the HUD existed, open
**Mods > MineSkate 3 > Config**, select your `default.xex` again and press
**Add trick HUD**; then toggle skate mode off and on.

## Trick Guide

Press **G** (or **Back** on a controller while skating) to open Skate 3's own
Trick Guide: its menu movie, trick list and demo clips, played on your skater.
A full conversion includes it. If your data was converted before the guide
existed, open **Mods > MineSkate 3 > Config** (pressing **G** takes you there
too), select your `default.xex` again and press **Unpack Trick Guide**.

## Session markers

As in Skate 3: hold **LB** (keyboard **Q**) to bring up the marker panel,
press **D-pad down** (down arrow) to drop a marker, and hold **D-pad up**
(up arrow) to return to it.

## Skater, board and sounds

**Mods > MineSkate 3 > Config** has three switches:

- **Skater**: the converted Skate 3 skater (default), or the Minecraft player
  model with your own skin.
- **Sounds**: wheel roll, pops, landings, grinds and slides, bails and marker
  chimes. Skate 3's own audio cannot be extracted yet, so these are original
  synthesised effects (`sounds/generate.py`), driven by the engine's physics
  state.
- **Rumble**: controller vibration for rolling, grinds, pops, landings and
  bails. Only XInput pads (Xbox controllers, or anything Steam Input presents
  as one, on Windows) can vibrate; GLFW, which reads every other pad, has no
  rumble call.

Armour and held items are drawn on the skater, using the vanilla armour
boxes and item poses (over the Skate 3 skater as well, as an approximation).
Custom armour models from other mods are not used.

Other players with the mod see your real board, and your Skate 3 skater if
they have that switch on: your skeleton is sent to them and they pose their
own converted copy of the meshes with it. Players without converted data see
the Minecraft model and a plain board shape instead.

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

## Blocks and surfaces

- **Rails.** Ledges where the ground drops away become grind rails
  automatically. Fences, walls, glass panes, iron bars and lying chains are
  grindable down their middle, at the height you can see (not their 1.5 block
  collision). Straight staircases of three or more steps get a rail down each
  open side, like a hubba ledge.
- **Skate park blocks.** The **MineSkate 3 Skate Park** creative tab (also
  craftable) has blocks built for skating:
  - **Grind Rail**: a bar on a post, half a block high. Rails in a line join
    into one continuous rail; it runs the way you face when placing it.
  - **Ramp**: a 45 degree kicker, one block up over one block.
  - **Long Ramp (Bottom)** and **Long Ramp (Top)**: half as steep; place the
    top behind the bottom for a two block long ramp, or use the bottom alone
    as a small kicker.
  - **Quarter Pipe**: a curved transition to vertical, with a small deck
    whose coping edge should grind like any other ledge.

  Ramps rise away from you as you place them. Skate rides their smooth
  surface; walking players climb them like slabs. Ramps side by side, or end
  to end, form one surface. The models and textures come from
  `blocks/generate.py`.
- **Surfaces.** Ice keeps your speed, blue ice even more; soul sand and honey
  bog you down; sand, gravel, snow, dirt, grass and wool are slower than
  stone or wood; slime bounces you back up when you land on it. These act on
  top of Skate's own physics, which only knows one ground material.
- **Building while skating.** Placing or breaking a block near you rebuilds
  the board's collision within a tenth of a second.

## Servers

Skaters are moved by Skate's physics on their own client, so the server
relaxes its movement corrections for them. To keep that from being abused,
the server checks each skater's movement every tick (except the owner of a
singleplayer world): moving through walls, sustained speed past the limit,
or hanging in the air without falling takes the player off the board and puts
them back where they last moved cleanly.

`config/mineskate3-server.toml` in the server folder (a copy in
`<world>/serverconfig/` overrides it for that world):

| Setting | Default | What |
| --- | --- | --- |
| `allowSkating` | `true` | Whether anyone may skate |
| `permissionLevel` | `0` | Operator level needed to skate (0 = everyone) |
| `movementChecks.enabled` | `true` | The movement checks above |
| `movementChecks.maxSpeed` | `30` | Blocks per second |
| `movementChecks.maxAirSeconds` | `10` | Longest time off the ground without falling |

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
- The skate park blocks, the in-game Trick Guide unpacking, block surfaces,
  block rails, armour, held items, rumble and the server's
  movement checks are new and untested in game. The movement check limits
  (speed, airtime) are guesses at what real skating needs; if legitimate
  skating gets stopped, raise them in the server config and please report it.
- Controller vibration works only with XInput pads on Windows.
- The sounds are stand-ins, not Skate 3's own; their mix is untuned.

## Licence

GPL-3.0-only (see `LICENSE`), as required by the Skate 3 Rust Engine. Parts
adapted from the mashup are Apache-2.0; see `NOTICE`.
