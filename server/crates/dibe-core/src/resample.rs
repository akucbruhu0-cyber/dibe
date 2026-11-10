//! Mono PCM at any rate to the engines' 16 kHz, through a Blackman-windowed sinc low-passed below the
//! lower Nyquist rate, so nothing above 8 kHz folds back into the speech band when downsampling.

const RATE: u32 = vad_rs::SAMPLE_RATE as u32;
/// Sinc zero crossings on each side of the kernel: its length, and so how steep the cutoff is.
const ZEROS: f64 = 24.0;
/// The cutoff as a fraction of the lower Nyquist rate, so the transition band ends about at it.
const ROLLOFF: f64 = 0.9;
/// Fractional positions snap to this grid when the two rates share no small ratio (an odd source
/// rate); the timing error stays under 1/2048 of an input sample.
const MAX_PHASES: u64 = 1024;

pub fn resample(pcm: &[f32], from_rate: u32) -> Vec<f32> {
    let mut resampler = Resampler::new(from_rate);
    let mut out = resampler.push(pcm);
    out.extend(resampler.finish());
    out
}

/// [`resample`] over audio that arrives in blocks: the output of all the blocks is exactly that of
/// the whole, because the kernel's reach of input is kept from one block to the next.
pub struct Resampler {
    step: u64,
    exact: u64,
    phases: u64,
    half: i64,
    taps: usize,
    table: Vec<f32>,
    /// Input from absolute index `offset` on; what no output still needs is dropped.
    buffer: Vec<f32>,
    offset: u64,
    /// The next output sample.
    next: u64,
    passthrough: bool,
}

impl Resampler {
    pub fn new(from_rate: u32) -> Self {
        assert!(from_rate > 0, "sample rate 0");
        // Output sample n sits at input position n * step / exact.
        let divisor = gcd(from_rate, RATE);
        let (step, exact) = (u64::from(from_rate / divisor), u64::from(RATE / divisor));
        let phases = exact.min(MAX_PHASES);
        // Cycles per input sample.
        let cutoff = 0.5 * ROLLOFF * (f64::from(RATE) / f64::from(from_rate)).min(1.0);
        let reach = ZEROS / (2.0 * cutoff);
        let half = reach.ceil() as i64;
        let passthrough = from_rate == RATE;
        let table = if passthrough {
            Vec::new()
        } else {
            (0..phases)
                .flat_map(|phase| kernel(phase as f64 / phases as f64, half, cutoff, reach))
                .collect()
        };
        Self {
            step,
            exact,
            phases,
            half,
            taps: (2 * half + 1) as usize,
            table,
            buffer: Vec::new(),
            offset: 0,
            next: 0,
            passthrough,
        }
    }

    /// The output that `pcm` and the blocks before it complete.
    pub fn push(&mut self, pcm: &[f32]) -> Vec<f32> {
        if self.passthrough {
            return pcm.to_vec();
        }
        self.buffer.extend_from_slice(pcm);
        let total = self.offset + self.buffer.len() as u64;
        let mut out = Vec::new();
        loop {
            let (base, phase) = self.position(self.next);
            if base as i64 + self.half >= total as i64 {
                break;
            }
            out.push(self.sample(base, phase));
            self.next += 1;
        }
        let (base, _) = self.position(self.next);
        let keep_from = (base as i64 - self.half).max(self.offset as i64) as u64;
        self.buffer.drain(..(keep_from - self.offset) as usize);
        self.offset = keep_from;
        out
    }

    /// The rest of the output, with silence past the end of the input.
    pub fn finish(self) -> Vec<f32> {
        if self.passthrough {
            return Vec::new();
        }
        let total = self.offset + self.buffer.len() as u64;
        let out_len = (total * self.exact).div_ceil(self.step);
        (self.next..out_len)
            .map(|n| {
                let (base, phase) = self.position(n);
                self.sample(base, phase)
            })
            .collect()
    }

    fn position(&self, n: u64) -> (u64, u64) {
        let position = n * self.step;
        let mut base = position / self.exact;
        let mut phase = (position % self.exact * self.phases + self.exact / 2) / self.exact;
        if phase == self.phases {
            base += 1;
            phase = 0;
        }
        (base, phase)
    }

    fn sample(&self, base: u64, phase: u64) -> f32 {
        let coefficients = &self.table[phase as usize * self.taps..][..self.taps];
        // Relative to the buffer; negative before the start of the input, which is silence.
        let first = base as i64 - self.half - self.offset as i64;
        if first >= 0 && first as usize + self.taps <= self.buffer.len() {
            dot(&self.buffer[first as usize..][..self.taps], coefficients)
        } else {
            coefficients
                .iter()
                .enumerate()
                .filter_map(|(tap, coefficient)| {
                    let index = usize::try_from(first + tap as i64).ok()?;
                    Some(self.buffer.get(index)? * coefficient)
                })
                .sum()
        }
    }
}

/// Taps for an output that falls `fraction` of a sample past input `base`; tap j weighs input
/// `base - half + j`. Normalized to unit gain at DC, so loudness is kept exactly.
fn kernel(fraction: f64, half: i64, cutoff: f64, reach: f64) -> Vec<f32> {
    let taps: Vec<f64> = (0..=2 * half)
        .map(|tap| {
            let t = fraction + (half - tap) as f64;
            let u = t / reach;
            if u.abs() >= 1.0 {
                return 0.0;
            }
            let x = 2.0 * cutoff * t;
            let sinc = if x == 0.0 {
                1.0
            } else {
                (std::f64::consts::PI * x).sin() / (std::f64::consts::PI * x)
            };
            let blackman = 0.42 + 0.5 * (std::f64::consts::PI * u).cos() + 0.08 * (2.0 * std::f64::consts::PI * u).cos();
            sinc * blackman
        })
        .collect();
    let gain: f64 = taps.iter().sum();
    taps.iter().map(|tap| (tap / gain) as f32).collect()
}

