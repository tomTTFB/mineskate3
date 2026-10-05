//! Original APT movie hierarchy and timeline control, independent of Bevy.
use super::{
    apt_display::{Control, DisplayList, Placement},
    apt_scene::Shapes,
    apt_vm::{Instruction, ObjectKind, Value, Vm},
};
use serde::Deserialize;
use std::collections::{BTreeMap, VecDeque};

#[derive(Clone, Deserialize)]
pub struct Frame {
    pub controls: Vec<Control>,
}
#[derive(Clone, Deserialize)]
pub struct Character {
    pub id: i32,
    pub type_name: String,
    #[serde(default)]
    pub frames: Vec<Frame>,
    pub text: Option<serde_json::Value>,
    pub bounds: Option<[f32; 4]>,
    /// Source movie of a flattened front-end screen (0 for the screen).
    #[serde(default)]
    pub bundle: i32,
}
#[derive(Clone)]
pub struct Instance {
    pub character: i32,
    pub frame: usize,
    pub playing: bool,
    pub children: BTreeMap<i32, usize>,
    /// attachMovie/createEmptyMovieClip children, kept across timeline seeks.
    pub dynamic: BTreeMap<i32, usize>,
    pub placement: Option<Placement>,
    /// _xscale, _yscale and _rotation decomposed from the placement matrix;
    /// while the script leaves them alone the authored matrix (with any skew)
    /// is used verbatim.
    pub decomposed: Option<[f64; 3]>,
    pub parent: Option<usize>,
}
/// Character id of clips made by createEmptyMovieClip.
pub const EMPTY_CLIP: i32 = -1;

