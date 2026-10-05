//! Trick Guide demos: the guide's own clips (data/scene/trickguide), on the
//! skater rig's first 32 bones, in the demo set's space.
//!
//! The clips use the RAW codec, which the stock skater banks never do:
//! per frame, each channel stores what its part's masks select of scale (3
//! floats), rotation (packed u32, or 4 floats) and translation (3 floats, or
//! three i16 and a u16 range). Channels are deltas on the file's own rest
//! pose (SK83_TPOSE): a bone's local transform is rest ∘ clip. The packed
//! rotation is the axis as azimuth (bits 31..21) and elevation (bits 20..11)
//! and sin(angle / 2) in bits 10..0, with w positive; decoding SK83_TPOSE
//! this way reproduces the stock RIG_TPOSE (OnBoard.abin) to 3 decimals.
use bevy::math::{Mat4, Quat, Vec3};
use skate_data::abin::{AnimationPart, Bank, Codec, Hierarchy, PartEntry, RecordData};
use std::path::{Path, PathBuf};

/// TRAJECTORY to the back right wheel: the bones these clips share with the
/// stock skater rig, by name and parent.
pub const BONES: usize = 32;

#[derive(Clone, Copy, Debug, PartialEq)]
struct Local {
    rotation: Quat,
    translation: Vec3,
}

impl Local {
    const IDENTITY: Self = Self {
        rotation: Quat::IDENTITY,
        translation: Vec3::ZERO,
    };
    fn then(self, delta: Self) -> Self {
        Self {
            rotation: (self.rotation * delta.rotation).normalize(),
            translation: self.translation + self.rotation * delta.translation,
        }
    }
    fn matrix(self) -> Mat4 {
        Mat4::from_rotation_translation(self.rotation, self.translation)
    }
}

#[derive(Clone, Copy, Default)]
struct Channel {
    rotation: Option<Quat>,
    translation: Option<Vec3>,
}

fn be_u32(bytes: &[u8], at: usize) -> Result<u32, String> {
    bytes
        .get(at..at + 4)
        .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
        .ok_or_else(|| format!("RAW data ends at {at:#x}"))
}

fn be_f32(bytes: &[u8], at: usize) -> Result<f32, String> {
    be_u32(bytes, at).map(f32::from_bits)
}

fn be_u16(bytes: &[u8], at: usize) -> Result<u16, String> {
    bytes
        .get(at..at + 2)
        .map(|b| u16::from_be_bytes([b[0], b[1]]))
        .ok_or_else(|| format!("RAW data ends at {at:#x}"))
}

/// See the module docs.
pub fn packed_rotation(word: u32) -> Quat {
    use std::f32::consts::{FRAC_PI_2, PI};
    let azimuth = ((word >> 21) & 0x7ff) as f32 / 2047.0 * 2.0 * PI - PI;
    let elevation = ((word >> 11) & 0x3ff) as f32 / 1023.0 * PI - FRAC_PI_2;
    let s = (word & 0x7ff) as f32 / 2047.0;
    let w = (1.0 - s * s).max(0.0).sqrt();
    let c = elevation.cos() * s;
    Quat::from_xyzw(c * azimuth.cos(), c * azimuth.sin(), elevation.sin() * s, w).normalize()
}

/// One frame of a RAW part's channels.
fn raw_channels(bytes: &[u8], part: &AnimationPart, frame: usize) -> Result<Vec<Channel>, String> {
    let header = part.compression_header.start;
    let masks: Vec<u32> = (0..6)
        .map(|i| be_u32(bytes, header + 4 * i))
        .collect::<Result<_, _>>()?;
    let frame_size = be_u16(bytes, header + 24)? as usize;
    let flags = bytes
        .get(header + 27..header + 29)
        .ok_or("RAW header truncated")?;
    let (packed_t, packed_q) = (flags[0] != 0, flags[1] != 0);
    let has =
        |channel: usize, kind: usize| masks[kind + (channel >> 5)] & (1 << (channel & 31)) != 0;
    let mut at = part.compressed_data.start + frame * frame_size;
    if at + frame_size > part.compressed_data.end {
        return Err(format!("RAW frame {frame} lies outside its part"));
    }
    let mut out = Vec::with_capacity(part.channel_count as usize);
    for channel in 0..part.channel_count as usize {
        let mut c = Channel::default();
        if has(channel, 0) {
            // Scale: the demo rig never animates it.
            at += 12;
        }
        if has(channel, 2) {
            c.rotation = Some(if packed_q {
                at += 4;
                packed_rotation(be_u32(bytes, at - 4)?)
            } else {
                at += 16;
                let f = |i: usize| be_f32(bytes, at - 16 + 4 * i);
                Quat::from_xyzw(f(0)?, f(1)?, f(2)?, f(3)?).normalize()
            });
        }
        if has(channel, 4) {
            c.translation = Some(if packed_t {
                at += 8;
                let i = |k: usize| be_u16(bytes, at - 8 + 2 * k).map(|v| v as i16 as f32);
                let range = be_u16(bytes, at - 2)? as f32 / 32768.0;
                Vec3::new(i(0)?, i(1)?, i(2)?) * range
            } else {
                at += 12;
                let f = |i: usize| be_f32(bytes, at - 12 + 4 * i);
                Vec3::new(f(0)?, f(1)?, f(2)?)
            });
        }
        out.push(c);
    }
    Ok(out)
}

