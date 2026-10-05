//! JNI for the Trick Guide screen. It runs on the calling (render) thread:
//! the guide is a menu, independent of the skate session.
use crate::demo::{self, Demo, Library};
use crate::hud::{
    self,
    trick_guide::{Nav, TrickGuide},
};
use crate::retarget;
use bevy::math::{Mat4, Vec3};
use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jobjectArray, jstring};
use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

pub struct Guide {
    guide: TrickGuide,
    textures: Vec<(String, [u32; 2])>,
    index: HashMap<String, usize>,
    error: String,
    draws: Vec<f32>,
    player: Player,
}

impl Guide {
    /// `root` is <assets>/private/trickguide as the converter writes it.
    pub fn open(root: &Path, regular: bool) -> Result<Self, String> {
        let read = |name: &str| -> Result<serde_json::Value, String> {
            let path = root.join(name);
            serde_json::from_slice(
                &std::fs::read(&path).map_err(|e| format!("{}: {e}", path.display()))?,
            )
            .map_err(|e| format!("{}: {e}", path.display()))
        };
        let runtime = read("runtime/trickguide.json")?;
        if runtime["version"].as_u64() != Some(2) {
            return Err("Trick guide data predates this mod; convert it again".into());
        }
        let guide = TrickGuide::load(&runtime, &read("menu.json")?, regular)?;
        let mut files = BTreeMap::new();
        let movie = &guide.bindings.movie;
        for texture in movie
            .shapes
            .values()
            .flatten()
            .filter_map(|s| s.texture.as_ref())
        {
            files.insert(texture.rgba.clone(), [texture.width, texture.height]);
        }
        for font in movie.text_assets.fonts.values() {
            files.insert(font.texture.clone(), font.size);
        }
        let textures: Vec<_> = files.into_iter().collect();
        let index = textures
            .iter()
            .enumerate()
            .map(|(i, (file, _))| (file.clone(), i))
            .collect();
        Ok(Self {
            guide,
            textures,
            index,
            error: String::new(),
            draws: Vec::new(),
            player: Player::new(root.join("clips")),
        })
    }
    fn run(&mut self, f: impl FnOnce(&mut TrickGuide) -> Result<(), String>) -> bool {
        if !self.error.is_empty() {
            return false;
        }
        match f(&mut self.guide) {
            Ok(()) => true,
            Err(e) => {
                self.error = e;
                false
            }
        }
    }
}

/// Shown before any trick is highlighted.
const FIRST_CLIP: &str = "trickguide_ollie_01";
/// Pause on the landing before the clip repeats.
const HOLD_SECONDS: f32 = 0.8;
/// Camera: beside the skater on the side they face, a little above.
const CAMERA_DISTANCE: f32 = 4.2;
const CAMERA_HEIGHT: f32 = 0.9;
const CAMERA_FOLLOW: f32 = 4.0;

/// `guideDemo` output: see `Player::write`.
pub mod layout {
    pub const EYE: usize = 1;
    pub const TARGET: usize = 4;
    pub const PARTS: usize = 7;
    pub const BOARD: usize = PARTS + 16 * super::retarget::PARTS;
    pub const BONE_COUNT: usize = BOARD + 16;
    pub const BONES: usize = BONE_COUNT + 1;
}

/// Plays the highlighted entry's demo on a loop with a follow camera.
struct Player {
    library: Library,
    clip: String,
    demo: Option<Demo>,
    time: f32,
    side: Vec3,
    target: Vec3,
    failed: bool,
}

impl Player {
    fn new(clips: PathBuf) -> Self {
        Self {
            library: Library::new(clips),
            clip: String::new(),
            demo: None,
            time: 0.0,
            side: Vec3::Z,
            target: Vec3::ZERO,
            failed: false,
        }
    }

    fn hips(demo: &Demo, bones: &[Mat4; demo::BONES]) -> Vec3 {
        demo.bone(bones, "HIPS")
            .map_or(Vec3::ZERO, |m| m.w_axis.truncate())
    }

