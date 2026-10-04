//! Poses Minecraft's player model from the Skate skeleton.
//!
//! The vanilla model has six rigid boxes. Each one is aimed along the matching
//! Skate limb using joint positions only, so no assumptions about Skate bone
//! axes are needed: the box's long axis follows the limb and its sideways axis
//! follows the skater's left. Lengths are stretched to the skater's own limbs
//! so hands and feet land where the animation puts them.
//!
//! Output matrices map a model part's local space (pixels, the vanilla model's
//! y-down convention, pivot at the origin) into world space.
use bevy::math::{Mat4, Vec3, Vec4};

pub const HEAD: usize = 0;
pub const BODY: usize = 1;
pub const RIGHT_ARM: usize = 2;
pub const LEFT_ARM: usize = 3;
pub const RIGHT_LEG: usize = 4;
pub const LEFT_LEG: usize = 5;
pub const PARTS: usize = 6;

const PIXEL: f32 = 1.0 / 16.0;
/// Below the ankle joint, to the sole.
const ANKLE_TO_SOLE: f32 = 0.08;
/// Past the wrist joint, to the knuckles.
const WRIST_TO_KNUCKLE: f32 = 0.08;

/// Model part transform: local x to `left`, local y down the limb, local z to
/// the back, each scaled to pixels; `length_scale` stretches local y only.
fn part(pivot: Vec3, down: Vec3, left_hint: Vec3, length_scale: f32) -> Mat4 {
    let down = down.normalize_or(Vec3::NEG_Y);
    let mut left = left_hint - down * left_hint.dot(down);
    if left.length_squared() < 1e-6 {
        left = down.any_orthonormal_vector();
    }
    let left = left.normalize();
    // (left, down, back) must stay right-handed, as the vanilla mapping
    // (+x left, +y down, +z back) is.
    let back = left.cross(down);
    Mat4::from_cols(
        (left * PIXEL).extend(0.0),
        (down * PIXEL * length_scale).extend(0.0),
        (back * PIXEL).extend(0.0),
        Vec4::new(pivot.x, pivot.y, pivot.z, 1.0),
    )
}

/// `joint` gives a named Skate joint's world position.
pub fn pose(joint: impl Fn(&str) -> Option<Vec3>) -> Option<[Mat4; PARTS]> {
    let neck = joint("NECK")?;
    let head = joint("HEAD")?;
    let l_shoulder = joint("LEFTARM")?;
    let r_shoulder = joint("RIGHTARM")?;
    let l_hand = joint("LEFTHAND")?;
    let r_hand = joint("RIGHTHAND")?;
    let l_hip = joint("LEFTUPLEG")?;
    let r_hip = joint("RIGHTUPLEG")?;
    let l_foot = joint("LEFTFOOT")?;
    let r_foot = joint("RIGHTFOOT")?;

    let shoulders = (l_shoulder + r_shoulder) * 0.5;
    let hip_mid = (l_hip + r_hip) * 0.5;
    let chest_left = l_shoulder - r_shoulder;
    let hip_left = l_hip - r_hip;

    // Body: the vanilla torso runs 12px down from its pivot; the shoulder
    // pivots sit 2px below the top. Fit 2px..12px to shoulders..hips.
    let torso = hip_mid - shoulders;
    let body_scale = (torso.length() / (10.0 * PIXEL)).clamp(0.3, 3.0);
    let body_down = torso.normalize_or(Vec3::NEG_Y);
    let body_top = shoulders - body_down * (2.0 * PIXEL * body_scale);
    let body = part(body_top, torso, chest_left, body_scale);

    // Head: 8px up from the top of the body, aimed along the neck.
    let head_up = (head - neck).normalize_or(-body_down);
    let head_part = part(body_top, -head_up, chest_left, 1.0);

    // Arms: 10px below the shoulder pivot (the box starts 2px above it).
    let arm = |shoulder: Vec3, hand: Vec3| {
        let reach = hand - shoulder;
        let length = reach.length() + WRIST_TO_KNUCKLE;
        part(shoulder, reach, chest_left, (length / (10.0 * PIXEL)).clamp(0.3, 3.0))
    };
    // Legs: 12px below the hip pivot.
    let leg = |hip: Vec3, foot: Vec3| {
        let reach = foot - hip;
        let length = reach.length() + ANKLE_TO_SOLE;
        part(hip, reach, hip_left, (length / (12.0 * PIXEL)).clamp(0.3, 3.0))
    };

    let mut out = [Mat4::IDENTITY; PARTS];
    out[HEAD] = head_part;
    out[BODY] = body;
    out[RIGHT_ARM] = arm(r_shoulder, r_hand);
    out[LEFT_ARM] = arm(l_shoulder, l_hand);
    out[RIGHT_LEG] = leg(r_hip, r_foot);
    out[LEFT_LEG] = leg(l_hip, l_foot);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A T-posed skater facing +z: parts must reproduce vanilla standing axes.
    #[test]
    fn standing_skater_matches_vanilla_axes() {
        let joints = |name: &str| {
            Some(match name {
                "HIPS" => Vec3::new(0., 1.0, 0.),
                "NECK" => Vec3::new(0., 1.5, 0.),
                "HEAD" => Vec3::new(0., 1.6, 0.),
                // Facing +z, the skater's left is +x.
                "LEFTARM" => Vec3::new(0.3, 1.4, 0.),
                "RIGHTARM" => Vec3::new(-0.3, 1.4, 0.),
                "LEFTHAND" => Vec3::new(0.3, 0.85, 0.),
                "RIGHTHAND" => Vec3::new(-0.3, 0.85, 0.),
                "LEFTUPLEG" => Vec3::new(0.1, 0.95, 0.),
                "RIGHTUPLEG" => Vec3::new(-0.1, 0.95, 0.),
                "LEFTFOOT" => Vec3::new(0.1, 0.1, 0.),
                "RIGHTFOOT" => Vec3::new(-0.1, 0.1, 0.),
                _ => return None,
            })
        };
        let parts = pose(joints).unwrap();
        let body = parts[BODY];
        assert!(body.x_axis.truncate().normalize().abs_diff_eq(Vec3::X, 1e-4));
        assert!(body.y_axis.truncate().normalize().abs_diff_eq(Vec3::NEG_Y, 1e-4));
        assert!(body.z_axis.truncate().normalize().abs_diff_eq(Vec3::NEG_Z, 1e-4));
        // A leg's foot end (local y = 12px) lands at the sole.
        let sole = parts[LEFT_LEG].transform_point3(Vec3::new(0., 12., 0.));
        assert!(sole.abs_diff_eq(Vec3::new(0.1, 0.02, 0.), 1e-3), "{sole}");
        assert!(parts[HEAD].y_axis.truncate().normalize().abs_diff_eq(Vec3::NEG_Y, 1e-4));
    }
}