/// One frame of a record's parts, placed by hierarchy part (positionally).
fn frame(
    bytes: &[u8],
    parts: &[PartEntry],
    hierarchy: &Hierarchy,
    frame: usize,
) -> Result<[Channel; BONES], String> {
    let mut out = [Channel::default(); BONES];
    for (i, entry) in parts.iter().enumerate() {
        let Some(part) = entry.part.as_ref().filter(|p| p.compressed_size > 0) else {
            continue;
        };
        let base = hierarchy
            .parts
            .get(i)
            .ok_or("clip has more parts than its hierarchy")?
            .sqt_offset as usize;
        for (j, channel) in raw_channels(bytes, part, frame)?.into_iter().enumerate() {
            if let Some(slot) = out.get_mut(base + j) {
                *slot = channel;
            }
        }
    }
    Ok(out)
}

pub struct Demo {
    /// The clip record's name (at most 36 characters, as stored).
    pub clip: String,
    pub names: Vec<String>,
    parents: Vec<i32>,
    frames: Vec<[Local; BONES]>,
    pub fps: f32,
}

impl Demo {
    pub fn load(path: &Path) -> Result<Self, String> {
        let bank = Bank::load(path).map_err(|e| format!("{}: {e}", path.display()))?;
        Self::from_bank(&bank).map_err(|e| format!("{}: {e}", path.display()))
    }

    fn from_bank(bank: &Bank) -> Result<Self, String> {
        let hierarchy = bank.hierarchy().ok_or("no skeleton")?;
        if (hierarchy.bone_count as usize) < BONES {
            return Err(format!(
                "{} bones, expected at least {BONES}",
                hierarchy.bone_count
            ));
        }
        let bytes = bank.bytes();
        let (clip_header, clip) = bank
            .records()
            .iter()
            .find_map(|r| match &r.data {
                RecordData::Clip(c) => Some((&r.header, c)),
                _ => None,
            })
            .ok_or("no animation clip")?;
        let (pose_header, rest_parts) = bank
            .records()
            .iter()
            .filter_map(|r| match &r.data {
                RecordData::Pose(p) => Some((&r.header, &p.parts)),
                _ => None,
            })
            .min_by_key(|(h, _)| h.name != "SK83_TPOSE")
            .ok_or("no rest pose")?;
        for header in [clip_header, pose_header] {
            if header.codec() != Codec::Raw {
                return Err(format!("{} is {:?}, not RAW", header.name, header.codec()));
            }
        }
        let fps = f32::from_bits(clip.fps_bits);
        let count = f32::from_bits(clip.frame_count_bits).round();
        if !(1.0..=240.0).contains(&fps) || !(1.0..=10000.0).contains(&count) {
            return Err(format!(
                "implausible clip timing: {fps} fps, {count} frames"
            ));
        }
        let rest = frame(bytes, rest_parts, hierarchy, 0)?;
        let rest: Vec<Local> = rest
            .iter()
            .map(|c| Local {
                rotation: c.rotation.unwrap_or(Quat::IDENTITY),
                translation: c.translation.unwrap_or(Vec3::ZERO),
            })
            .collect();
        let mut frames = Vec::with_capacity(count as usize);
        for f in 0..count as usize {
            let channels = frame(bytes, &clip.parts, hierarchy, f)?;
            let mut locals = [Local::IDENTITY; BONES];
            for (i, c) in channels.iter().enumerate() {
                locals[i] = rest[i].then(Local {
                    rotation: c.rotation.unwrap_or(Quat::IDENTITY),
                    translation: c.translation.unwrap_or(Vec3::ZERO),
                });
            }
            frames.push(locals);
        }
        Ok(Self {
            clip: clip_header.name.clone(),
            names: hierarchy.bone_names[..BONES].to_vec(),
            parents: hierarchy.parents[..BONES].to_vec(),
            frames,
            fps,
        })
    }

