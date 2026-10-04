//! Skate 3's original trick HUD: the retail APT movie (trick names, sequence
//! and line score, multiplier, line timer, landing quality) run by the engine's
//! own VM. Copied from SK8-ENGINE/skate-3-rust-engine crates/skate-game
//! (apt_*.rs, hud_runtime.rs); only the Bevy rendering is replaced, by a flat
//! draw list Minecraft renders.
pub mod apt_display;
pub mod apt_movie;
pub mod apt_scene;
pub mod apt_text;
pub mod apt_vm;
pub mod hud_runtime;

use apt_vm::Value;
use skate_host::bridge::ScoringHud;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// A trick label as the HUD shows it: each `ID_` component through the
/// movie's language table, unknown ids made readable. From skate-game's
/// scoring_hud.rs (upstream 84142e7).
pub fn localize_trick(label: &str, assets: Option<&apt_text::TextAssets>) -> String {
    if let Some(literal) = label.strip_prefix('#') {
        return literal.to_owned();
    }
    label
        .split_whitespace()
        .map(|part| {
            let text = assets.map(|a| a.localize(part)).unwrap_or_else(|| part.to_owned());
            if text.starts_with("ID_") { humanize_trick_id(&text) } else { text }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn humanize_trick_id(id: &str) -> String {
    let rest = id
        .strip_prefix("ID_TRICK_")
        .or_else(|| id.strip_prefix("ID_"))
        .unwrap_or(id);
    rest.split('_')
        .filter(|word| !word.is_empty())
        .map(|word| {
            let lower = word.to_ascii_lowercase();
            let mut chars = lower.chars();
            match chars.next() {
                None => String::new(),
                Some(first) => first.to_ascii_uppercase().to_string() + chars.as_str(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn composed_names_localize_every_component() {
        let mut assets = apt_text::TextAssets::default();
        assets.language.insert("ID_TRICK_KICKFLIP".into(), "Kickflip".into());
        assets
            .language
            .insert("ID_TRICK_AUTHENTIC_FS_HALFCAB".into(), "FS Half-Cab".into());
        assert_eq!(localize_trick("ID_TRICK_KICKFLIP 360", Some(&assets)), "Kickflip 360");
        assert_eq!(
            localize_trick("ID_TRICK_KICKFLIP ID_TRICK_AUTHENTIC_FS_HALFCAB", Some(&assets)),
            "Kickflip FS Half-Cab"
        );
        assert_eq!(localize_trick("ID_TRICK_POP_SHOVE_IT", None), "Pop Shove It");
    }
}

/// Where the converter's `hud` step writes the trick display.
pub fn root(assets: &Path) -> PathBuf {
    assets.join("private").join("hud")
}

fn input(s: &ScoringHud) -> hud_runtime::Input {
    hud_runtime::Input {
        sequence_score: s.sequence_score,
        line_score: s.line_score,
        sequence_timer: s.sequence_timer,
        line_time: s.line_time,
        line_capacity: s.line_capacity,
        multiplier: s.multiplier,
        clean: s.clean,
        sketchy: s.sketchy,
        stance: s.stance,
        trick_name: s.trick_name.clone(),
        trick_metrics: [
            Value::Text(s.trick_name.clone()),
            Value::Bool(s.stance[0]),
            Value::Bool(s.stance[1]),
            Value::Bool(s.was_bailing),
            Value::Bool(s.new_trick),
        ],
        context_tricks: Vec::new(),
    }
}

pub struct Hud {
    runtime: hud_runtime::Runtime,
    source: serde_json::Value,
    shapes: apt_scene::Shapes,
    /// Texture files (relative to the HUD root) and their sizes; draws refer
    /// to them by index.
    pub textures: Vec<(String, [u32; 2])>,
    index: HashMap<String, usize>,
}

impl Hud {
    pub fn load(assets: &Path, scoring: &ScoringHud) -> Result<Self, String> {
        let root = root(assets);
        let path = root.join("runtime").join("trickdisplay.json");
        let source: serde_json::Value = serde_json::from_slice(
            &std::fs::read(&path).map_err(|e| format!("{}: {e}", path.display()))?,
        )
        .map_err(|e| e.to_string())?;
        let runtime = hud_runtime::Runtime::load(&source, input(scoring))?;
        let shapes: apt_scene::Shapes =
            serde_json::from_value(source["shapes"].clone()).map_err(|e| e.to_string())?;
        let mut files = std::collections::BTreeMap::new();
        for shape in shapes.values().flatten() {
            files.insert(shape.texture.rgba.clone(), [shape.texture.width, shape.texture.height]);
        }
        for font in runtime.bindings.movie.text_assets.fonts.values() {
            files.insert(font.texture.clone(), font.size);
        }
        let textures: Vec<(String, [u32; 2])> = files.into_iter().collect();
        for (file, size) in &textures {
            let len = std::fs::metadata(root.join(file))
                .map_err(|e| format!("HUD {file}: {e}"))?
                .len();
            if len != u64::from(size[0]) * u64::from(size[1]) * 4 {
                return Err(format!("Invalid HUD texture size {file}"));
            }
        }
        let index = textures
            .iter()
            .enumerate()
            .map(|(i, (file, _))| (file.clone(), i))
            .collect();
        Ok(Self {
            runtime,
            source,
            shapes,
            textures,
            index,
        })
    }

    /// A fresh movie, as skate-game does on a map change.
    pub fn reset(&mut self, scoring: &ScoringHud) -> Result<(), String> {
        self.runtime = hud_runtime::Runtime::load(&self.source, input(scoring))?;
        Ok(())
    }

    /// One simulation tick.
    pub fn update(&mut self, scoring: &ScoringHud) -> Result<(), String> {
        self.runtime.update(
            input(scoring),
            scoring.new_trick,
            scoring.modified_trick,
            scoring.close_tricks,
        )
    }

    /// The current frame as a flat list. Per draw: texture index, vertex
    /// count, multiply rgba, add rgba, then x, y, u, v per vertex (triangle
    /// list, in the movie's 1280x720 space).
    pub fn draws(&self, out: &mut Vec<f32>) -> Result<(), String> {
        out.clear();
        let draws = apt_scene::draw(&self.runtime.bindings.movie, &self.runtime.vm, &self.shapes)?;
        for draw in draws {
            let Some(&texture) = self.index.get(&draw.texture) else {
                continue;
            };
            out.push(texture as f32);
            out.push(draw.vertices.len() as f32);
            out.extend_from_slice(&draw.multiply);
            out.extend_from_slice(&draw.add);
            for v in &draw.vertices {
                out.extend_from_slice(&[v.position[0], v.position[1], v.uv[0], v.uv[1]]);
            }
        }
        Ok(())
    }
}
