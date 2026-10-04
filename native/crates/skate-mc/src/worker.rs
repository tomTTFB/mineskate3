//! One Skate session per world, on its own thread, as the mashup runs it.
//! Leaving skate mode only pauses the session: decoded animation banks, graphs
//! and the board stay resident so the next toggle is instant.
use crate::{hud::Hud, mesh::{self, Meshes}, rails, retarget};
use bevy::math::{Mat4, Vec3};
use skate_host::bridge::{InputFrame, PreparedCollision, Pose, Session, Status as SkaterStatus};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, mpsc};

pub type Triangle = [[f32; 3]; 3];

/// Layout of the float array `pose` fills.
pub mod layout {
    pub const ROOT: usize = 0; // 16, column-major
    pub const CAMERA_POSITION: usize = 16; // 3
    pub const CAMERA_BASIS: usize = 19; // 9: right, up, forward columns
    pub const CAMERA_FOV: usize = 28; // vertical degrees
    pub const CAMERA_VALID: usize = 29; // 1.0 when the camera fields are set
    pub const VELOCITY: usize = 30; // 3, metres per second
    pub const PARTS: usize = 33; // 6 x 16: head, body, right arm, left arm, right leg, left leg
    /// 16: world matrix taking the unit cube onto the board's box.
    pub const BOARD: usize = PARTS + 16 * super::retarget::PARTS;
    /// 10: physical state id, wheel contacts, impact speed, wiping out,
    /// marker visible, can place, can return, return progress, markers
    /// placed, markers returned.
    pub const STATUS: usize = BOARD + 16;
    pub const LEN: usize = STATUS + 10;
}

#[derive(Clone, Copy, Default)]
pub struct Pad {
    pub buttons: u16,
    pub triggers: [u8; 2],
    pub left: [i16; 2],
    pub right: [i16; 2],
}

enum Job {
    Collision(u64, Vec<Triangle>, Option<[f32; 4]>),
    Activate { spawn: [f32; 3], heading: f32, aspect: f32 },
    Step { dt: f32, pad: Option<Pad>, aspect: f32 },
    Suspend,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Loading = 0,
    Ready = 1,
    Active = 2,
    Failed = -1,
}

pub struct Shared {
    pub status: Status,
    pub error: String,
    /// Bumped whenever `out` holds a newer pose.
    pub generation: u64,
    pub tick: u64,
    pub out: Vec<f32>,
    pub state: String,
    pub meshes: Option<Arc<Meshes>>,
    /// World (session space) bones for `Meshes::used`, 12 floats each.
    pub bones: Vec<f32>,
    pub rails: usize,
    /// 1 when the original trick HUD runs, 0 when its data is missing,
    /// -1 when it stopped on an error (see `hud_error`).
    pub hud_status: i32,
    pub hud_error: String,
    pub hud_textures: Vec<(String, [u32; 2])>,
    /// The HUD's current draw list (see `Hud::draws`).
    pub hud_draws: Vec<f32>,
}

pub struct Host {
    send: mpsc::Sender<Job>,
    pub shared: Arc<Mutex<Shared>>,
    collision_seq: u64,
}

/// Far below anything: the session needs some collision before the first
/// real blocks arrive.
const PLACEHOLDER: Triangle = [
    [-1.0, -10_000.0, -1.0],
    [-1.0, -10_000.0, 1.0],
    [1.0, -10_000.0, 1.0],
];

