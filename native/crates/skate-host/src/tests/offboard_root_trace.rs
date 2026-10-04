// Upstream trace omitted from the headless host; frame.rs calls it in test builds.
use super::SkaterRuntime;

pub(crate) fn trace(_tick: u64, _phase: &str, _skater: &SkaterRuntime) {}
