//! Skinned meshes from the skater GLB the converter writes on the player's
//! machine: the board, and the Skate 3 skater itself. GLB reading follows
//! chasmlol/2010-rust-rewrite-mashup crates/assets/src/skate_board.rs
//! (Apache-2.0); nothing here ships game data.
//!
//! Bones travel as 12 floats each (three basis columns, then the
//! translation), one per entry of `Meshes::used`, in whatever space the
//! caller wants the vertices in. Everyone converting the same game gets the
//! same skin, so another player's bones skin this player's copy of the mesh.
use bevy::math::{Mat4, Vec3, Vec4};
use serde_json::{Map, Value};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const BOARD: usize = 0;
pub const SKATER: usize = 1;

pub struct Surface {
    pub texture: usize,
    pub first_vertex: usize,
    pub vertex_count: usize,
    pub first_index: usize,
    pub index_count: usize,
}

#[derive(Default)]
pub struct Mesh {
    pub positions: Vec<Vec3>,
    pub normals: Vec<Vec3>,
    pub uvs: Vec<[f32; 2]>,
    pub vertex_joints: Vec<[u32; 4]>,
    pub weights: Vec<[f32; 4]>,
    pub indices: Vec<u32>,
    pub surfaces: Vec<Surface>,
    /// Encoded images (PNG) straight from the GLB.
    pub textures: Vec<Vec<u8>>,
}

pub struct Meshes {
    /// Skin joint names, matched to the animated skeleton by name.
    pub joints: Vec<String>,
    pub inverse_binds: Vec<Mat4>,
    /// Skin joints any vertex of either mesh is weighted to, ascending.
    pub used: Vec<usize>,
    pub board: Mesh,
    pub skater: Mesh,
}

static STORE: Mutex<Option<(PathBuf, Result<Arc<Meshes>, String>)>> = Mutex::new(None);

/// The meshes for `assets`, loaded once and shared by the session and the
/// renderer (remote skaters need them without a session of their own).
pub fn shared(assets: &Path) -> Result<Arc<Meshes>, String> {
    let mut store = STORE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((root, result)) = store.as_ref()
        && root == assets
    {
        return result.clone();
    }
    let result = Meshes::load(assets).map(Arc::new);
    *store = Some((assets.to_path_buf(), result.clone()));
    result
}

impl Meshes {
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
        let inverse_binds: Vec<Mat4> = glb
            .floats(index(&skin["inverseBindMatrices"])?)?
            .chunks_exact(16)
            .map(Mat4::from_cols_slice)
            .collect();
        if inverse_binds.len() != joints.len() {
            return Err("skin joints and inverse binds disagree".into());
        }

