//! The skateboard mesh from the skater GLB the converter writes on the
//! player's machine. GLB reading follows chasmlol/2010-rust-rewrite-mashup
//! crates/assets/src/skate_board.rs (Apache-2.0); nothing here ships game data.
use bevy::math::{Mat4, Vec3, Vec4};
use serde_json::{Map, Value};
use std::path::Path;

pub struct Surface {
    pub texture: usize,
    pub first_vertex: usize,
    pub vertex_count: usize,
    pub first_index: usize,
    pub index_count: usize,
}

pub struct Board {
    /// Skin joint names, matched to the animated skeleton by name.
    pub joints: Vec<String>,
    pub inverse_binds: Vec<Mat4>,
    pub positions: Vec<Vec3>,
    pub normals: Vec<Vec3>,
    pub uvs: Vec<[f32; 2]>,
    pub vertex_joints: Vec<[u32; 4]>,
    pub weights: Vec<[f32; 4]>,
    pub indices: Vec<u32>,
    pub surfaces: Vec<Surface>,
    /// Encoded images (PNG in practice) straight from the GLB.
    pub textures: Vec<Vec<u8>>,
}

impl Board {
    pub fn load(assets: &Path) -> Result<Self, String> {
        let path = assets.join("private").join("skater.glb");
        let bytes =
            std::fs::read(&path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        let glb = Glb::parse(&bytes)?;
        let skin = glb.at(&["skins", "0"])?;
        let joint_nodes = skin["joints"].as_array().ok_or("skin has no joints")?;
        let joints = joint_nodes
            .iter()
            .map(|joint| {
                let node = index(joint)?;
                glb.json["nodes"][node]["name"]
                    .as_str()
                    .map(str::to_owned)
                    .ok_or_else(|| format!("joint node {node} has no name"))
            })
            .collect::<Result<Vec<_>, String>>()?;
        let inverse_binds = glb
            .floats(index(&skin["inverseBindMatrices"])?)?
            .chunks_exact(16)
            .map(|m| Mat4::from_cols_slice(m))
            .collect();

        let mut board = Board {
            joints,
            inverse_binds,
            positions: vec![],
            normals: vec![],
            uvs: vec![],
            vertex_joints: vec![],
            weights: vec![],
            indices: vec![],
            surfaces: vec![],
            textures: vec![],
        };
        let primitives = glb.at(&["meshes", "0", "primitives"])?;
        for primitive in primitives.as_array().ok_or("mesh has no primitives")? {
            let material = &glb.json["materials"][index(&primitive["material"])?];
            let name = material["name"].as_str().unwrap_or("");
            if !name.contains("Skate") {
                continue;
            }
            let texture = index(&material["pbrMetallicRoughness"]["baseColorTexture"]["index"])?;
            let image = index(&glb.json["textures"][texture]["source"])?;
            let view = index(&glb.json["images"][image]["bufferView"])?;
            board.textures.push(glb.view(view)?.0.to_vec());

            let attributes = &primitive["attributes"];
            let positions = glb.floats(index(&attributes["POSITION"])?)?;
            let normals = glb.floats(index(&attributes["NORMAL"])?)?;
            let uvs = glb.floats(index(&attributes["TEXCOORD_0"])?)?;
            let bone_ids = glb.integers(index(&attributes["JOINTS_0"])?)?;
            let weights = glb.floats(index(&attributes["WEIGHTS_0"])?)?;
            let count = positions.len() / 3;
            if normals.len() != count * 3
                || uvs.len() != count * 2
                || bone_ids.len() != count * 4
                || weights.len() != count * 4
            {
                return Err(format!("{name}: vertex streams disagree"));
            }
            let first_vertex = board.positions.len();
            for v in 0..count {
                board.positions.push(Vec3::from_slice(&positions[v * 3..]));
                board.normals.push(Vec3::from_slice(&normals[v * 3..]));
                board.uvs.push([uvs[v * 2], uvs[v * 2 + 1]]);
                board.vertex_joints.push(std::array::from_fn(|i| bone_ids[v * 4 + i]));
                board.weights.push(std::array::from_fn(|i| weights[v * 4 + i]));
            }
            let first_index = board.indices.len();
            let indices = glb.integers(index(&primitive["indices"])?)?;
            if indices.iter().any(|&i| i as usize >= count) {
                return Err(format!("{name}: index outside its vertices"));
            }
            board
                .indices
                .extend(indices.iter().map(|&i| i + first_vertex as u32));
            board.surfaces.push(Surface {
                texture: board.textures.len() - 1,
                first_vertex,
                vertex_count: count,
                first_index,
                index_count: indices.len(),
            });
        }
        if board.surfaces.is_empty() {
            return Err(format!("{} has no skateboard surfaces", path.display()));
        }
        Ok(board)
    }

    /// The board's bind-pose box in `bone`'s space, as a matrix taking the
    /// unit cube centred on the origin onto it.
    pub fn bounds_in(&self, bone: &str) -> Option<Mat4> {
        let joint = self.joints.iter().position(|n| n == bone)?;
        let to_bone = blender_basis() * self.inverse_binds[joint];
        let mut min = Vec3::splat(f32::INFINITY);
        let mut max = Vec3::splat(f32::NEG_INFINITY);
        for p in &self.positions {
            let local = to_bone.transform_point3(*p);
            min = min.min(local);
            max = max.max(local);
        }
        (min.is_finite() && max.is_finite()).then(|| {
            Mat4::from_translation((min + max) * 0.5) * Mat4::from_scale((max - min).max(Vec3::splat(0.01)))
        })
    }

    /// Skins every vertex into `out` as x, y, z, nx, ny, nz, u, v.
    /// `world_bone` gives a skeleton bone's world matrix by name.
    pub fn skin(&self, world_bone: impl Fn(&str) -> Option<Mat4>, out: &mut Vec<f32>) {
        let blender = blender_basis();
        let matrices: Vec<Mat4> = self
            .joints
            .iter()
            .zip(&self.inverse_binds)
            .map(|(name, inverse)| {
                world_bone(name).map_or(Mat4::ZERO, |m| m * blender * *inverse)
            })
            .collect();
        out.clear();
        out.reserve(self.positions.len() * 8);
        for v in 0..self.positions.len() {
            let mut pos = Vec3::ZERO;
            let mut normal = Vec3::ZERO;
            for i in 0..4 {
                let w = self.weights[v][i];
                if w == 0.0 {
                    continue;
                }
                let m = matrices
                    .get(self.vertex_joints[v][i] as usize)
                    .copied()
                    .unwrap_or(Mat4::ZERO);
                pos += m.transform_point3(self.positions[v]) * w;
                normal += m.transform_vector3(self.normals[v]) * w;
            }
            let normal = normal.normalize_or(Vec3::Y);
            out.extend_from_slice(&[
                pos.x,
                pos.y,
                pos.z,
                normal.x,
                normal.y,
                normal.z,
                self.uvs[v][0],
                self.uvs[v][1],
            ]);
        }
    }
}

/// The GLB carries Blender's bone basis (the mashup's `rb`).
fn blender_basis() -> Mat4 {
    Mat4::from_cols(Vec4::X, -Vec4::Z, Vec4::Y, Vec4::W)
}

fn index(value: &Value) -> Result<usize, String> {
    value
        .as_u64()
        .map(|v| v as usize)
        .ok_or_else(|| format!("expected an index, found {value}"))
}

struct Glb<'a> {
    json: Value,
    blob: &'a [u8],
}