/// Eight running sums instead of one, so the compiler can vectorize what float ordering forbids it to.
fn dot(samples: &[f32], coefficients: &[f32]) -> f32 {
    let mut lanes = [0.0f32; 8];
    let (chunks, rest) = samples.as_chunks::<8>();
    let (coefficient_chunks, coefficient_rest) = coefficients.as_chunks::<8>();
    for (chunk, coefficient_chunk) in chunks.iter().zip(coefficient_chunks) {
        for lane in 0..8 {
            lanes[lane] += chunk[lane] * coefficient_chunk[lane];
        }
    }
    let tail: f32 = rest
        .iter()
        .zip(coefficient_rest)
        .map(|(sample, coefficient)| sample * coefficient)
        .sum();
    lanes.iter().sum::<f32>() + tail
}

fn gcd(mut a: u32, mut b: u32) -> u32 {
    while b != 0 {
        (a, b) = (b, a % b);
    }
    a
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(frequency: f64, rate: u32, seconds: f64) -> Vec<f32> {
        (0..(seconds * f64::from(rate)) as usize)
            .map(|n| (0.5 * (2.0 * std::f64::consts::PI * frequency * n as f64 / f64::from(rate)).sin()) as f32)
            .collect()
    }

    /// Amplitude of `frequency` in the output, and what is left once that sinusoid is taken out,
    /// both over the middle of the signal so the edges' zero padding does not count.
    fn fit(output: &[f32], frequency: f64) -> (f64, f64) {
        let middle = &output[output.len() / 4..output.len() * 3 / 4];
        let omega = |n: usize| 2.0 * std::f64::consts::PI * frequency * (n + output.len() / 4) as f64 / f64::from(RATE);
        let (mut sin, mut cos) = (0.0, 0.0);
        for (n, sample) in middle.iter().enumerate() {
            sin += f64::from(*sample) * omega(n).sin();
            cos += f64::from(*sample) * omega(n).cos();
        }
        let (sin, cos) = (2.0 * sin / middle.len() as f64, 2.0 * cos / middle.len() as f64);
        let residual = middle
            .iter()
            .enumerate()
            .map(|(n, sample)| (f64::from(*sample) - sin * omega(n).sin() - cos * omega(n).cos()).powi(2))
            .sum::<f64>()
            / middle.len() as f64;
        (sin.hypot(cos), residual.sqrt())
    }

    fn rms(output: &[f32]) -> f64 {
        let middle = &output[output.len() / 4..output.len() * 3 / 4];
        (middle.iter().map(|sample| f64::from(*sample).powi(2)).sum::<f64>() / middle.len() as f64).sqrt()
    }

    #[test]
    fn a_tone_in_the_speech_band_keeps_its_frequency_and_amplitude() {
        for rate in [48_000, 44_100, 22_050, 8_000, 11_025, 44_056] {
            let output = resample(&sine(1_000.0, rate, 1.0), rate);
            assert_eq!(output.len(), 16_000, "{rate}");
            let (amplitude, residual) = fit(&output, 1_000.0);
            assert!((amplitude - 0.5).abs() < 0.005, "{rate}: amplitude {amplitude}");
            assert!(residual < 1e-3, "{rate}: residual {residual}");
        }
    }

    #[test]
    fn tones_above_8khz_do_not_alias_into_the_output() {
        for (rate, frequency) in [(48_000, 12_000.0), (48_000, 9_000.0), (44_100, 10_000.0), (22_050, 10_500.0)] {
            let level = rms(&resample(&sine(frequency, rate, 1.0), rate));
            assert!(level < 0.5e-3, "{rate} Hz, {frequency} Hz tone: rms {level}");
        }
    }

    #[test]
    fn the_band_edge_is_rolled_off() {
        let (amplitude, _) = fit(&resample(&sine(7_000.0, 48_000, 1.0), 48_000), 7_000.0);
        assert!(amplitude < 0.45 && amplitude > 0.1, "7 kHz amplitude {amplitude}");
    }

    #[test]
    fn blocks_give_exactly_the_output_of_the_whole() {
        let mut noise = 0x2545_f491_u32;
        for rate in [48_000, 44_100, 22_050, 8_000, 11_025, 44_056, RATE] {
            let input: Vec<f32> = (0..rate as usize * 3)
                .map(|_| {
                    noise ^= noise << 13;
                    noise ^= noise >> 17;
                    noise ^= noise << 5;
                    noise as f32 / u32::MAX as f32 - 0.5
                })
                .collect();
            let whole = resample(&input, rate);
            for block in [1, 7, 480, 4_801, rate as usize] {
                let mut resampler = Resampler::new(rate);
                let mut blocks: Vec<f32> = input.chunks(block).flat_map(|chunk| resampler.push(chunk)).collect();
                blocks.extend(resampler.finish());
                assert!(blocks == whole, "{rate} Hz in blocks of {block}");
            }
        }
    }

    #[test]
    fn sixteen_khz_passes_through() {
        let input = sine(1_000.0, RATE, 0.1);
        assert_eq!(resample(&input, RATE), input);
    }
}