    fn select(&mut self, clip: &str) {
        if clip == self.clip {
            return;
        }
        self.clip = clip.to_owned();
        self.time = 0.0;
        match self.library.load(clip) {
            Ok(demo) => {
                // The camera watches from the side the skater faces, across
                // the clip's line of travel.
                let start = demo.sample(0.0);
                let end = demo.sample(demo.duration());
                let at = |bones: &[Mat4; demo::BONES], n: &str| {
                    demo.bone(bones, n)
                        .map_or(Vec3::ZERO, |m| m.w_axis.truncate())
                };
                let travel =
                    (at(&end, "TRAJECTORY") - at(&start, "TRAJECTORY")) * Vec3::new(1.0, 0.0, 1.0);
                let travel = travel.try_normalize().unwrap_or(Vec3::X);
                let across = Vec3::new(-travel.z, 0.0, travel.x);
                let left = at(&start, "LEFTARM") - at(&start, "RIGHTARM");
                let facing = left.cross(Vec3::Y);
                self.side = if facing.dot(across) < 0.0 {
                    -across
                } else {
                    across
                };
                self.target = Self::hips(&demo, &start);
                self.demo = Some(demo);
                self.failed = false;
            }
            Err(e) => {
                if !self.failed {
                    eprintln!("[mineskate3] trick guide demo: {e}");
                }
                self.failed = true;
                self.demo = None;
            }
        }
    }

    /// Advances `dt` seconds and writes: valid flag, camera eye and target,
    /// the six player-model parts (retarget::pose), the board box, then the
    /// bone count and bones for skinning the board mesh (mesh::Meshes).
    fn write(&mut self, dt: f32, out: &mut Vec<f32>) {
        out.clear();
        let Some(demo) = &self.demo else { return };
        self.time += dt.clamp(0.0, 0.25);
        let restart = self.time > demo.duration() + HOLD_SECONDS;
        if restart {
            self.time = 0.0;
        }
        let bones = demo.sample(self.time);
        let hips = Self::hips(demo, &bones);
        self.target = if restart {
            hips
        } else {
            self.target.lerp(hips, 1.0 - (-dt * CAMERA_FOLLOW).exp())
        };
        let world = |name: &str| demo.bone(&bones, name);
        let Some(parts) = retarget::pose(|n| world(n).map(|m| m.w_axis.truncate())) else {
            return;
        };
        out.resize(layout::BONES, 0.0);
        out[0] = 1.0;
        let eye = self.target + self.side * CAMERA_DISTANCE + Vec3::Y * CAMERA_HEIGHT;
        out[layout::EYE..layout::EYE + 3].copy_from_slice(&eye.to_array());
        out[layout::TARGET..layout::TARGET + 3].copy_from_slice(&self.target.to_array());
        for (i, m) in parts.iter().enumerate() {
            out[layout::PARTS + 16 * i..layout::PARTS + 16 * (i + 1)]
                .copy_from_slice(&m.to_cols_array());
        }
        let meshes = crate::loaded_meshes();
        let board = world("SKATEBOARD_ROOT").unwrap_or(Mat4::IDENTITY);
        let board_box = meshes
            .as_ref()
            .and_then(|m| m.board_bounds_in("SKATEBOARD_ROOT"))
            .map_or_else(
                || board * Mat4::from_scale(Vec3::new(0.8, 0.06, 0.2)),
                |local| board * local,
            );
        out[layout::BOARD..layout::BOARD + 16].copy_from_slice(&board_box.to_cols_array());
        let mut skin = Vec::new();
        if let Some(m) = &meshes {
            m.export_bones(world, &mut skin);
        }
        out[layout::BONE_COUNT] = (skin.len() / 12) as f32;
        out.extend_from_slice(&skin);
    }
}

fn guide<'a>(handle: jlong) -> Option<&'a mut Guide> {
    if handle == 0 {
        None
    } else {
        // SAFETY: handles come from guideOpen and are freed only by guideFree;
        // the Java screen owns its handle on the render thread.
        Some(unsafe { &mut *(handle as *mut Guide) })
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideOpen(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    regular: jboolean,
) -> jlong {
    let root: String = match env.get_string(&root) {
        Ok(s) => s.into(),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e.to_string());
            return 0;
        }
    };
    let result = crate::guard(Err("panic while opening the trick guide".into()), || {
        Guide::open(&PathBuf::from(root), regular != 0)
    });
    match result {
        Ok(g) => Box::into_raw(Box::new(g)) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: see `guide`; the Java side forgets the handle afterwards.
        crate::guard((), || drop(unsafe { Box::from_raw(handle as *mut Guide) }));
    }
}

/// 0 up, 1 down, 2 select, 3 back. False once the guide has failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideInput(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    nav: jint,
) -> jboolean {
    let Some(g) = guide(handle) else { return 0 };
    let nav = match nav {
        0 => Nav::Up,
        1 => Nav::Down,
        2 => Nav::Select,
        _ => Nav::Back,
    };
    crate::guard(0, || jboolean::from(g.run(|t| t.input(nav))))
}