impl<'a> Glb<'a> {
    fn parse(bytes: &'a [u8]) -> Result<Self, String> {
        let word = |at: usize| -> Result<usize, String> {
            bytes
                .get(at..at + 4)
                .map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]) as usize)
                .ok_or_else(|| "truncated GLB".to_owned())
        };
        if bytes.get(..4) != Some(b"glTF") {
            return Err("skater.glb is not a GLB file".into());
        }
        let json_len = word(12)?;
        let json_bytes = bytes.get(20..20 + json_len).ok_or("truncated GLB JSON")?;
        let json = serde_json::from_slice(json_bytes).map_err(|e| e.to_string())?;
        let blob_len = word(20 + json_len)?;
        let blob_start = 28 + json_len;
        let blob = bytes
            .get(blob_start..blob_start + blob_len)
            .ok_or("truncated GLB binary chunk")?;
        Ok(Self { json, blob })
    }

    fn at(&self, path: &[&str]) -> Result<&Value, String> {
        let mut value = &self.json;
        for key in path {
            value = match key.parse::<usize>() {
                Ok(i) => &value[i],
                Err(_) => &value[*key],
            };
        }
        if value.is_null() {
            return Err(format!("skater.glb has no {}", path.join("/")));
        }
        Ok(value)
    }

    fn view(&self, view: usize) -> Result<(&'a [u8], Option<usize>), String> {
        let view = &self.json["bufferViews"][view];
        let offset = view["byteOffset"].as_u64().unwrap_or(0) as usize;
        let length = index(&view["byteLength"])?;
        let bytes = self
            .blob
            .get(offset..offset + length)
            .ok_or("buffer view outside the GLB")?;
        Ok((bytes, view["byteStride"].as_u64().map(|s| s as usize)))
    }

    fn components(&self, accessor: usize) -> Result<Vec<f64>, String> {
        let accessor: &Map<String, Value> = self.json["accessors"][accessor]
            .as_object()
            .ok_or("missing accessor")?;
        let count = index(&accessor["count"])?;
        let width = match accessor["type"].as_str() {
            Some("SCALAR") => 1,
            Some("VEC2") => 2,
            Some("VEC3") => 3,
            Some("VEC4") => 4,
            Some("MAT4") => 16,
            other => return Err(format!("unsupported accessor type {other:?}")),
        };
        let (size, read): (usize, fn(&[u8]) -> f64) = match accessor["componentType"].as_u64() {
            Some(5121) => (1, |b| f64::from(b[0])),
            Some(5123) => (2, |b| f64::from(u16::from_le_bytes([b[0], b[1]]))),
            Some(5125) => (4, |b| f64::from(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))),
            Some(5126) => (4, |b| f64::from(f32::from_le_bytes([b[0], b[1], b[2], b[3]]))),
            other => return Err(format!("unsupported component type {other:?}")),
        };
        let (bytes, stride) = self.view(index(&accessor["bufferView"])?)?;
        let start = accessor
            .get("byteOffset")
            .and_then(Value::as_u64)
            .unwrap_or(0) as usize;
        let stride = stride.unwrap_or(size * width);
        let mut out = Vec::with_capacity(count * width);
        for element in 0..count {
            for component in 0..width {
                let at = start + element * stride + component * size;
                let field = bytes.get(at..at + size).ok_or("accessor outside its view")?;
                out.push(read(field));
            }
        }
        Ok(out)
    }

    fn floats(&self, accessor: usize) -> Result<Vec<f32>, String> {
        Ok(self.components(accessor)?.into_iter().map(|v| v as f32).collect())
    }

    fn integers(&self, accessor: usize) -> Result<Vec<u32>, String> {
        Ok(self.components(accessor)?.into_iter().map(|v| v as u32).collect())
    }
}
