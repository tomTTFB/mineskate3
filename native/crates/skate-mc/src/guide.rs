//! JNI for the Trick Guide screen. It runs on the calling (render) thread:
//! the guide is a menu, independent of the skate session.
use crate::hud::{self, trick_guide::{Nav, TrickGuide}};
use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring};
use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

pub struct Guide {
    guide: TrickGuide,
    textures: Vec<(String, [u32; 2])>,
    index: HashMap<String, usize>,
    error: String,
    draws: Vec<f32>,
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
        for texture in movie.shapes.values().flatten().filter_map(|s| s.texture.as_ref()) {
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
    guide(handle).map_or(1, |g| jboolean::from(g.guide.closed() || !g.error.is_empty()))
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
    let Ok(array) = env.new_object_array(textures.len() as jint, "java/lang/String", jni::objects::JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, (path, [w, h])) in textures.iter().enumerate() {
        let Ok(text) = env.new_string(format!("{path}|{w}|{h}")) else {
            return std::ptr::null_mut();
        };
        if env.set_object_array_element(&array, i as jint, text).is_err() {
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
