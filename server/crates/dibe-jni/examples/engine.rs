//! Drives the functions behind the JNI exports on a device, without the app:
//! `engine <model> <vad.bin> <audio.wav> [language] [--live]`. A 16-bit WAV at any rate goes
//! through the resampler; segments (or live events) go to stdout as the app receives them.

use std::time::Instant;

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let live = args.iter().any(|arg| arg == "--live");
    let args: Vec<&str> = args.iter().map(String::as_str).filter(|arg| *arg != "--live").collect();
    let [model, vad, wav, rest @ ..] = args.as_slice() else {
        anyhow::bail!("usage: engine <model> <vad.bin> <audio.wav> [language] [--live]");
    };
    let language = rest.first().copied().unwrap_or("auto");

    let mut reader = hound::WavReader::open(wav)?;
    let spec = reader.spec();
    let interleaved = reader
        .samples::<i16>()
        .map(|sample| Ok(f32::from(sample?) / 32768.0))
        .collect::<anyhow::Result<Vec<f32>>>()?;
    let channels = usize::from(spec.channels);
    let mono: Vec<f32> = interleaved
        .chunks_exact(channels)
        .map(|frame| frame.iter().sum::<f32>() / channels as f32)
        .collect();
    let started = Instant::now();
    let pcm = dibe_core::resample::resample(&mono, spec.sample_rate);
    eprintln!("resample {} Hz: {} ms", spec.sample_rate, started.elapsed().as_millis());

    let started = Instant::now();
    let mut model = dibe::Model::load(model, vad)?;
    eprintln!("load: {} ms", started.elapsed().as_millis());

    let started = Instant::now();
    if live {
        let mut session = model.live(language, true)?;
        for chunk in pcm.chunks(vad_rs::SAMPLE_RATE / 2) {
            session.push(chunk);
            for event in session.advance(&mut model)? {
                println!("{}", serde_json::to_string(&event)?);
            }
        }
        for event in session.finish(&mut model)? {
            println!("{}", serde_json::to_string(&event)?);
        }
    } else {
        let segments = model.transcribe(&pcm, language, whisper_rs::StreamCallbacks::default())?;
        println!("{}", dibe::segments_json(&segments)?);
    }
    eprintln!(
        "transcribe {:.1} s of audio: {} ms",
        pcm.len() as f64 / vad_rs::SAMPLE_RATE as f64,
        started.elapsed().as_millis()
    );
    Ok(())
}
