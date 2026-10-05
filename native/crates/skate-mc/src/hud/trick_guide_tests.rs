//! Runs the original Trick Guide from a converted install and rasterises its
//! draw list, so the screen can be checked without the game. Needs owned
//! data, so it is opt-in:
//! TRICKGUIDE_DIR=<assets>/private/trickguide TRICKGUIDE_OUT=<dir>
//! cargo test -p skate-mc trick_guide -- --ignored --nocapture
use super::*;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

const W: usize = 1280;
const H: usize = 720;

struct Canvas {
    pixels: Vec<[f32; 4]>,
    textures: HashMap<String, (Vec<u8>, [u32; 2])>,
    root: PathBuf,
}
impl Canvas {
    fn new(root: &Path) -> Self {
        Self {
            pixels: vec![[0.18, 0.2, 0.22, 1.0]; W * H],
            textures: HashMap::new(),
            root: root.into(),
        }
    }
    fn texture(&mut self, name: &str, size: [u32; 2]) -> Option<&(Vec<u8>, [u32; 2])> {
        if !self.textures.contains_key(name) {
            let bytes = std::fs::read(self.root.join(name)).ok()?;
            self.textures.insert(name.into(), (bytes, size));
        }
        self.textures.get(name)
    }
    fn draw(&mut self, d: &apt_scene::Draw) {
        let solid = d.texture.is_empty();
        let tex = if solid { None } else { self.texture(&d.texture, d.size).cloned() };
        if !solid && tex.is_none() {
            return;
        }
        for tri in d.vertices.chunks_exact(3) {
            let p: Vec<[f32; 2]> = tri.iter().map(|v| v.position).collect();
            let area = (p[1][0] - p[0][0]) * (p[2][1] - p[0][1]) - (p[2][0] - p[0][0]) * (p[1][1] - p[0][1]);
            if area.abs() < 1e-6 {
                continue;
            }
            let x0 = p.iter().map(|q| q[0]).fold(f32::MAX, f32::min).floor().max(0.0) as usize;
            let x1 = p.iter().map(|q| q[0]).fold(f32::MIN, f32::max).ceil().min(W as f32 - 1.0) as usize;
            let y0 = p.iter().map(|q| q[1]).fold(f32::MAX, f32::min).floor().max(0.0) as usize;
            let y1 = p.iter().map(|q| q[1]).fold(f32::MIN, f32::max).ceil().min(H as f32 - 1.0) as usize;
            for y in y0..=y1 {
                for x in x0..=x1 {
                    let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
                    let w0 = ((p[1][0] - px) * (p[2][1] - py) - (p[2][0] - px) * (p[1][1] - py)) / area;
                    let w1 = ((p[2][0] - px) * (p[0][1] - py) - (p[0][0] - px) * (p[2][1] - py)) / area;
                    let w2 = 1.0 - w0 - w1;
                    if w0 < 0.0 || w1 < 0.0 || w2 < 0.0 {
                        continue;
                    }
                    let mut c = if d.button.is_some() {
                        [0.85, 0.85, 0.85, 1.0]
                    } else if let Some((bytes, size)) = &tex {
                        let u = tri[0].uv[0] * w0 + tri[1].uv[0] * w1 + tri[2].uv[0] * w2;
                        let v = tri[0].uv[1] * w0 + tri[1].uv[1] * w1 + tri[2].uv[1] * w2;
                        let tx = ((u * size[0] as f32) as i64).clamp(0, size[0] as i64 - 1) as usize;
                        let ty = ((v * size[1] as f32) as i64).clamp(0, size[1] as i64 - 1) as usize;
                        let i = (ty * size[0] as usize + tx) * 4;
                        std::array::from_fn(|k| bytes.get(i + k).copied().unwrap_or(0) as f32 / 255.0)
                    } else {
                        [1.0; 4]
                    };
                    for k in 0..4 {
                        c[k] = (c[k] * d.multiply[k] + d.add[k]).clamp(0.0, 1.0);
                    }
                    let dst = &mut self.pixels[y * W + x];
                    for k in 0..3 {
                        dst[k] = c[k] * c[3] + dst[k] * (1.0 - c[3]);
                    }
                }
            }
        }
    }
    fn save(&self, path: &Path) {
        let bytes: Vec<u8> = self
            .pixels
            .iter()
            .flat_map(|p| [p[0], p[1], p[2]].map(|c| (c * 255.0) as u8))
            .collect();
        let mut out = format!("P6\n{W} {H}\n255\n").into_bytes();
        out.extend(bytes);
        std::fs::write(path, out).unwrap();
    }
}