        let mut board = Mesh::default();
        let mut skater = Mesh::default();
        let primitives = glb.at(&["meshes", "0", "primitives"])?;
        for primitive in primitives.as_array().ok_or("mesh has no primitives")? {
            let material = &glb.json["materials"][index(&primitive["material"])?];
            let name = material["name"].as_str().unwrap_or("");
            let mesh = if name.contains("Skate") { &mut board } else { &mut skater };
            let texture = index(&material["pbrMetallicRoughness"]["baseColorTexture"]["index"])?;
            let image = index(&glb.json["textures"][texture]["source"])?;
            let view = index(&glb.json["images"][image]["bufferView"])?;
            mesh.textures.push(glb.view(view)?.0.to_vec());

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
            if bone_ids.iter().any(|&j| j as usize >= joints.len()) {
                return Err(format!("{name}: vertex weighted to a missing joint"));
            }
            let first_vertex = mesh.positions.len();
            for v in 0..count {
                mesh.positions.push(Vec3::from_slice(&positions[v * 3..]));
                mesh.normals.push(Vec3::from_slice(&normals[v * 3..]));
                mesh.uvs.push([uvs[v * 2], uvs[v * 2 + 1]]);
                mesh.vertex_joints.push(std::array::from_fn(|i| bone_ids[v * 4 + i]));
                mesh.weights.push(std::array::from_fn(|i| weights[v * 4 + i]));
            }
            let first_index = mesh.indices.len();
            let indices = glb.integers(index(&primitive["indices"])?)?;
            if indices.iter().any(|&i| i as usize >= count) {
                return Err(format!("{name}: index outside its vertices"));
            }
            mesh.indices
                .extend(indices.iter().map(|&i| i + first_vertex as u32));
            mesh.surfaces.push(Surface {
                texture: mesh.textures.len() - 1,
                first_vertex,
                vertex_count: count,
                first_index,
                index_count: indices.len(),
            });
        }
        if board.surfaces.is_empty() {
            return Err(format!("{} has no skateboard surfaces", path.display()));
        }
        let mut used: Vec<usize> = [&board, &skater]
            .iter()
            .flat_map(|m| m.vertex_joints.iter().zip(&m.weights))
            .flat_map(|(j, w)| (0..4).filter(|&i| w[i] != 0.0).map(move |i| j[i] as usize))
            .collect();
        used.sort_unstable();
        used.dedup();
        Ok(Self {
            joints,
            inverse_binds,
            used,
            board,
            skater,
        })
    }

    pub fn mesh(&self, which: usize) -> &Mesh {
        if which == SKATER { &self.skater } else { &self.board }
    }

    /// A stable fingerprint of the bone layout, so players with different
    /// conversions never skin each other's meshes with mismatched bones.
    pub fn layout_hash(&self) -> i32 {
        let mut hash: u32 = 0x811c9dc5;
        for &j in &self.used {
            for b in self.joints[j].bytes().chain([0]) {
                hash = (hash ^ u32::from(b)).wrapping_mul(0x0100_0193);
            }
        }
        hash as i32
    }

    /// The bones for `Meshes::used`, from a skeleton's world matrices by name.
    pub fn export_bones(&self, world_bone: impl Fn(&str) -> Option<Mat4>, out: &mut Vec<f32>) {
        out.clear();
        for &j in &self.used {
            let m = world_bone(&self.joints[j]).unwrap_or(Mat4::ZERO);
            for c in [m.x_axis, m.y_axis, m.z_axis, m.w_axis] {
                out.extend_from_slice(&[c.x, c.y, c.z]);
            }
        }
    }

    /// The board's bind-pose box in `bone`'s space, as a matrix taking the
    /// unit cube centred on the origin onto it.
    pub fn board_bounds_in(&self, bone: &str) -> Option<Mat4> {
        let joint = self.joints.iter().position(|n| n == bone)?;
        let to_bone = blender_basis() * self.inverse_binds[joint];
        let mut min = Vec3::splat(f32::INFINITY);
        let mut max = Vec3::splat(f32::NEG_INFINITY);
        for p in &self.board.positions {
            let local = to_bone.transform_point3(*p);
            min = min.min(local);
            max = max.max(local);
        }
        (min.is_finite() && max.is_finite()).then(|| {
            Mat4::from_translation((min + max) * 0.5) * Mat4::from_scale((max - min).max(Vec3::splat(0.01)))
        })
    }

    /// Skins mesh `which` with `bones` (see the module docs) into `out` as
    /// x, y, z, nx, ny, nz, u, v per vertex. Returns false on a short bone set.
    pub fn skin(&self, which: usize, bones: &[f32], out: &mut Vec<f32>) -> bool {
        if bones.len() < self.used.len() * 12 {
            return false;
        }
        let blender = blender_basis();
        let mut matrices = vec![Mat4::ZERO; self.joints.len()];
        for (slot, &j) in self.used.iter().enumerate() {
            let b = &bones[slot * 12..slot * 12 + 12];
            let bone = Mat4::from_cols(
                Vec4::new(b[0], b[1], b[2], 0.0),
                Vec4::new(b[3], b[4], b[5], 0.0),
                Vec4::new(b[6], b[7], b[8], 0.0),
                Vec4::new(b[9], b[10], b[11], 1.0),
            );
            matrices[j] = bone * blender * self.inverse_binds[j];
        }
        let mesh = self.mesh(which);
        out.clear();
        out.reserve(mesh.positions.len() * 8);
        for v in 0..mesh.positions.len() {
            let mut pos = Vec3::ZERO;
            let mut normal = Vec3::ZERO;
            for i in 0..4 {
                let w = mesh.weights[v][i];
                if w == 0.0 {
                    continue;
                }
                let m = matrices[mesh.vertex_joints[v][i] as usize];
                pos += m.transform_point3(mesh.positions[v]) * w;
                normal += m.transform_vector3(mesh.normals[v]) * w;
            }
            let normal = normal.normalize_or(Vec3::Y);
            out.extend_from_slice(&[
                pos.x, pos.y, pos.z, normal.x, normal.y, normal.z, mesh.uvs[v][0], mesh.uvs[v][1],
            ]);
        }
        true
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

#[cfg(test)]
mod tests {
    use super::*;

    fn one_vertex_mesh(joint: u32) -> Mesh {
        Mesh {
            positions: vec![Vec3::new(0.1, 0.2, 0.3)],
            normals: vec![Vec3::Y],
            uvs: vec![[0.5, 0.5]],
            vertex_joints: vec![[joint, 0, 0, 0]],
            weights: vec![[1.0, 0.0, 0.0, 0.0]],
            indices: vec![0, 0, 0],
            surfaces: vec![Surface { texture: 0, first_vertex: 0, vertex_count: 1, first_index: 0, index_count: 3 }],
            textures: vec![vec![]],
        }
    }

    /// Bones exported from a skeleton skin the mesh as that skeleton poses it:
    /// a bone at its bind pose leaves the vertex where it was modelled.
    #[test]
    fn exported_bind_pose_bones_skin_back_to_the_bind_mesh() {
        let inverse_bind = Mat4::from_translation(Vec3::new(-1.0, 0.0, 0.0));
        let meshes = Meshes {
            joints: vec!["UNUSED".into(), "SKATEBOARD_ROOT".into()],
            inverse_binds: vec![Mat4::IDENTITY, inverse_bind],
            used: vec![1],
            board: one_vertex_mesh(1),
            skater: Mesh::default(),
        };
        let bind_world = (blender_basis() * inverse_bind).inverse();
        let mut bones = Vec::new();
        meshes.export_bones(|name| (name == "SKATEBOARD_ROOT").then_some(bind_world), &mut bones);
        assert_eq!(bones.len(), 12);
        let mut out = Vec::new();
        assert!(meshes.skin(BOARD, &bones, &mut out));
        assert!(Vec3::from_slice(&out[..3]).abs_diff_eq(Vec3::new(0.1, 0.2, 0.3), 1e-5), "{out:?}");
        // Moving the bone moves the vertex with it.
        let moved = Mat4::from_translation(Vec3::new(0.0, 2.0, 0.0)) * bind_world;
        meshes.export_bones(|name| (name == "SKATEBOARD_ROOT").then_some(moved), &mut bones);
        assert!(meshes.skin(BOARD, &bones, &mut out));
        assert!(Vec3::from_slice(&out[..3]).abs_diff_eq(Vec3::new(0.1, 2.2, 0.3), 1e-5), "{out:?}");
        // A short bone set is refused rather than read out of bounds.
        assert!(!meshes.skin(BOARD, &bones[..6], &mut out));
        assert_ne!(meshes.layout_hash(), 0);
    }
}
