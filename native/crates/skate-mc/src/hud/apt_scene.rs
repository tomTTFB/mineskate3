//! Retained movie traversal. All geometry and glyphs come from the owned APT.
use super::{apt_movie::Movie, apt_vm::Vm};
use serde::Deserialize;
use std::collections::BTreeMap;

#[derive(Clone, Deserialize)]
pub struct Vertex {
    pub position: [f32; 2],
    #[serde(default)]
    pub uv: [f32; 2],
}
#[derive(Clone, Deserialize)]
pub struct Texture {
    pub width: u32,
    pub height: u32,
    pub rgba: String,
}
#[derive(Clone, Deserialize)]
pub struct Shape {
    /// None for a solid fill, drawn with the multiply colour alone.
    pub texture: Option<Texture>,
    pub triangles: Vec<[Vertex; 3]>,
    pub color: [f32; 4],
}
pub type Shapes = BTreeMap<i32, Vec<Shape>>;
/// Line advance of multi-line fields, in font heights.
const LEADING: f32 = 1.2;
pub struct Draw {
    /// Texture file; empty for a solid fill.
    pub texture: String,
    pub size: [u32; 2],
    pub vertices: Vec<Vertex>,
    pub multiply: [f32; 4],
    pub add: [f32; 4],
    /// A controller button the engine draws natively (button_item2 sets the
    /// clip's `_test` to its name); `vertices` is then the button's quad.
    pub button: Option<String>,
}
fn compose(a: [f32; 6], b: [f32; 6]) -> [f32; 6] {
    [
        a[0] * b[0] + a[2] * b[1],
        a[1] * b[0] + a[3] * b[1],
        a[0] * b[2] + a[2] * b[3],
        a[1] * b[2] + a[3] * b[3],
        a[0] * b[4] + a[2] * b[5] + a[4],
        a[1] * b[4] + a[3] * b[5] + a[5],
    ]
}
fn transform(m: [f32; 6], v: &Vertex) -> Vertex {
    Vertex {
        position: apply(m, v.position),
        uv: v.uv,
    }
}
fn apply(m: [f32; 6], p: [f32; 2]) -> [f32; 2] {
    [
        m[0] * p[0] + m[2] * p[1] + m[4],
        m[1] * p[0] + m[3] * p[1] + m[5],
    ]
}
/// A clip's matrix in its parent: the authored placement until the script
/// changes scale or rotation, then rebuilt from the display properties.
pub fn local_matrix(movie: &Movie, vm: &Vm, id: usize) -> [f32; 6] {
    let instance = &movie.instances[&id];
    let sx = vm.get(id, "_xscale").number();
    let sy = vm.get(id, "_yscale").number();
    let rotation = vm.get(id, "_rotation").number();
    let x = vm.get(id, "_x").number() as f32;
    let y = vm.get(id, "_y").number() as f32;
    let authored = instance
        .placement
        .as_ref()
        .filter(|_| instance.decomposed == Some([sx, sy, rotation]));
    if let Some(p) = authored {
        let mut m = p.matrix;
        m[4] = x;
        m[5] = y;
        return m;
    }
    let (s, c) = rotation.to_radians().sin_cos();
    let (sx, sy) = (sx / 100.0, sy / 100.0);
    [
        (c * sx) as f32,
        (s * sx) as f32,
        (-s * sy) as f32,
        (c * sy) as f32,
        x,
        y,
    ]
}
/// Timeline children, then script-attached ones: Flash keeps authored
/// depths far below the depths attachMovie and createEmptyMovieClip use.
fn children(movie: &Movie, id: usize) -> Vec<usize> {
    let instance = &movie.instances[&id];
    instance
        .children
        .values()
        .chain(instance.dynamic.values())
        .copied()
        .filter(|c| movie.instances.contains_key(c))
        .collect()
}
/// Content bounds in the clip's own space: shapes, text boxes and visible
/// children through their matrices. None for an empty clip.
pub fn local_bounds(movie: &Movie, vm: &Vm, id: usize) -> Option<[f32; 4]> {
    let instance = movie.instances.get(&id)?;
    let character = &movie.characters[&instance.character];
    let mut b: Option<[f32; 4]> = None;
    let mut add = |p: [f32; 2]| {
        let v = b.get_or_insert([p[0], p[1], p[0], p[1]]);
        v[0] = v[0].min(p[0]);
        v[1] = v[1].min(p[1]);
        v[2] = v[2].max(p[0]);
        v[3] = v[3].max(p[1]);
    };
    if character.type_name == "shape" {
        for shape in movie.shapes.get(&character.id).into_iter().flatten() {
            for v in shape.triangles.iter().flatten() {
                add(v.position);
            }
        }
    } else if character.text.is_some()
        && let Some(r) = character.bounds
    {
        add([r[0], r[1]]);
        add([r[2], r[3]]);
    }
    for child in children(movie, id) {
        if !vm.get(child, "_visible").truth() {
            continue;
        }
        if let Some(r) = local_bounds(movie, vm, child) {
            let m = local_matrix(movie, vm, child);
            for p in [[r[0], r[1]], [r[2], r[1]], [r[2], r[3]], [r[0], r[3]]] {
                add(apply(m, p));
            }
        }
    }
    b
}
pub fn draw(movie: &Movie, vm: &Vm, shapes: &Shapes) -> Result<Vec<Draw>, String> {
    let mut draws = Vec::new();
    visit(
        movie,
        vm,
        shapes,
        movie.root,
        [1., 0., 0., 1., 0., 0.],
        [1.; 4],
        [0.; 4],
        &mut draws,
    )?;
    Ok(draws)
}
#[allow(clippy::too_many_arguments)]
fn visit(
    movie: &Movie,
    vm: &Vm,
    shapes: &Shapes,
    id: usize,
    parent: [f32; 6],
    pm: [f32; 4],
    pa: [f32; 4],
    out: &mut Vec<Draw>,
) -> Result<(), String> {
    if !vm.get(id, "_visible").truth() {
        return Ok(());
    }
    let instance = &movie.instances[&id];
    let matrix = compose(parent, local_matrix(movie, vm, id));
    if matrix.iter().any(|x| !x.is_finite()) {
        return Err(format!(
            "Nonfinite HUD transform on character {}",
            instance.character
        ));
    }
    let color = instance
        .placement
        .as_ref()
        .map_or([255, 255, 255, 255, 0, 0, 0, 0], |p| p.color);
    let rgba = [1, 2, 3, 0];
    let mut multiply = std::array::from_fn(|i| pm[i] * color[rgba[i]] as f32 / 255.);
    let mut add = std::array::from_fn(|i| pa[i] + pm[i] * color[4 + rgba[i]] as f32 / 255.);
    let alpha = (vm.get(id, "_alpha").number() as f32 / 100.).clamp(0., 1.);
    multiply[3] *= alpha;
    add[3] *= alpha;
    if multiply[3] <= 0. && add[3] <= 0. {
        return Ok(());
    }
    if let super::apt_vm::Value::Text(button) = vm.get(id, "_test") {
        if let Some(r) = local_bounds(movie, vm, id) {
            let corners = [[r[0], r[1]], [r[2], r[1]], [r[2], r[3]], [r[0], r[3]]];
            out.push(Draw {
                texture: String::new(),
                size: [1, 1],
                vertices: [0, 1, 2, 0, 2, 3]
                    .map(|i| Vertex {
                        position: apply(matrix, corners[i]),
                        uv: [0., 0.],
                    })
                    .to_vec(),
                multiply,
                add,
                button: Some(button),
            });
        }
        return Ok(());
    }
    let character = &movie.characters[&instance.character];
    if character.type_name == "shape" {
        for shape in shapes
            .get(&character.id)
            .ok_or("Missing original shape geometry")?
        {
            let (texture, size) = shape
                .texture
                .as_ref()
                .map_or((String::new(), [1, 1]), |t| (t.rgba.clone(), [t.width, t.height]));
            out.push(Draw {
                texture,
                size,
                vertices: shape
                    .triangles
                    .iter()
                    .flatten()
                    .map(|v| transform(matrix, v))
                    .collect(),
                multiply: std::array::from_fn(|i| multiply[i] * shape.color[i]),
                add,
                button: None,
            });
        }
    } else if let Some(text) = &character.text {
        text_draws(movie, vm, id, text, matrix, multiply, add, out)?;
    }
    for child in children(movie, id) {
        visit(movie, vm, shapes, child, matrix, multiply, add, out)?;
    }
    Ok(())
}
#[allow(clippy::too_many_arguments)]
fn text_draws(
    movie: &Movie,
    vm: &Vm,
    id: usize,
    text: &serde_json::Value,
    matrix: [f32; 6],
    multiply: [f32; 4],
    add: [f32; 4],
    out: &mut Vec<Draw>,
) -> Result<(), String> {
    let character = &movie.characters[&movie.instances[&id].character];
    let font = &movie.text_assets.fonts
        [&(text["font_id"].as_i64().ok_or("Invalid text font")? as i32)];
    // Native shadow text uses a black atlas pass followed by the sharp
    // futuraheavy glyphs translated +1 in text X (825D6B68/82CA1FD8).
    let passes = if let Some(foreground) = font.foreground {
        vec![
            (font, true, 0.),
            (&movie.text_assets.fonts[&foreground], false, 1.),
        ]
    } else {
        vec![(font, false, 0.)]
    };
    let authored = text["font_height"].as_f64().ok_or("Invalid text height")? as f32;
    let height = match vm.get(id, "_fontSize") {
        super::apt_vm::Value::Number(size) if size > 0.0 => size as f32,
        _ => authored,
    };
    let bounds = character.bounds.ok_or("Missing text bounds")?;
    let value = vm.get(id, "_displayText").text();
    let wrap = (text["word_wrap"].as_bool() == Some(true)
        && text["multiline"].as_bool() == Some(true))
    .then_some(bounds[2] - bounds[0]);
    let alignment = text["alignment"].as_u64().unwrap_or(0);
    let autosize = vm.get(id, "autoSize").text();
    let alignment = if autosize == "left" { 0 } else { alignment };
    let argb = match vm.get(id, "textColor") {
        super::apt_vm::Value::Number(rgb) => 0xff00_0000 | (rgb as u32 & 0xff_ffff),
        _ => u32::from_str_radix(
            text["color_argb"]
                .as_str()
                .ok_or("Missing text color")?
                .trim_start_matches('#'),
            16,
        )
        .map_err(|e| e.to_string())?,
    };
    let color = [
        ((argb >> 16) & 255) as f32 / 255.,
        ((argb >> 8) & 255) as f32 / 255.,
        (argb & 255) as f32 / 255.,
        (argb >> 24) as f32 / 255.,
    ];
    for (font, shadow, advance_x) in passes {
        let sx = font.scale[0] * height;
        let sy = font.scale[1] * height;
        let mut vertices = Vec::new();
        for (row, line) in super::apt_text::lines(font, &value, height, wrap)
            .iter()
            .enumerate()
        {
            let width = font.width(line, height);
            let mut x = bounds[0]
                + advance_x
                + font.offset[0] * sx
                + match alignment {
                    1 => bounds[2] - bounds[0] - width,
                    2 => (bounds[2] - bounds[0] - width) * 0.5,
                    _ => 0.,
                };
            let y = bounds[1] + font.offset[1] * sy + row as f32 * height * LEADING;
            for c in line.chars() {
                if let Some(g) = font.glyph(c) {
                    let x0 = x + g.x_offset * sx;
                    let y0 = y + (font.ascent - g.y_offset) * sy;
                    let x1 = x0 + g.width * sx;
                    let y1 = y0 + g.height * sy;
                    let [u0, v0, u1, v1] = g.atlas_bounds;
                    let points = [[x0, y0], [x1, y0], [x1, y1], [x0, y1]];
                    let uvs = [[u0, v0], [u1, v0], [u1, v1], [u0, v1]];
                    for i in [0, 1, 2, 0, 2, 3] {
                        vertices.push(transform(
                            matrix,
                            &Vertex {
                                position: points[i],
                                uv: [
                                    uvs[i][0] / font.size[0] as f32,
                                    uvs[i][1] / font.size[1] as f32,
                                ],
                            },
                        ));
                    }
                    x = g.x_advance.mul_add(sx, x);
                }
            }
        }
        if !vertices.is_empty() {
            out.push(Draw {
                texture: font.texture.clone(),
                size: font.size,
                vertices,
                multiply: std::array::from_fn(|i| {
                    if shadow && i < 3 {
                        0.
                    } else {
                        multiply[i] * color[i]
                    }
                }),
                add,
                button: None,
            });
        }
    }
    Ok(())
}