fn render(guide: &TrickGuide, root: &Path, out: &Path, name: &str) {
    let mut canvas = Canvas::new(root);
    let draws = guide.draws().unwrap();
    for d in &draws {
        canvas.draw(d);
    }
    canvas.save(&out.join(format!("{name}.ppm")));
    println!("{name}: {} draws, clip {:?}", draws.len(), guide.clip());
}

#[test]
#[ignore]
fn trick_guide_renders_from_owned_data() {
    let (Ok(dir), Ok(out)) = (std::env::var("TRICKGUIDE_DIR"), std::env::var("TRICKGUIDE_OUT")) else {
        return;
    };
    let (dir, out) = (PathBuf::from(dir), PathBuf::from(out));
    std::fs::create_dir_all(&out).unwrap();
    let read = |p: &str| -> serde_json::Value {
        serde_json::from_slice(&std::fs::read(dir.join(p)).unwrap()).unwrap()
    };
    let mut guide = TrickGuide::load(&read("runtime/trickguide.json"), &read("menu.json"), true)
        .unwrap_or_else(|e| panic!("load: {e}"));
    render(&guide, &dir, &out, "00_loaded");
    for _ in 0..30 {
        guide.update().unwrap_or_else(|e| panic!("update: {e}"));
    }
    render(&guide, &dir, &out, "01_intro");
    for (step, nav) in [
        ("02_down", Nav::Down),
        ("03_select", Nav::Select),
        ("04_select", Nav::Select),
        ("05_down", Nav::Down),
        ("06_back", Nav::Back),
    ] {
        guide.input(nav).unwrap_or_else(|e| panic!("{step}: {e}"));
        for _ in 0..10 {
            guide.update().unwrap();
        }
        render(&guide, &dir, &out, step);
    }
    println!("unknown natives: {:?}", guide.bindings.unknown);
}

/// Every gesture_item graphic in a labelled grid, to name the input values.
#[test]
#[ignore]
fn trick_guide_gesture_sheet() {
    let (Ok(dir), Ok(out)) = (std::env::var("TRICKGUIDE_DIR"), std::env::var("TRICKGUIDE_OUT")) else {
        return;
    };
    let (dir, out) = (PathBuf::from(dir), PathBuf::from(out));
    let read = |p: &str| -> serde_json::Value {
        serde_json::from_slice(&std::fs::read(dir.join(p)).unwrap()).unwrap()
    };
    let runtime = read("runtime/trickguide.json");
    let mut guide = TrickGuide::load(&runtime, &read("menu.json"), true).unwrap();
    let root = guide.bindings.movie.root;
    // Hide the screen; draw only the attached gestures.
    for child in guide.bindings.movie.instances[&root].children.values().copied().collect::<Vec<_>>() {
        guide.vm.set(child, "_visible", Value::Bool(false)).unwrap();
    }
    let mut names: Vec<String> = runtime["exports"]["4"]
        .as_object()
        .unwrap()
        .keys()
        .filter(|k| k.starts_with("g_"))
        .cloned()
        .collect();
    names.sort();
    for (i, name) in names.iter().enumerate() {
        let clip = guide
            .bindings
            .movie
            .method(&mut guide.vm, root, "attachMovie", &[
                Value::Text(name.clone()),
                Value::Text(format!("sheet{i}")),
                Value::Number(i as f64 + 100.0),
            ])
            .unwrap();
        let Some(Value::Object(clip)) = clip else { panic!("{name}") };
        let (col, row) = (i % 10, i / 10);
        guide.vm.set(clip, "_x", Value::Number(70.0 + col as f64 * 120.0)).unwrap();
        guide.vm.set(clip, "_y", Value::Number(70.0 + row as f64 * 120.0)).unwrap();
        println!("{row},{col}: {name}");
    }
    render(&guide, &dir, &out, "gestures");
}
