//! JNI bridge between the MineSkate 3 NeoForge mod and the Skate 3 Rust engine.
//! Java side: `dev.mineskate3.client.NativeSkate`.
pub mod board;
pub mod rails;
pub mod refpack;
pub mod retarget;
pub mod worker;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jintArray, jlong, jstring};
use std::panic::{AssertUnwindSafe, catch_unwind};
use worker::{Host, Pad, Status, Triangle};

/// Bumped when the Java-facing contract changes; the mod refuses a mismatch.
pub const ABI_VERSION: jint = 1;

fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

fn host<'a>(handle: jlong) -> Option<&'a mut Host> {
    if handle == 0 {
        None
    } else {
        // SAFETY: handles come from `create` and are only freed by `destroy`;
        // the Java side serialises every call for one handle.
        Some(unsafe { &mut *(handle as *mut Host) })
    }
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_abiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_create(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) -> jlong {
    let root: String = match env.get_string(&root) {
        Ok(s) => s.into(),
        Err(e) => {
            throw(&mut env, &e.to_string());
            return 0;
        }
    };
    match guard(Err("panic while starting the Skate session".into()), || {
        Host::start(root.into())
    }) {
        Ok(host) => Box::into_raw(Box::new(host)) as jlong,
        Err(e) => {
            throw(&mut env, &e);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: see `host`; Java drops its handle after this call. Dropping
        // the sender ends the session thread.
        guard((), || drop(unsafe { Box::from_raw(handle as *mut Host) }));
    }
}

/// 0 loading, 1 ready, 2 skating, -1 failed (see `error`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_status(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guard(Status::Failed as jint, || {
        host(handle).map_or(Status::Failed as jint, |h| h.shared.lock().unwrap().status as jint)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_error(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = guard(String::from("panic"), || {
        host(handle).map_or_else(|| "no session".into(), |h| h.shared.lock().unwrap().error.clone())
    });
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_state(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = guard(String::new(), || {
        host(handle).map_or_else(String::new, |h| h.shared.lock().unwrap().state.clone())
    });
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Replaces the collision around the skater: `triangles` holds 9 floats per
/// triangle, counterclockwise seen from outside, in session space. Rails are
/// only kept strictly inside the x/z box `min_x, min_z, max_x, max_z`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_collision(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    triangles: JFloatArray,
    count: jint,
    min_x: jfloat,
    min_z: jfloat,
    max_x: jfloat,
    max_z: jfloat,
) {
    let Some(host) = host(handle) else { return };
    let floats = (count.max(0) as usize) * 9;
    let mut data = vec![0.0f32; floats];
    if env.get_float_array_region(&triangles, 0, &mut data).is_err() {
        return;
    }
    let tris: Vec<Triangle> = data
        .chunks_exact(9)
        .map(|t| [[t[0], t[1], t[2]], [t[3], t[4], t[5]], [t[6], t[7], t[8]]])
        .filter(|t| t.iter().flatten().all(|v| v.is_finite()))
        .collect();
    guard((), || host.collision(tris, Some([min_x, min_z, max_x, max_z])));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_activate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    z: jfloat,
    heading: jfloat,
    aspect: jfloat,
) {
    if let Some(host) = host(handle) {
        guard((), || host.activate([x, y, z], heading, aspect));
    }
}

/// One rendered frame of input. `connected` false sends a disconnected pad.
/// Sticks are XInput signed shorts (y up), triggers 0..255.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_step(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    dt: jfloat,
    connected: jboolean,
    buttons: jint,
    left_trigger: jint,
    right_trigger: jint,
    lx: jint,
    ly: jint,
    rx: jint,
    ry: jint,
    aspect: jfloat,
) {
    let Some(host) = host(handle) else { return };
    let axis = |v: jint| v.clamp(-32768, 32767) as i16;
    let trigger = |v: jint| v.clamp(0, 255) as u8;
    let pad = (connected != 0).then(|| Pad {
        buttons: buttons as u16,
        triggers: [trigger(left_trigger), trigger(right_trigger)],
        left: [axis(lx), axis(ly)],
        right: [axis(rx), axis(ry)],
    });
    guard((), || host.step(dt.clamp(0.0, 0.1), pad, aspect));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_suspend(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(host) = host(handle) {
        guard((), || host.suspend());
    }
}

/// Copies the newest pose into `out` (see `worker::layout`), returning its
/// generation, or -1 when nothing has been published yet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_pose(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    out: JFloatArray,
) -> jlong {
    let Some(host) = host(handle) else { return -1 };
    let (generation, data) = {
        let s = host.shared.lock().unwrap();
        if s.generation == 0 {
            return -1;
        }
        (s.generation, s.out.clone())
    };
    let len = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
    let n = len.min(data.len());
    if env.set_float_array_region(&out, 0, &data[..n]).is_err() {
        return -1;
    }
    generation as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_poseLength(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    worker::layout::LEN as jint
}

/// Skinned board vertices, 8 floats each (position, normal, uv), written into
/// `out`; returns the vertex count, or 0 without a board.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_boardVertices(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    out: JFloatArray,
) -> jint {
    let Some(host) = host(handle) else { return 0 };
    let data = host.shared.lock().unwrap().board_vertices.clone();
    let len = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
    if data.is_empty() || len < data.len() {
        return 0;
    }
    if env.set_float_array_region(&out, 0, &data).is_err() {
        return 0;
    }
    (data.len() / 8) as jint
}

/// Board layout: [vertexCount, surfaceCount, then per surface: texture,
/// firstIndex, indexCount], or an empty array before the session loads.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_boardLayout(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jintArray {
    let board = host(handle).and_then(|h| h.shared.lock().unwrap().board.clone());
    let mut layout: Vec<jint> = Vec::new();
    if let Some(board) = board {
        layout.push(board.positions.len() as jint);
        layout.push(board.surfaces.len() as jint);
        for s in &board.surfaces {
            layout.extend([s.texture as jint, s.first_index as jint, s.index_count as jint]);
        }
    }
    int_array(env, &layout)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_boardIndices(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jintArray {
    let board = host(handle).and_then(|h| h.shared.lock().unwrap().board.clone());
    let indices: Vec<jint> = board.map_or_else(Vec::new, |b| {
        b.indices.iter().map(|&i| i as jint).collect()
    });
    int_array(env, &indices)
}

/// The encoded (PNG) image of board texture `index`, or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_boardTexture(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jbyteArray {
    let board = host(handle).and_then(|h| h.shared.lock().unwrap().board.clone());
    let Some(bytes) = board.and_then(|b| b.textures.get(index.max(0) as usize).cloned()) else {
        return std::ptr::null_mut();
    };
    env.byte_array_from_slice(&bytes)
        .map(JByteArray::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mineskate3_client_NativeSkate_rails(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    host(handle).map_or(0, |h| h.shared.lock().unwrap().rails as jint)
}

fn int_array(env: JNIEnv, values: &[jint]) -> jintArray {
    let Ok(array) = env.new_int_array(values.len() as jint) else {
        return std::ptr::null_mut();
    };
    if env.set_int_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    JIntArray::into_raw(array)
}
