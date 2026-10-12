use super::*;

/// Packed words from SK83_TPOSE against the same bones of the stock
/// RIG_TPOSE (VBR, decoded independently).
#[test]
fn packed_rotation_matches_the_stock_rest_pose() {
    for (word, expected) in [
        (0x4000_006d, [0.0, 0.0, -0.053, 0.999]),
        (0xf39f_f8d4, [0.0, 0.0, 0.103, 0.995]),
        (0xc2ae_6639, [-0.049, 0.767, -0.121, 0.627]),
        (0x7f8f_e5a8, [0.707, -0.007, -0.007, 0.707]),
        (0xa3d5_c6b4, [0.45, 0.545, 0.45, 0.545]),
    ] {
        let q = packed_rotation(word);
        let e = Quat::from_array(expected);
        assert!(q.dot(e).abs() > 0.999, "{word:08x}: {q} vs {e}");
    }
}

/// Opt-in: DEMO_CLIPS=<folder of trickguide_*.abin>.
#[test]
#[ignore]
fn kickflip_stands_on_its_board() {
    let Ok(dir) = std::env::var("DEMO_CLIPS") else {
        return;
    };
    let mut library = Library::new(PathBuf::from(dir));
    let demo = library.load("trickguide_kickflip_01").unwrap();
    assert_eq!(demo.clip, "TRICKGUIDE_KICKFLIP_01");
    let bones = demo.sample(0.0);
    let at = |n: &str| demo.bone(&bones, n).unwrap().w_axis.truncate();
    let (head, foot, board) = (at("HEAD"), at("LEFTFOOT"), at("SKATEBOARD_ROOT"));
    println!(
        "{} frames at {} fps; head {head} foot {foot} board {board}",
        demo.frames.len(),
        demo.fps
    );
    assert!((head.y - 1.62).abs() < 0.05 && (foot.y - 0.21).abs() < 0.05 && board.y.abs() < 0.12);
    // Mid-air: the board leaves the ground.
    let air = demo.sample(48.0 / demo.fps);
    assert!(demo.bone(&air, "SKATEBOARD_ROOT").unwrap().w_axis.y > 0.5);
}