impl Host {
    pub fn start(root: PathBuf) -> Result<Self, String> {
        let shared = Arc::new(Mutex::new(Shared {
            status: Status::Loading,
            error: String::new(),
            generation: 0,
            tick: 0,
            out: vec![0.0; layout::LEN],
            state: String::new(),
            meshes: None,
            bones: Vec::new(),
            rails: 0,
            hud_status: 0,
            hud_error: String::new(),
            hud_textures: Vec::new(),
            hud_draws: Vec::new(),
        }));
        let (send, receive) = mpsc::channel();
        let thread_shared = Arc::clone(&shared);
        std::thread::Builder::new()
            .name("mineskate3-session".into())
            .stack_size(32 * 1024 * 1024)
            .spawn(move || {
                let result = run(root, receive, &thread_shared);
                let mut shared = thread_shared.lock().unwrap();
                if let Err(e) = result {
                    shared.status = Status::Failed;
                    shared.error = e;
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(Self {
            send,
            shared,
            collision_seq: 0,
        })
    }

    pub fn collision(&mut self, triangles: Vec<Triangle>, inside: Option<[f32; 4]>) {
        self.collision_seq += 1;
        let _ = self
            .send
            .send(Job::Collision(self.collision_seq, triangles, inside));
    }

    pub fn activate(&self, spawn: [f32; 3], heading: f32, aspect: f32) {
        let _ = self.send.send(Job::Activate {
            spawn,
            heading,
            aspect,
        });
    }

    pub fn step(&self, dt: f32, pad: Option<Pad>, aspect: f32) {
        let _ = self.send.send(Job::Step { dt, pad, aspect });
    }

    pub fn suspend(&self) {
        let _ = self.send.send(Job::Suspend);
    }
}

type Built = (u64, Result<(PreparedCollision, usize), String>);

fn run(root: PathBuf, receive: mpsc::Receiver<Job>, shared: &Mutex<Shared>) -> Result<(), String> {
    let started = std::time::Instant::now();
    let mut session = Session::new(&root, vec![PLACEHOLDER], vec![], [0.0; 3], 0.0)?;
    let meshes = mesh::shared(&root).map_err(|e| format!("Skater model: {e}"))?;
    // The trick HUD is optional: older conversions do not include it.
    let mut hud = match Hud::load(&root, &session.scoring()) {
        Ok(hud) => {
            let mut s = shared.lock().unwrap();
            s.hud_status = 1;
            s.hud_textures = hud.textures.clone();
            Some(hud)
        }
        Err(e) => {
            eprintln!("[mineskate3] trick HUD unavailable: {e}");
            let mut s = shared.lock().unwrap();
            s.hud_status = 0;
            s.hud_error = e;
            None
        }
    };
    let hud_failed = |e: String, hud: &mut Option<Hud>| {
        eprintln!("[mineskate3] trick HUD stopped: {e}");
        let mut s = shared.lock().unwrap();
        s.hud_status = -1;
        s.hud_error = e;
        s.hud_draws.clear();
        *hud = None;
    };
    eprintln!(
        "[mineskate3] Skate session loaded in {}ms",
        started.elapsed().as_millis()
    );
    {
        let mut s = shared.lock().unwrap();
        s.meshes = Some(meshes);
        s.status = Status::Ready;
    }

    // Block collision streams in: rails are found and the collision world
    // built off this thread, then swapped in between steps.
    let builder = session.collision_builder();
    let (build_send, build_jobs) = mpsc::channel::<(u64, Vec<Triangle>, Option<[f32; 4]>)>();
    let (built_send, built) = mpsc::channel::<Built>();
    std::thread::Builder::new()
        .name("mineskate3-collision".into())
        .stack_size(16 * 1024 * 1024)
        .spawn(move || {
            while let Ok(mut job) = build_jobs.recv() {
                while let Ok(newer) = build_jobs.try_recv() {
                    job = newer;
                }
                let (seq, mut triangles, inside) = job;
                if triangles.is_empty() {
                    triangles.push(PLACEHOLDER);
                }
                let (found, _) = rails::find_skate(&triangles, inside);
                let count = found.len();
                let result = builder.build(triangles, found).map(|p| (p, count));
                if built_send.send((seq, result)).is_err() {
                    break;
                }
            }
        })
        .map_err(|e| e.to_string())?;

    let mut requested = 0u64;
    let mut installed = 0u64;
    let install = |session: &mut Session, (seq, result): Built, installed: &mut u64| {
        *installed = (*installed).max(seq);
        match result {
            Ok((prepared, rails)) => {
                session.install_collision(prepared)?;
                shared.lock().unwrap().rails = rails;
            }
            Err(e) => eprintln!("[mineskate3] block collision: {e}"),
        }
        Ok::<(), String>(())
    };

    let mut active = false;
    let mut accumulated = 0.0f32;
    let mut packet = 0u32;
    while let Ok(job) = receive.recv() {
        match job {
            Job::Collision(seq, triangles, inside) => {
                requested = seq;
                if build_send.send((seq, triangles, inside)).is_err() {
                    return Err("Skate collision thread stopped".into());
                }
            }
            Job::Activate {
                spawn,
                heading,
                aspect,
            } => {
                // The skater must land on the blocks around the spawn, not
                // whatever collision was there before.
                while installed < requested {
                    let next = built
                        .recv()
                        .map_err(|_| "Skate collision thread stopped".to_string())?;
                    install(&mut session, next, &mut installed)?;
                }
                accumulated = 0.0;
                session.set_aspect_ratio(aspect);
                let pose = session.activate(spawn, heading)?;
                // Data added since the session loaded (the setup's "Add
                // trick HUD") is picked up on the next toggle.
                if hud.is_none() && shared.lock().unwrap().hud_status == 0 {
                    if let Ok(loaded) = Hud::load(&root, &session.scoring()) {
                        let mut s = shared.lock().unwrap();
                        s.hud_status = 1;
                        s.hud_error.clear();
                        s.hud_textures = loaded.textures.clone();
                        hud = Some(loaded);
                    }
                }
                if let Some(h) = hud.as_mut()
                    && let Err(e) = h.reset(&session.scoring())
                {
                    hud_failed(e, &mut hud);
                }
                active = true;
                publish(shared, &pose, session.status(), true)?;
            }
            Job::Suspend => {
                active = false;
                accumulated = 0.0;
                session.suspend_input();
                let mut s = shared.lock().unwrap();
                if s.status == Status::Active {
                    s.status = Status::Ready;
                }
            }
            Job::Step { dt, pad, aspect } => {
                if !active {
                    continue;
                }
                while let Ok(next) = built.try_recv() {
                    install(&mut session, next, &mut installed)?;
                }
                packet = packet.wrapping_add(1);
                let frame = pad.map_or_else(InputFrame::neutral, |p| {
                    InputFrame::from_pad(p.buttons, p.triggers, p.left, p.right, packet)
                });
                session.set_aspect_ratio(aspect);
                session.collect(frame, dt);
                accumulated = (accumulated + dt).min(0.15);
                let mut advanced = false;
                // The native camera can change the simulation period.
                while accumulated >= session.period() {
                    accumulated -= session.period();
                    session.advance()?;
                    // The movie steps once per simulation tick, as in skate-game.
                    if let Some(h) = hud.as_mut()
                        && let Err(e) = h.update(&session.scoring())
                    {
                        hud_failed(e, &mut hud);
                    }
                    advanced = true;
                }
                if advanced {
                    publish(shared, &session.pose(), session.status(), false)?;
                    if let Some(h) = hud.as_ref() {
                        let mut draws = Vec::new();
                        match h.draws(&mut draws) {
                            Ok(()) => shared.lock().unwrap().hud_draws = draws,
                            Err(e) => hud_failed(e, &mut hud),
                        }
                    }
                }
            }
        }
    }
    Ok(())
}

fn put(out: &mut [f32], at: usize, m: Mat4) {
    out[at..at + 16].copy_from_slice(&m.to_cols_array());
}

fn publish(
    shared: &Mutex<Shared>,
    pose: &Pose,
    status: SkaterStatus,
    activated: bool,
) -> Result<(), String> {
    if !pose.root.is_finite() || pose.bones.iter().any(|b| !b.is_finite()) {
        return Err("Skate published a non-finite pose".into());
    }
    let meshes = shared.lock().unwrap().meshes.clone();
    let world = |name: &str| {
        pose.names
            .iter()
            .position(|n| n == name)
            .and_then(|i| pose.bones.get(i))
            .map(|bone| pose.root * *bone)
    };
    let mut out = vec![0.0; layout::LEN];
    put(&mut out, layout::ROOT, pose.root);
    if let Some((position, basis, fov)) = pose.camera {
        out[layout::CAMERA_POSITION..layout::CAMERA_POSITION + 3]
            .copy_from_slice(&position.to_array());
        out[layout::CAMERA_BASIS..layout::CAMERA_BASIS + 9].copy_from_slice(&basis.to_cols_array());
        out[layout::CAMERA_FOV] = fov;
        out[layout::CAMERA_VALID] = 1.0;
    }
    out[layout::VELOCITY..layout::VELOCITY + 3].copy_from_slice(&pose.velocity.to_array());
    let parts = retarget::pose(|name| world(name).map(|m| m.w_axis.truncate()))
        .unwrap_or([Mat4::ZERO; retarget::PARTS]);
    for (i, m) in parts.iter().enumerate() {
        put(&mut out, layout::PARTS + i * 16, *m);
    }
    let board_box = meshes.as_ref().and_then(|m| m.board_bounds_in("SKATEBOARD_ROOT"));
    let board_matrix = match (world("SKATEBOARD_ROOT"), board_box) {
        (Some(bone), Some(local)) => bone * local,
        _ => {
            Mat4::from_translation(pose.root.w_axis.truncate() + Vec3::Y * 0.08)
                * Mat4::from_scale(Vec3::new(0.2, 0.06, 0.8))
        }
    };
    put(&mut out, layout::BOARD, board_matrix);
    let st = status;
    out[layout::STATUS..layout::LEN].copy_from_slice(&[
        st.state as f32,
        f32::from(st.wheel_contacts),
        st.impact_speed,
        f32::from(u8::from(st.wiping_out)),
        f32::from(u8::from(st.marker_visible)),
        f32::from(u8::from(st.marker_can_place)),
        f32::from(u8::from(st.marker_can_return)),
        st.marker_progress,
        st.markers_placed as f32,
        st.markers_returned as f32,
    ]);
    let mut bones = Vec::new();
    if let Some(meshes) = &meshes {
        meshes.export_bones(world, &mut bones);
    }
    let mut s = shared.lock().unwrap();
    s.out = out;
    s.bones = bones;
    s.tick = pose.tick;
    s.state.clone_from(&pose.state);
    s.generation += 1;
    if activated {
        s.status = Status::Active;
    }
    Ok(())
}