pub struct Movie {
    pub characters: BTreeMap<i32, Character>,
    pub instances: BTreeMap<usize, Instance>,
    pub actions: BTreeMap<String, Vec<Instruction>>,
    pub pending: VecDeque<(usize, u32)>,
    pub root: usize,
    pub text_assets: super::apt_text::TextAssets,
    pub shapes: Shapes,
    /// Export (linkage) names per source movie, for attachMovie.
    exports: BTreeMap<i32, BTreeMap<String, i32>>,
    /// The linkage name of each exported character, for registerClass.
    linkage: BTreeMap<i32, String>,
    /// Object.registerClass: linkage name to constructor function object.
    pub classes: BTreeMap<String, usize>,
    /// Constructors owed to new clips, children first, run by the host.
    pub constructors: VecDeque<(usize, usize)>,
    /// Flattened screens list their init actions in dependency order.
    pub init_order: Option<Vec<u32>>,
    states: BTreeMap<i32, Vec<DisplayList>>,
}
fn decompose(m: [f32; 6]) -> [f64; 3] {
    let (a, b, c, d) = (m[0] as f64, m[1] as f64, m[2] as f64, m[3] as f64);
    let sx = a.hypot(b);
    let mut sy = c.hypot(d);
    if a * d - b * c < 0.0 {
        sy = -sy;
    }
    [sx * 100.0, sy * 100.0, b.atan2(a).to_degrees()]
}
impl Movie {
    pub fn load(json: &serde_json::Value) -> Result<Self, String> {
        let mut characters: Vec<Character> =
            serde_json::from_value(json["characters"].clone()).map_err(|e| e.to_string())?;
        characters.push(Character {
            id: EMPTY_CLIP,
            type_name: "sprite".into(),
            frames: Vec::new(),
            text: None,
            bounds: None,
            bundle: 0,
        });
        let mut states = BTreeMap::new();
        for c in &characters {
            let mut list = DisplayList::default();
            let mut frames = Vec::new();
            for frame in &c.frames {
                for control in &frame.controls {
                    list.apply(control)?;
                }
                frames.push(list.clone());
            }
            states.insert(c.id, frames);
        }
        let exports = match json.get("exports") {
            Some(e) if !e.is_null() => e
                .as_object()
                .ok_or("Invalid APT exports")?
                .iter()
                .map(|(bundle, names)| {
                    let names: BTreeMap<String, i32> =
                        serde_json::from_value(names.clone()).map_err(|e| e.to_string())?;
                    Ok((bundle.parse::<i32>().map_err(|e| e.to_string())?, names))
                })
                .collect::<Result<_, String>>()?,
            _ => BTreeMap::new(),
        };
        let linkage = match json.get("linkage") {
            Some(l) if !l.is_null() => {
                let raw: BTreeMap<String, String> =
                    serde_json::from_value(l.clone()).map_err(|e| e.to_string())?;
                raw.into_iter()
                    .map(|(id, name)| Ok((id.parse::<i32>().map_err(|e| e.to_string())?, name)))
                    .collect::<Result<_, String>>()?
            }
            _ => BTreeMap::new(),
        };
        let shapes: Shapes = match json.get("shapes") {
            Some(s) if !s.is_null() => {
                serde_json::from_value(s.clone()).map_err(|e| e.to_string())?
            }
            _ => Shapes::new(),
        };
        let init_order = match json.get("init_order") {
            Some(o) if !o.is_null() => {
                Some(serde_json::from_value(o.clone()).map_err(|e| e.to_string())?)
            }
            _ => None,
        };
        Ok(Self {
            characters: characters.into_iter().map(|c| (c.id, c)).collect(),
            instances: BTreeMap::new(),
            actions: serde_json::from_value(json["actions"].clone()).map_err(|e| e.to_string())?,
            pending: VecDeque::new(),
            root: usize::MAX,
            text_assets: super::apt_text::TextAssets::load(json)?,
            shapes,
            exports,
            linkage,
            classes: BTreeMap::new(),
            constructors: VecDeque::new(),
            init_order,
            states,
        })
    }
    pub fn initialize(&mut self, vm: &mut Vm) -> Result<(), String> {
        self.root = self.create(vm, 0, None, 0)?;
        vm.set(self.root, "_root", Value::Object(self.root))?;
        // Every clip sees the same authored root, including unnamed children.
        for id in self.instances.keys() {
            vm.set(*id, "_root", Value::Object(self.root))?;
        }
        Ok(())
    }
    fn create(
        &mut self,
        vm: &mut Vm,
        character: i32,
        parent: Option<usize>,
        depth: usize,
    ) -> Result<usize, String> {
        if depth > 32 || self.instances.len() > 4096 {
            return Err("APT movie hierarchy limit".into());
        }
        let c = self
            .characters
            .get(&character)
            .ok_or_else(|| format!("APT unknown character {character}"))?
            .clone();
        let id = vm.object(ObjectKind::Native(format!("movie:{character}")));
        if self.root != usize::MAX {
            vm.set(id, "_root", Value::Object(self.root))?;
        }
        if let Some(parent) = parent {
            vm.set(id, "_parent", Value::Object(parent))?;
        }
        vm.set(id, "_x", Value::Number(0.0))?;
        vm.set(id, "_y", Value::Number(0.0))?;
        vm.set(id, "_xscale", Value::Number(100.0))?;
        vm.set(id, "_yscale", Value::Number(100.0))?;
        vm.set(id, "_rotation", Value::Number(0.0))?;
        vm.set(id, "_visible", Value::Bool(true))?;
        vm.set(id, "_alpha", Value::Number(100.0))?;
        if let Some(text) = &c.text {
            vm.set(
                id,
                "text",
                Value::Text(text["initial_text"].as_str().unwrap_or("").into()),
            )?;
        }
        // registerClass: the clip takes the class prototype now; the
        // constructor runs once its children exist.
        let class = self
            .linkage
            .get(&character)
            .and_then(|name| self.classes.get(name))
            .copied();
        if let Some(class) = class
            && let Value::Object(proto) = vm.get(class, "prototype")
        {
            vm.objects[id].prototype = Some(proto);
        }
        self.instances.insert(
            id,
            Instance {
                character,
                frame: 0,
                playing: !c.frames.is_empty(),
                children: BTreeMap::new(),
                dynamic: BTreeMap::new(),
                placement: None,
                decomposed: None,
                parent,
            },
        );
        self.text_changed(vm, id)?;
        if !c.frames.is_empty() {
            self.seek(vm, id, 0, depth + 1)?;
        }
        if let Some(class) = class {
            self.constructors.push_back((id, class));
        }
        Ok(id)
    }
    pub fn text_changed(&self, vm: &mut Vm, id: usize) -> Result<(), String> {
        let Some(instance) = self.instances.get(&id) else {
            return Ok(());
        };
        let character = &self.characters[&instance.character];
        let Some(text) = &character.text else {
            return Ok(());
        };
        let value = self.text_assets.localize(&vm.get(id, "text").text());
        vm.set(id, "_displayText", Value::Text(value.clone()))?;
        let font = self
            .text_assets
            .fonts
            .get(&(text["font_id"].as_i64().ok_or("Invalid text font id")? as i32))
            .ok_or("Missing original text font")?;
        let height = text["font_height"].as_f64().ok_or("Invalid text size")? as f32;
        let width = value
            .split(['^', '\n'])
            .map(|line| font.width(line, height))
            .fold(0.0, f32::max);
        vm.set(id, "textWidth", Value::Number(width as f64))?;
        let bounds = character.bounds.ok_or("Missing text bounds")?;
        let autosize = vm.get(id, "autoSize").text();
        vm.set(
            id,
            "_width",
            Value::Number(
                if autosize == "left" || autosize == "right" || autosize == "center" {
                    width
                } else {
                    bounds[2] - bounds[0]
                } as f64,
            ),
        )?;
        Ok(())
    }
    /// Removes a clip and its descendants. Retired handles can remain
    /// referenced by ActionScript, but no longer advance or render.
    pub fn remove(&mut self, vm: &mut Vm, id: usize) {
        if let Some(instance) = self.instances.remove(&id) {
            for child in instance.children.values().chain(instance.dynamic.values()) {
                self.remove(vm, *child);
            }
        }
        let _ = vm.set(id, "_visible", Value::Bool(false));
    }
    pub fn seek(
        &mut self,
        vm: &mut Vm,
        id: usize,
        frame: usize,
        nesting: usize,
    ) -> Result<(), String> {
        let instance = self
            .instances
            .get(&id)
            .ok_or("APT absent movie instance")?
            .clone();
        let character = self
            .characters
            .get(&instance.character)
            .ok_or("APT absent movie character")?;
        if frame >= character.frames.len() {
            return Err(format!(
                "APT frame {frame} outside character {}",
                character.id
            ));
        }
        let frame_count = character.frames.len();
        let actions: Vec<_> = character.frames[frame]
            .controls
            .iter()
            .filter(|c| c.type_name == "do_action" && c.actions_offset != 0)
            .map(|c| c.actions_offset)
            .collect();
        let list = self.states[&instance.character][frame].clone();
        let mut children = BTreeMap::new();
        for (depth, placement) in list.depths {
            let previous = instance.children.get(&depth).copied();
            if let Some(name) = previous
                .and_then(|old| self.instances.get(&old))
                .and_then(|i| i.placement.as_ref())
                .map(|p| p.name.clone())
                && !name.is_empty()
                && name != placement.name
            {
                vm.objects[id].fields.remove(&name);
            }
            let child = if let Some(old) = previous.filter(|old| {
                self.instances
                    .get(old)
                    .is_some_and(|i| i.character == placement.character)
            }) {
                old
            } else {
                if let Some(old) = previous {
                    self.remove(vm, old);
                }
                self.create(vm, placement.character, Some(id), nesting + 1)?
            };
            let old = self.instances[&child].placement.as_ref();
            if old.is_none_or(|old| old.matrix != placement.matrix) {
                let [sx, sy, rotation] = decompose(placement.matrix);
                vm.set(child, "_x", Value::Number(placement.matrix[4] as f64))?;
                vm.set(child, "_y", Value::Number(placement.matrix[5] as f64))?;
                vm.set(child, "_xscale", Value::Number(sx))?;
                vm.set(child, "_yscale", Value::Number(sy))?;
                vm.set(child, "_rotation", Value::Number(rotation))?;
                self.instances.get_mut(&child).unwrap().decomposed = Some([sx, sy, rotation]);
            }
            if !placement.name.is_empty() {
                vm.set(id, &placement.name, Value::Object(child))?;
                vm.set(child, "_name", Value::Text(placement.name.clone()))?;
            }
            self.instances.get_mut(&child).unwrap().placement = Some(placement);
            children.insert(depth, child);
        }
        for (depth, child) in &instance.children {
            if !children.contains_key(depth) {
                if let Some(name) = self
                    .instances
                    .get(child)
                    .and_then(|i| i.placement.as_ref())
                    .map(|p| p.name.clone())
                    && !name.is_empty()
                {
                    vm.objects[id].fields.remove(&name);
                }
                self.remove(vm, *child);
            }
        }
        let Some(current) = self.instances.get_mut(&id) else {
            return Ok(());
        };
        current.frame = frame;
        current.children = children;
        vm.set(id, "_currentframe", Value::Number((frame + 1) as f64))?;
        vm.set(id, "_totalframes", Value::Number(frame_count as f64))?;
        for offset in actions {
            self.pending.push_back((id, offset));
        }
        Ok(())
    }
    pub fn advance(&mut self, vm: &mut Vm) -> Result<(), String> {
        let playing: Vec<_> = self
            .instances
            .iter()
            .filter(|(_, i)| i.playing)
            .map(|(id, i)| (*id, i.character, i.frame))
            .collect();
        for (id, character, frame) in playing {
            if !self.instances.contains_key(&id) {
                continue;
            }
            let count = self.characters[&character].frames.len();
            if count > 1 {
                self.seek(vm, id, (frame + 1) % count, 0)?;
            }
        }
        Ok(())
    }
    /// attachMovie: a new instance of an exported character, looked up in
    /// the parent's own movie first.
    fn attach(
        &mut self,
        vm: &mut Vm,
        parent: usize,
        character: i32,
        name: &str,
        depth: i32,
    ) -> Result<usize, String> {
        if let Some(old) = self.instances[&parent].dynamic.get(&depth).copied() {
            self.remove(vm, old);
        }
        let child = self.create(vm, character, Some(parent), 0)?;
        if self.root != usize::MAX {
            vm.set(child, "_root", Value::Object(self.root))?;
        }
        vm.set(child, "_name", Value::Text(name.into()))?;
        vm.set(parent, name, Value::Object(child))?;
        self.instances
            .get_mut(&parent)
            .ok_or("APT absent movie instance")?
            .dynamic
            .insert(depth, child);
        Ok(child)
    }
    fn export(&self, parent: usize, name: &str) -> Option<i32> {
        let bundle = self.characters[&self.instances[&parent].character].bundle;
        self.exports
            .get(&bundle)
            .and_then(|e| e.get(name))
            .or_else(|| self.exports.values().find_map(|e| e.get(name)))
            .copied()
    }
    fn detach(&mut self, vm: &mut Vm, id: usize) {
        if let Some(parent) = self.instances.get(&id).and_then(|i| i.parent)
            && let Some(p) = self.instances.get_mut(&parent)
        {
            p.dynamic.retain(|_, child| *child != id);
            if let Value::Text(name) = vm.get(id, "_name")
                && vm.get(parent, &name) == Value::Object(id)
            {
                vm.objects[parent].fields.remove(&name);
            }
        }
        self.remove(vm, id);
    }
    pub fn method(
        &mut self,
        vm: &mut Vm,
        id: usize,
        method: &str,
        args: &[Value],
    ) -> Result<Option<Value>, String> {
        if !self.instances.contains_key(&id) {
            return Ok(None);
        }
        match method {
            "stop" => self.instances.get_mut(&id).unwrap().playing = false,
            "play" => self.instances.get_mut(&id).unwrap().playing = true,
            "gotoAndPlay" | "gotoAndStop" => {
                let c = &self.characters[&self.instances[&id].character];
                let frame = match args.first().ok_or("APT goto requires frame")? {
                    Value::Text(label) => {
                        let found = c.frames.iter().position(|f| {
                            f.controls.iter().any(|control| {
                                control.type_name == "frame_label"
                                    && control.label.as_deref() == Some(label)
                            })
                        });
                        // Flash ignores an unknown label; so does the shipped UI.
                        let Some(found) = found else {
                            return Ok(Some(Value::Undefined));
                        };
                        found
                    }
                    value => {
                        let frame = value.number();
                        if !frame.is_finite() || frame < 0.0 {
                            return Err(format!(
                                "Invalid APT frame {frame} in {} on character {}",
                                method, c.id
                            ));
                        }
                        // The shipped line timer deliberately requests zero at
                        // full capacity. Frame zero addresses the first frame.
                        (frame as usize).saturating_sub(1)
                    }
                };
                if c.frames.is_empty() {
                    return Ok(Some(Value::Undefined));
                }
                let frame = frame.min(c.frames.len() - 1);
                self.seek(vm, id, frame, 0)?;
                if let Some(instance) = self.instances.get_mut(&id) {
                    instance.playing = method == "gotoAndPlay";
                }
            }
            "attachMovie" => {
                let linkage = args.first().map(Value::text).unwrap_or_default();
                let Some(character) = self.export(id, &linkage) else {
                    return Ok(Some(Value::Undefined));
                };
                let name = args.get(1).map(Value::text).unwrap_or_default();
                let depth = args.get(2).map(Value::number).unwrap_or(0.0) as i32;
                let child = self.attach(vm, id, character, &name, depth)?;
                if let Some(Value::Object(init)) = args.get(3) {
                    let fields: Vec<_> = vm.objects[*init]
                        .fields
                        .iter()
                        .map(|(k, v)| (k.clone(), v.clone()))
                        .collect();
                    for (k, v) in fields {
                        vm.set(child, k, v)?;
                    }
                }
                return Ok(Some(Value::Object(child)));
            }
            "createEmptyMovieClip" => {
                let name = args.first().map(Value::text).unwrap_or_default();
                let depth = args.get(1).map(Value::number).unwrap_or(0.0) as i32;
                let child = self.attach(vm, id, EMPTY_CLIP, &name, depth)?;
                return Ok(Some(Value::Object(child)));
            }
            "removeMovieClip" => self.detach(vm, id),
            "getDepth" => {
                let depth = self.instances[&id].parent.and_then(|p| {
                    let p = &self.instances.get(&p)?;
                    p.children
                        .iter()
                        .chain(p.dynamic.iter())
                        .find(|(_, c)| **c == id)
                        .map(|(d, _)| *d)
                });
                return Ok(Some(Value::Number(depth.unwrap_or(0) as f64)));
            }
            "setTextFormat" => {
                if let Some(Value::Object(format)) = args.last() {
                    if let Value::Number(color) = vm.get(*format, "color") {
                        vm.set(id, "textColor", Value::Number(color))?;
                    }
                    if let Value::Number(size) = vm.get(*format, "size") {
                        vm.set(id, "_fontSize", Value::Number(size))?;
                    }
                }
            }
            _ => return Ok(None),
        }
        Ok(Some(Value::Undefined))
    }
}
