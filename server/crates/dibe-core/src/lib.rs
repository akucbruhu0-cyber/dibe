pub mod engine;
pub mod live;
pub mod resample;

pub fn cs_to_seconds(cs: i64) -> f64 {
    cs as f64 / 100.0
}
