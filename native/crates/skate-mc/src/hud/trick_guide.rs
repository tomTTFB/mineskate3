//! Skate 3's Trick Guide screen: the original tricks/trickguide movie and
//! its shared controls, served the menu the game's FETrickTutorial gives it.
use super::{
    apt_host,
    apt_movie::Movie,
    apt_scene,
    apt_vm::{Host, ObjectKind, Value, Vm},
};
use serde::Deserialize;
use std::collections::BTreeSet;

#[derive(Clone, Debug, Deserialize)]
pub struct Node {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub clip: Option<String>,
    #[serde(default)]
    pub inputs: Vec<[String; 3]>,
    #[serde(default)]
    pub children: Option<Vec<Node>>,
}

/// Menu navigation, as the movie's keys reach it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Nav {
    Up,
    Down,
    Select,
    Back,
}

// Key codes the movie compares UpdateInput's argument with.
const APT_UP: f64 = 1.0;
const APT_DOWN: f64 = 2.0;
const APT_NEXT: f64 = 5.0;
/// Up to four input slots; GetMenuItemGesturesButtons pads with blanks.
const SLOTS: usize = 4;
/// The root timeline's outro starts on frame 21 and ends with OutroComplete.
const OUTRO_FRAME: f64 = 21.0;

pub struct Bindings {
    pub movie: Movie,
    menu: Node,
    path: Vec<usize>,
    current: usize,
    regular: bool,
    rebuild: bool,
    gestures: bool,
    closing: bool,
    pub closed: bool,
    /// Calls with no native, reported once each for diagnosis.
    pub unknown: BTreeSet<String>,
}
impl Bindings {
    fn list(&self) -> &[Node] {
        let mut node = &self.menu;
        for &i in &self.path {
            match node.children.as_ref().and_then(|c| c.get(i)) {
                Some(child) => node = child,
                None => break,
            }
        }
        node.children.as_deref().unwrap_or(&[])
    }
    pub fn item(&self) -> Option<&Node> {
        self.list().get(self.current)
    }
    fn construct(&mut self, vm: &mut Vm) -> Result<(), String> {
        while let Some((clip, class)) = self.movie.constructors.pop_front() {
            vm.call_function(class, clip, Vec::new(), self)?;
        }
        Ok(())
    }
    fn array(vm: &mut Vm, values: Vec<Value>) -> Result<Value, String> {
        let id = vm.object(ObjectKind::Plain);
        let n = values.len();
        for (i, v) in values.into_iter().enumerate() {
            vm.set(id, i.to_string(), v)?;
        }
        vm.set(id, "length", Value::Number(n as f64))?;
        Ok(Value::Object(id))
    }
}
impl Host for Bindings {
    fn property_changed(&mut self, vm: &mut Vm, object: usize, key: &str) -> Result<(), String> {
        if key == "text" || key == "autoSize" {
            self.movie.text_changed(vm, object)?;
        }
        if key == "_width" || key == "_height" {
            apt_host::size_changed(&self.movie, vm, object, key)?;
        }
        Ok(())
    }
    fn property(&mut self, vm: &Vm, object: usize, key: &str) -> Option<Value> {
        apt_host::size(&self.movie, vm, object, key)
    }
    fn call(
        &mut self,
        vm: &mut Vm,
        object: usize,
        method: &str,
        args: Vec<Value>,
    ) -> Result<Value, String> {
        if let Some(value) = apt_host::call(&mut self.movie, vm, object, method, &args)? {
            self.construct(vm)?;
            return Ok(value);
        }
        let native = match vm.objects.get(object).map(|o| &o.kind) {
            Some(ObjectKind::Native(name)) => name.clone(),
            _ => "global".into(),
        };
        let index = |i: usize| args.get(i).map(Value::number).unwrap_or(0.0).max(0.0) as usize;
        Ok(match (native.as_str(), method) {
            ("Game", "GetMenuItems") => {
                let names = self.list().iter().map(|n| Value::Text(n.name.clone())).collect();
                Self::array(vm, names)?
            }
            ("Game", "OnMenuGetItemEnabled") => Value::Bool(true),
            ("Game", "OnMenuHilight") => {
                self.current = index(1);
                self.gestures = true;
                Value::Undefined
            }
            ("Game", "OnMenuSelect") => {
                self.current = index(1);
                if self.item().is_some_and(|n| n.children.as_ref().is_some_and(|c| !c.is_empty())) {
                    self.path.push(self.current);
                    self.current = 0;
                    self.rebuild = true;
                }
                self.gestures = true;
                Value::Undefined
            }
            ("FETrickTutorial", "GetMenuItemData") => {
                let item = self.item();
                let submenu = item.is_some_and(|n| n.children.as_ref().is_some_and(|c| !c.is_empty()));
                let description = item.map(|n| n.description.clone()).unwrap_or_default();
                let level = self.path.len() as f64;
                Self::array(
                    vm,
                    vec![Value::Number(level), Value::Bool(submenu), Value::Text(description)],
                )?
            }
            ("FETrickTutorial", "GetCurrentMenuItem") => Value::Number(self.current as f64),
            ("FETrickTutorial", "GetMenuItemGesturesButtons") => {
                let inputs = self.item().map(|n| n.inputs.clone()).unwrap_or_default();
                let mut values = vec![Value::Number(inputs.len().min(SLOTS) as f64)];
                for i in 0..SLOTS {
                    let slot = inputs.get(i).cloned().unwrap_or_default();
                    values.extend(slot.into_iter().map(Value::Text));
                }
                Self::array(vm, values)?
            }
            ("FETrickTutorial", "IsGoofy") => Value::Bool(!self.regular),
            ("HUDComponents", "PlayerStance_IsRegular") => Value::Bool(self.regular),
            ("Tricks", "IsStanceTrickGesturesFlipped") => Value::Bool(false),
            ("ScreenManager", "OnLoaded") => {
                // The screen manager starts the intro once the screen registers.
                if let Some(Value::Object(screen)) = args.first() {
                    self.movie.method(vm, *screen, "play", &[])?;
                }
                Value::Undefined
            }
            ("ScreenManager", "OutroComplete") => {
                self.closed = true;
                Value::Undefined
            }
            ("ScreenManager", "IntroComplete") | ("LetterBox", _) | ("Audio", _) => Value::Undefined,
            _ => {
                self.unknown.insert(format!("{native}.{method}"));
                Value::Undefined
            }
        })
    }
}

