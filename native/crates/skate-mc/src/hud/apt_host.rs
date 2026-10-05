//! Built-ins every APT movie can call, shared by the HUD and screen hosts:
//! MovieClip methods, Math, Array, Object.registerClass and display sizes.
use super::{
    apt_movie::Movie,
    apt_scene,
    apt_vm::{ObjectKind, Value, Vm},
};

/// Globals the shipped movies expect before their init actions run.
pub fn install_globals(vm: &mut Vm) -> Result<(), String> {
    for name in ["MovieClip", "Math", "Object", "TextFormat", "Array"] {
        if vm.get(vm.global, name) == Value::Undefined {
            let object = vm.object(ObjectKind::Native(name.into()));
            let prototype = vm.object(ObjectKind::Plain);
            vm.set(object, "prototype", Value::Object(prototype))?;
            vm.set(vm.global, name, Value::Object(object))?;
        }
    }
    if vm.get(vm.global, "Screen_EdgeOffset") == Value::Undefined {
        vm.set(vm.global, "Screen_EdgeOffset", Value::Number(0.0))?;
    }
    Ok(())
}

fn native_name(vm: &Vm, object: usize) -> String {
    match vm.objects.get(object).map(|o| &o.kind) {
        Some(ObjectKind::Native(name)) => name.clone(),
        _ => "global".into(),
    }
}

/// Handles a call when it is a built-in; Ok(None) leaves it to the host.
pub fn call(
    movie: &mut Movie,
    vm: &mut Vm,
    object: usize,
    method: &str,
    args: &[Value],
) -> Result<Option<Value>, String> {
    if let Some(value) = movie.method(vm, object, method, args)? {
        return Ok(Some(value));
    }
    let number = |i: usize| args.get(i).map(Value::number).unwrap_or(f64::NAN);
    let native = native_name(vm, object);
    Ok(Some(match (native.as_str(), method) {
        ("Math", "floor") => Value::Number(number(0).floor()),
        ("Math", "ceil") => Value::Number(number(0).ceil()),
        ("Math", "round") => Value::Number((number(0) + 0.5).floor()),
        ("Math", "abs") => Value::Number(number(0).abs()),
        ("Math", "sqrt") => Value::Number(number(0).sqrt()),
        ("Math", "min") => Value::Number(number(0).min(number(1))),
        ("Math", "max") => Value::Number(number(0).max(number(1))),
        // Presentation-only random source; never influences scoring.
        ("Math", "random") => Value::Number(vm.random()),
        ("Object", "registerClass") => {
            if let (Some(name), Some(Value::Object(class))) = (args.first(), args.get(1)) {
                movie.classes.insert(name.text(), *class);
            }
            Value::Bool(true)
        }
        // The HUD never enumerates prototype properties. Marking their
        // enumeration flags therefore leaves its observable fields intact.
        ("global", "ASSetPropFlags") => Value::Undefined,
        ("global", "push") => {
            let length = vm.get(object, "length").number();
            let mut n = if length.is_finite() { length as usize } else { 0 };
            for value in args {
                vm.set(object, n.to_string(), value.clone())?;
                n += 1;
            }
            vm.set(object, "length", Value::Number(n as f64))?;
            Value::Number(n as f64)
        }
        ("global", "pop") => {
            let length = vm.get(object, "length").number();
            if !(length.is_finite() && length >= 1.0) {
                return Ok(Some(Value::Undefined));
            }
            let last = (length as usize - 1).to_string();
            let value = vm.get(object, &last);
            vm.objects[object].fields.remove(&last);
            vm.set(object, "length", Value::Number(length - 1.0))?;
            value
        }
        ("global", "join") => {
            let length = vm.get(object, "length").number();
            let separator = args.first().map_or(",".into(), Value::text);
            let n = if length.is_finite() { length as usize } else { 0 };
            Value::Text(
                (0..n)
                    .map(|i| vm.get(object, &i.to_string()).text())
                    .collect::<Vec<_>>()
                    .join(&separator),
            )
        }
        _ => return Ok(None),
    }))
}

/// `_width`/`_height` of a clip: its content bounds times its scale.
/// Text fields keep the width their text layout recorded.
pub fn size(movie: &Movie, vm: &Vm, id: usize, key: &str) -> Option<Value> {
    let instance = movie.instances.get(&id)?;
    if movie.characters[&instance.character].text.is_some() && key == "_width" {
        return None;
    }
    let bounds = apt_scene::local_bounds(movie, vm, id).unwrap_or([0.0; 4]);
    let (extent, scale) = if key == "_width" {
        (bounds[2] - bounds[0], vm.get(id, "_xscale").number())
    } else {
        (bounds[3] - bounds[1], vm.get(id, "_yscale").number())
    };
    Some(Value::Number(extent as f64 * scale.abs() / 100.0))
}

/// A script set `_width`/`_height`: express it as a scale of the content.
pub fn size_changed(movie: &Movie, vm: &mut Vm, id: usize, key: &str) -> Result<(), String> {
    let Some(instance) = movie.instances.get(&id) else {
        return Ok(());
    };
    if movie.characters[&instance.character].text.is_some() {
        return Ok(());
    }
    let Some(bounds) = apt_scene::local_bounds(movie, vm, id) else {
        return Ok(());
    };
    let (extent, scale_key) = if key == "_width" {
        (bounds[2] - bounds[0], "_xscale")
    } else {
        (bounds[3] - bounds[1], "_yscale")
    };
    let target = vm.get(id, key).number();
    if extent > 0.0 && target.is_finite() {
        let sign = vm.get(id, scale_key).number().signum();
        let sign = if sign == 0.0 || sign.is_nan() { 1.0 } else { sign };
        vm.set(id, scale_key, Value::Number(sign * target / extent as f64 * 100.0))?;
    }
    Ok(())
}