/// One UI tick; the screen calls it at the movie's frame rate.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideUpdate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(g) = guide(handle) else { return 0 };
    crate::guard(0, || {
        let ok = g.run(TrickGuide::update);
        if ok {
            match g.guide.draws() {
                Ok(draws) => hud::encode_draws(&draws, &g.index, &mut g.draws),
                Err(e) => g.error = e,
            }
        }
        jboolean::from(g.error.is_empty())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(g) = guide(handle) {
        crate::guard((), || {
            g.run(TrickGuide::close);
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideClosed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    guide(handle).map_or(1, |g| {
        jboolean::from(g.guide.closed() || !g.error.is_empty())
    })
}

fn string(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideError(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = guide(handle).map_or_else(String::new, |g| g.error.clone());
    string(&mut env, &text)
}

/// The highlighted entry's demo clip name, or an empty string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideClip(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = guide(handle)
        .and_then(|g| g.guide.clip().map(str::to_owned))
        .unwrap_or_default();
    string(&mut env, &text)
}

/// Texture files, relative to the trick guide folder, as "path|width|height".
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideTextures(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jobjectArray {
    let textures = guide(handle).map_or_else(Vec::new, |g| g.textures.clone());
    let Ok(array) = env.new_object_array(
        textures.len() as jint,
        "java/lang/String",
        jni::objects::JObject::null(),
    ) else {
        return std::ptr::null_mut();
    };
    for (i, (path, [w, h])) in textures.iter().enumerate() {
        let Ok(text) = env.new_string(format!("{path}|{w}|{h}")) else {
            return std::ptr::null_mut();
        };
        if env
            .set_object_array_element(&array, i as jint, text)
            .is_err()
        {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

/// The draw list from the last update (see `hud::encode_draws`): its length
/// in floats, or minus the length needed when `out` is too small.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideDraws(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    out: JFloatArray,
) -> jint {
    let Some(g) = guide(handle) else { return 0 };
    let len = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
    if g.draws.len() > len {
        return -(g.draws.len() as jint);
    }
    if env.set_float_array_region(&out, 0, &g.draws).is_err() {
        return 0;
    }
    g.draws.len() as jint
}

/// Advances the demo by `dt` seconds and writes it to `out` (see
/// `Player::write`). Returns the float count, 0 without a demo, or minus the
/// length needed when `out` is too small (the frame is not repeated).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_guideDemo(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    dt: jfloat,
    out: JFloatArray,
) -> jint {
    let Some(g) = guide(handle) else { return 0 };
    let mut data = Vec::new();
    crate::guard((), || {
        let clip = g.guide.clip().map(str::to_owned);
        let clip = clip.as_deref().unwrap_or(if g.player.clip.is_empty() {
            FIRST_CLIP
        } else {
            ""
        });
        if !clip.is_empty() {
            g.player.select(clip);
        }
        g.player.write(dt, &mut data);
    });
    let len = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
    if data.len() > len {
        return -(data.len() as jint);
    }
    if data.is_empty() || env.set_float_array_region(&out, 0, &data).is_err() {
        return 0;
    }
    data.len() as jint
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Opt-in: DEMO_CLIPS=<folder of trickguide_*.abin>.
    #[test]
    #[ignore]
    fn demo_player_loops_with_a_finite_camera() {
        let Ok(dir) = std::env::var("DEMO_CLIPS") else {
            return;
        };
        let mut player = Player::new(PathBuf::from(dir));
        player.select("trickguide_kickflip_01");
        let duration = player.demo.as_ref().unwrap().duration();
        let mut out = Vec::new();
        let mut restarted = false;
        for _ in 0..((duration + HOLD_SECONDS) * 30.0) as usize + 10 {
            let before = player.time;
            player.write(1.0 / 30.0, &mut out);
            restarted |= player.time < before;
            assert_eq!(out.len(), layout::BONES);
            assert!(out.iter().all(|v| v.is_finite()));
            let eye = Vec3::from_slice(&out[layout::EYE..]);
            let target = Vec3::from_slice(&out[layout::TARGET..]);
            assert!(
                (eye.distance(target) - (CAMERA_DISTANCE.powi(2) + CAMERA_HEIGHT.powi(2)).sqrt())
                    .abs()
                    < 1e-3
            );
        }
        assert!(restarted);
        player.select("trickguide_no_such_clip");
        player.write(0.1, &mut out);
        assert!(out.is_empty());
    }
}