pub struct TrickGuide {
    pub vm: Vm,
    pub bindings: Bindings,
    screen: usize,
}
impl TrickGuide {
    pub fn load(json: &serde_json::Value, menu: &serde_json::Value, regular: bool) -> Result<Self, String> {
        let menu: Node = serde_json::from_value(menu.clone()).map_err(|e| e.to_string())?;
        let mut vm = Vm::new();
        apt_host::install_globals(&mut vm)?;
        for name in [
            "Game", "FETrickTutorial", "HUDComponents", "Tricks", "ScreenManager", "LetterBox",
            "Audio",
        ] {
            let object = vm.object(ObjectKind::Native(name.into()));
            vm.set(vm.global, name, Value::Object(object))?;
        }
        for (name, code) in [
            ("AptUp", APT_UP),
            ("AptDown", APT_DOWN),
            ("AptLeft", 3.0),
            ("AptRight", 4.0),
            ("AptNext", APT_NEXT),
            ("AptBack", 6.0),
        ] {
            vm.set(vm.global, name, Value::Number(code))?;
        }
        let mut bindings = Bindings {
            movie: Movie::load(json)?,
            menu,
            path: Vec::new(),
            current: 0,
            regular,
            rebuild: false,
            gestures: false,
            closing: false,
            closed: false,
            unknown: BTreeSet::new(),
        };
        let order = bindings
            .movie
            .init_order
            .clone()
            .ok_or("Trick guide runtime lacks its init order")?;
        for offset in order {
            let code = bindings
                .movie
                .actions
                .get(&offset.to_string())
                .ok_or("Missing trick guide init action")?
                .clone();
            vm.run(&code, &mut bindings)?;
        }
        vm.begin_update();
        bindings.movie.initialize(&mut vm)?;
        let mut guide = Self {
            vm,
            bindings,
            screen: usize::MAX,
        };
        guide.drain()?;
        let Value::Object(screen) = guide.vm.get(guide.bindings.movie.root, "screen") else {
            return Err("Trick guide screen was not constructed".into());
        };
        guide.screen = screen;
        // What the game does once the screen is up: list, then the inputs.
        guide.state(1)?;
        guide.state(11)?;
        Ok(guide)
    }
    fn drain(&mut self) -> Result<(), String> {
        let mut calls = 0;
        loop {
            self.bindings.construct(&mut self.vm)?;
            let Some((object, offset)) = self.bindings.movie.pending.pop_front() else {
                return Ok(());
            };
            calls += 1;
            if calls > 4096 {
                return Err("Trick guide frame action limit".into());
            }
            if !self.bindings.movie.instances.contains_key(&object) {
                continue;
            }
            let code = self
                .bindings
                .movie
                .actions
                .get(&offset.to_string())
                .ok_or("Missing trick guide action block")?
                .clone();
            self.vm.run_on(object, &code, &mut self.bindings)?;
        }
    }
    fn state(&mut self, state: i32) -> Result<(), String> {
        self.vm.begin_update();
        self.vm.call_method(
            self.screen,
            "SetState",
            vec![Value::Number(state as f64)],
            &mut self.bindings,
        )?;
        self.drain()
    }
    /// One menu key, as the movie handles it.
    pub fn input(&mut self, nav: Nav) -> Result<(), String> {
        if self.bindings.closing {
            return Ok(());
        }
        let code = match nav {
            Nav::Up => APT_UP,
            Nav::Down => APT_DOWN,
            Nav::Select => APT_NEXT,
            Nav::Back => {
                if let Some(parent) = self.bindings.path.pop() {
                    self.bindings.current = parent;
                    self.state(1)?;
                    self.state(11)?;
                } else {
                    self.close()?;
                }
                return Ok(());
            }
        };
        self.vm.begin_update();
        self.vm.call_method(
            self.screen,
            "UpdateInput",
            vec![Value::Number(code), Value::Number(0.0)],
            &mut self.bindings,
        )?;
        self.drain()?;
        if std::mem::take(&mut self.bindings.rebuild) {
            self.state(1)?;
        }
        if std::mem::take(&mut self.bindings.gestures) {
            self.state(11)?;
        }
        Ok(())
    }
    /// Plays the outro; `closed()` turns true when it finishes.
    pub fn close(&mut self) -> Result<(), String> {
        if self.bindings.closing {
            return Ok(());
        }
        self.bindings.closing = true;
        let root = self.bindings.movie.root;
        self.vm.begin_update();
        self.bindings
            .movie
            .method(&mut self.vm, root, "gotoAndPlay", &[Value::Number(OUTRO_FRAME)])?;
        self.drain()
    }
    pub fn closed(&self) -> bool {
        self.bindings.closed
    }
    /// One UI tick (the movie's 30 Hz is approximated by the host's step).
    pub fn update(&mut self) -> Result<(), String> {
        self.vm.begin_update();
        self.bindings.movie.advance(&mut self.vm)?;
        self.drain()?;
        self.vm
            .collect(self.bindings.movie.instances.keys().copied())
    }
    /// The highlighted entry's demo clip, when it has one.
    pub fn clip(&self) -> Option<&str> {
        self.bindings
            .item()
            .and_then(|n| n.clip.as_deref())
            .filter(|c| !c.is_empty())
    }
    pub fn draws(&self) -> Result<Vec<apt_scene::Draw>, String> {
        apt_scene::draw(&self.bindings.movie, &self.vm, &self.bindings.movie.shapes)
    }
}

#[cfg(test)]
#[path = "trick_guide_tests.rs"]
mod tests;