    pub fn duration(&self) -> f32 {
        (self.frames.len().saturating_sub(1)) as f32 / self.fps
    }

    /// Model-space bones at `time` seconds (clamped). Parentless bones hang
    /// from the trajectory, as the engine composes them.
    pub fn sample(&self, time: f32) -> [Mat4; BONES] {
        let at = (time * self.fps).clamp(0.0, (self.frames.len() - 1) as f32);
        let first = at.floor() as usize;
        let second = (first + 1).min(self.frames.len() - 1);
        let alpha = at - first as f32;
        let mut out = [Mat4::IDENTITY; BONES];
        for i in 0..BONES {
            let (a, b) = (self.frames[first][i], self.frames[second][i]);
            let local = Local {
                rotation: a.rotation.slerp(b.rotation, alpha),
                translation: a.translation.lerp(b.translation, alpha),
            }
            .matrix();
            let parent = match self.parents[i] {
                p if p >= 0 => Some(p as usize),
                _ if i > 0 => Some(0),
                _ => None,
            };
            out[i] = parent.map_or(local, |p| out[p] * local);
        }
        out
    }

    pub fn bone(&self, bones: &[Mat4; BONES], name: &str) -> Option<Mat4> {
        self.names.iter().position(|n| n == name).map(|i| bones[i])
    }
}

/// Finds the file holding the menu's clip `name`. Files are named after
/// their clip plus a take number (trickguide_kickflip_01 is in
/// trickguide_kickflip_011.abin), and record names stop at 36 characters,
/// so the direct guesses are checked and a scan settles the rest.
pub struct Library {
    root: PathBuf,
    index: Option<Vec<(String, PathBuf)>>,
}

impl Library {
    pub fn new(root: PathBuf) -> Self {
        Self { root, index: None }
    }

    fn matches(record: &str, clip: &str) -> bool {
        let (record, clip) = (record.to_ascii_lowercase(), clip.to_ascii_lowercase());
        record == clip || (record.len() >= 36 && clip.starts_with(&record))
    }

    pub fn load(&mut self, clip: &str) -> Result<Demo, String> {
        let name = clip.to_ascii_lowercase();
        if name.is_empty() || name.contains(['/', '\\']) || name.contains("..") {
            return Err(format!("invalid clip name {clip:?}"));
        }
        for guess in [format!("{name}.abin"), format!("{name}1.abin")] {
            let path = self.root.join(guess);
            if path.is_file() {
                if let Ok(demo) = Demo::load(&path) {
                    if Self::matches(&demo.clip, &name) {
                        return Ok(demo);
                    }
                }
            }
        }
        let root = &self.root;
        let index = self.index.get_or_insert_with(|| {
            let mut files: Vec<PathBuf> = std::fs::read_dir(root)
                .map(|d| d.filter_map(|e| e.ok().map(|e| e.path())).collect())
                .unwrap_or_default();
            files.retain(|p| {
                p.extension()
                    .is_some_and(|e| e.eq_ignore_ascii_case("abin"))
            });
            files.sort();
            files
                .into_iter()
                .filter_map(|path| {
                    let bank = Bank::load(&path).ok()?;
                    let name = bank.records().iter().find_map(|r| match r.data {
                        RecordData::Clip(_) => Some(r.header.name.clone()),
                        _ => None,
                    })?;
                    Some((name, path))
                })
                .collect()
        });
        let path = index
            .iter()
            .find(|(record, _)| Self::matches(record, &name))
            .map(|(_, path)| path.clone())
            .ok_or_else(|| format!("no demo clip {clip} in {}", self.root.display()))?;
        Demo::load(&path)
    }
}

#[cfg(test)]
#[path = "demo_tests.rs"]
mod tests;
