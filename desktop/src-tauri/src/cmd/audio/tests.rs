use super::*;
use crate::ffmpeg::random_string;
use crate::recording::finalize::best_raw_capture;
use std::str::FromStr;

fn sample_names() -> Vec<String> {
    ["MacBook Mic", "Speakers", "AirPods"].map(String::from).to_vec()
}

#[test]
fn mark_defaults_marks_input_and_output_defaults() {
    let flags = mark_defaults(&sample_names(), Some("MacBook Mic"), Some("Speakers"));
    assert_eq!(flags, vec![true, true, false]);
}

#[test]
fn mark_defaults_tolerates_a_machine_without_a_default_input() {
    // A Mac mini has no microphone: the list still comes back, with only the output marked.
    assert_eq!(
        mark_defaults(&sample_names(), None, Some("Speakers")),
        vec![false, true, false]
    );
    assert_eq!(mark_defaults(&sample_names(), None, None), vec![false; 3]);
    assert!(mark_defaults(&[], None, None).is_empty());
}

#[test]
fn device_id_roundtrips_through_string() {
    // CoreAudio UIDs contain colons of their own; only the first one separates the host.
    let id = DeviceId::new(cpal::default_host().id(), "AppleUSBAudioEngine:Vendor:Mic:1234:1");
    assert_eq!(DeviceId::from_str(&id.to_string()).expect("parses back"), id);
}

#[test]
fn missing_device_is_reported_by_name() {
    let host = cpal::default_host();
    let gone = AudioDevice {
        is_default: false,
        is_input: true,
        id: DeviceId::new(host.id(), "no-such-device").to_string(),
        name: "AirPods".to_string(),
    };
    let error = start_error(find_device(&host, &gone).expect_err("nothing has this id"));
    assert_eq!(error.code, "device_unavailable");
    assert!(error.message.contains("AirPods"));

    // An index saved by an older version is not a device id at all.
    let legacy = AudioDevice {
        id: "2".to_string(),
        ..gone
    };
    let error = start_error(find_device(&host, &legacy).expect_err("an index is no id"));
    assert_eq!(error.code, "device_unavailable");
}

#[test]
fn other_start_failures_stay_internal() {
    let error = start_error(eyre!("disk full"));
    assert_eq!(error.code, "internal_error");
    assert!(error.message.contains("disk full"));
}

#[test]
fn discarded_recording_leaves_nothing_to_recover() {
    let path = std::env::temp_dir().join(format!("vibe-discard-{}.wav", random_string(8)));
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: 48_000,
        bits_per_sample: 32,
        sample_format: hound::SampleFormat::Float,
    };
    let (mut tx, rx) = capture::ring(&spec);
    let writer = spawn_writer(path.clone(), spec, rx, CHECKPOINT_EVERY, || {}, None).expect("spawn");
    // A first device was already recording when the second one failed to start.
    capture::push_samples(&[0.5f32; 4_800], 1, &mut tx, &writer.dropped);
    let mut recording = ActiveRecording::new("session".to_string(), RecordingOrigin::Meeting, None);
    recording.writers.push(writer);
    recording.wav_paths.push(path.clone());

    discard_recording(recording);

    // Left in the in-progress folder it would be recovered at the next launch as a crash.
    assert!(!path.exists());
}

#[test]
fn stream_error_finishes_session_with_audio() {
    let path = std::env::temp_dir().join(format!("vibe-stream-error-{}.wav", random_string(8)));
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: 48_000,
        bits_per_sample: 32,
        sample_format: hound::SampleFormat::Float,
    };
    let (mut tx, rx) = capture::ring(&spec);
    let writer = spawn_writer(path.clone(), spec, rx, CHECKPOINT_EVERY, || {}, None).expect("spawn");
    let state = RecordingState::default();
    let session_id = state.try_begin(RecordingOrigin::Meeting).expect("begin");
    // The device delivered 0.1 s of audio and was unplugged: cpal calls the error callback and
    // never the data callback again.
    capture::push_samples(&[0.5f32; 4_800], 1, &mut tx, &writer.dropped);
    drop(tx);
    let mut recording = ActiveRecording::new(session_id.clone(), RecordingOrigin::Meeting, None);
    recording.writers.push(writer);
    recording.wav_paths.push(path.clone());
    assert!(state.install(recording).is_ok());

    // What the error callback does: take the session, close its captures, keep what was saved
    // and carry the failure as a warning.
    let recording = state.take(&session_id).expect("the failure ends the active session");
    let closed = close_captures(recording.streams, recording.writers, recording.wav_paths);
    let saved = best_raw_capture(&closed.files).expect("the audio before the failure was saved");
    let failure = "The recording device stopped (Device disconnected)".to_string();
    let (event, payload) = recording_event(&session_id, recording.origin, Some(&saved), vec![failure.clone()], None);

    assert_eq!(closed.files, vec![(path.clone(), 4_800)]);
    assert_eq!(hound::WavReader::open(&path).expect("saved WAV").len(), 4_800);
    assert_eq!(event, "record_finish", "saved audio becomes a project");
    assert_eq!(payload["path"], path.to_string_lossy().as_ref());
    assert_eq!(payload["origin"], "meeting");
    assert!(payload["warning"]
        .as_str()
        .is_some_and(|warning| warning.contains("Device disconnected")));
    // A second failing device finds no session left to end.
    assert!(state.take(&session_id).is_none());
    // Had the device failed before delivering any audio, there would be no project: an error says why.
    let (event, payload) = recording_event(&session_id, recording.origin, None, vec![failure], None);
    assert_eq!(event, "record_error");
    assert!(payload["message"]
        .as_str()
        .is_some_and(|message| message.contains("Device disconnected")));
    std::fs::remove_file(path).ok();
}

#[test]
fn recording_without_live_finishes_as_before() {
    let dir = std::env::temp_dir().join(format!("vibe-no-live-{}", random_string(8)));
    std::fs::create_dir_all(&dir).expect("temp dir");
    let path = dir.join("capture.wav");
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: 48_000,
        bits_per_sample: 32,
        sample_format: hound::SampleFormat::Float,
    };
    let mut recording = ActiveRecording::new("session".to_string(), RecordingOrigin::Manual, Some("talk".to_string()));
    // What start_streams hands each writer when the recording did not ask for live.
    let live = recording.live.as_ref().map(|live| live.feed(0));
    assert!(live.is_none(), "no client, so no tap");
    let (mut tx, rx) = capture::ring(&spec);
    let writer = spawn_writer(path.clone(), spec, rx, CHECKPOINT_EVERY, || {}, live).expect("spawn");
    capture::push_samples(&[0.5f32; 4_800], 1, &mut tx, &writer.dropped);
    recording.writers.push(writer);
    recording.wav_paths.push(path);

    let (event, payload) = finished_event(recording, None, &dir);

    assert_eq!(event, "record_finish");
    assert_eq!(payload.get("liveSegments"), Some(&Value::Null));
    let saved = PathBuf::from(payload["path"].as_str().expect("a path"));
    assert!(saved.starts_with(&dir) && saved.is_file());
    std::fs::remove_dir_all(dir).ok();
}

fn said(text: &str) -> Vec<Segment> {
    vec![Segment {
        start: 0,
        stop: 100,
        text: text.to_string(),
        speaker: None,
    }]
}

#[test]
fn captures_stay_recoverable_while_the_live_finals_are_awaited() {
    let dir = std::env::temp_dir().join(format!("vibe-live-stop-{}", random_string(8)));
    let out_dir = dir.join("out");
    std::fs::create_dir_all(&out_dir).expect("temp dirs");
    let path = dir.join("capture.wav");
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: 48_000,
        bits_per_sample: 32,
        sample_format: hound::SampleFormat::Float,
    };
    let mut recording = ActiveRecording::new("session".to_string(), RecordingOrigin::Manual, None);
    let (mut tx, rx) = capture::ring(&spec);
    let writer = spawn_writer(path.clone(), spec, rx, CHECKPOINT_EVERY, || {}, None).expect("spawn");
    capture::push_samples(&[0.5f32; 4_800], 1, &mut tx, &writer.dropped);
    recording.writers.push(writer);
    recording.wav_paths.push(path.clone());
    // The stop waits for the last finals; an app quit during that wait must still find the
    // capture in the in-progress folder, where the next launch recovers it.
    let capture = path.clone();
    let task = tauri::async_runtime::spawn(async move {
        for _ in 0..100 {
            if !capture.exists() {
                return said("finalized while waiting");
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        said("recoverable")
    });
    recording.live = Some(LiveClient::with_task(task));

    let (event, payload) = finished_event(recording, None, &out_dir);

    assert_eq!(event, "record_finish");
    assert_eq!(payload["liveSegments"][0]["text"], "recoverable");
    assert!(!path.exists(), "finalized once the finals were in");
    std::fs::remove_dir_all(dir).ok();
}
