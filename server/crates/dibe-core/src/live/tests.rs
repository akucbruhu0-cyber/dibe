use super::*;

const RATE: usize = vad_rs::SAMPLE_RATE;
/// What the desktop sends: half a second per push.
const PUSH: usize = RATE / 2;

fn silence(secs: f64) -> Vec<f32> {
    vec![0.0; (secs * RATE as f64) as usize]
}

fn speech(secs: f64) -> Vec<f32> {
    vec![0.5; (secs * RATE as f64) as usize]
}

/// Any non-zero sample is speech, read at a 10 ms resolution so three hours of pushes stay cheap.
fn energy_vad(samples: &[f32]) -> anyhow::Result<Vec<SpeechSegment>> {
    const STEP: usize = RATE / 100;
    let mut segments = Vec::new();
    let mut start = None;
    for (index, window) in samples.chunks(STEP).enumerate() {
        match (window[0] != 0.0, start) {
            (true, None) => start = Some(index * STEP),
            (false, Some(start_sample)) => {
                segments.push(SpeechSegment {
                    start_sample,
                    end_sample: index * STEP,
                });
                start = None;
            }
            _ => {}
        }
    }
    if let Some(start_sample) = start {
        segments.push(SpeechSegment {
            start_sample,
            end_sample: samples.len(),
        });
    }
    Ok(segments)
}

/// One segment over the whole input, named by its length.
fn echo(samples: &[f32], _prompt: Option<&str>) -> anyhow::Result<Vec<Segment>> {
    Ok(vec![Segment {
        start: 0,
        end: (samples.len() * 100 / RATE) as i64,
        text: format!("{} ms", samples.len() * 1000 / RATE),
        no_speech_prob: 0.0,
    }])
}

/// Pushes `audio` half a second at a time, the clock moving with the audio, and collects every event.
fn feed(
    core: &mut LiveCore,
    clock: &mut Instant,
    audio: &[f32],
    transcribe: &mut impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
) -> Vec<LiveEvent> {
    let mut events = Vec::new();
    for chunk in audio.chunks(PUSH) {
        core.push(chunk);
        *clock += Duration::from_millis((chunk.len() * 1000 / RATE) as u64);
        events.extend(core.advance(*clock, energy_vad, &mut *transcribe).unwrap());
    }
    events
}

/// The finals' times, in whole milliseconds.
fn finals(events: &[LiveEvent]) -> Vec<(u64, u64)> {
    let ms = |seconds: f64| (seconds * 1000.0).round() as u64;
    events
        .iter()
        .filter_map(|event| match event {
            LiveEvent::Final { start, end, .. } => Some((ms(*start), ms(*end))),
            _ => None,
        })
        .collect()
}

fn is_partial(event: &LiveEvent) -> bool {
    matches!(event, LiveEvent::Partial { .. })
}

#[test]
fn closed_utterance_becomes_final_with_absolute_times() {
    let mut core = LiveCore::new(None, true, false);
    let mut clock = Instant::now();
    let audio = [silence(10.0), silence(2.0), speech(2.0), silence(1.0)].concat();
    let events = feed(&mut core, &mut clock, &audio, &mut echo);
    assert_eq!(
        events,
        vec![LiveEvent::Final {
            start: 12.0,
            end: 14.0,
            text: "2000 ms".into()
        }]
    );
    assert_eq!(core.lag_ms(), 0, "no speech is waiting for text");
}

#[test]
fn open_utterance_partial_at_most_once_per_interval() {
    let mut core = LiveCore::new(None, true, true);
    let mut clock = Instant::now();
    let mut lags = Vec::new();
    let per_push: Vec<Vec<LiveEvent>> = speech(5.0)
        .chunks(PUSH)
        .map(|chunk| {
            let events = feed(&mut core, &mut clock, chunk, &mut echo);
            lags.push(core.lag_ms());
            events
        })
        .collect();
    // Lag is the speech after the latest partial's end, not the whole open utterance.
    assert_eq!(lags, [0, 500].repeat(5));
    // A push every half second and a partial at most every second: every other push.
    let pattern: Vec<bool> = per_push.iter().map(|events| events.iter().any(is_partial)).collect();
    assert_eq!(pattern, [true, false].repeat(5));
    assert!(per_push
        .iter()
        .all(|events| events.iter().filter(|e| is_partial(e)).count() <= 1));
    assert_eq!(
        per_push[8],
        vec![LiveEvent::Partial {
            start: 0.0,
            end: 4.5,
            text: "4500 ms".into()
        }]
    );
    assert!(finals(&per_push.concat()).is_empty(), "nothing closed yet");
}

#[test]
fn speech_over_20s_is_force_committed() {
    let mut core = LiveCore::new(None, true, false);
    let mut clock = Instant::now();
    let events = feed(&mut core, &mut clock, &speech(25.0), &mut echo);
    // Committed once it runs past 20 s, while the speaker is still talking.
    assert_eq!(finals(&events), vec![(0, 20_500)]);
    let events = feed(&mut core, &mut clock, &silence(1.0), &mut echo);
    assert_eq!(finals(&events), vec![(20_500, 25_000)]);

    // A pause too short to close the utterance is still where the forced cut falls.
    let mut core = LiveCore::new(None, true, false);
    let mut clock = Instant::now();
    let audio = [speech(12.0), silence(0.3), speech(12.0), silence(1.0)].concat();
    let events = feed(&mut core, &mut clock, &audio, &mut echo);
    assert_eq!(finals(&events), vec![(0, 12_000), (12_300, 24_300)]);
}

#[test]
fn buffer_is_bounded_over_three_hours() {
    let pushes = 21_600; // 3 h of half-second pushes
    let bound = RATE * 21;
    let speech_push = speech(0.5);
    let silence_push = silence(0.5);

    let mut core = LiveCore::new(Some("הקשר".into()), true, true);
    let mut clock = Instant::now();
    let mut finals_seen = 0;
    for index in 0..pushes {
        // 26 s of talk, 4 s of quiet, over and over: forced commits and closed utterances both.
        let talking = (index % 60) < 52;
        let chunk = if talking { &speech_push } else { &silence_push };
        let events = feed(&mut core, &mut clock, chunk, &mut echo);
        finals_seen += finals(&events).len();
        assert!(core.buffer.len() <= bound, "push {index}: {} samples", core.buffer.len());
    }
    assert!(finals_seen >= 2 * 360, "{finals_seen} finals in 360 cycles");
    assert!(core
        .prompt
        .as_ref()
        .is_some_and(|tail| tail.chars().count() <= PROMPT_TAIL_CHARS));

    let mut core = LiveCore::new(None, true, true);
    for index in 0..pushes {
        assert!(feed(&mut core, &mut clock, &silence_push, &mut echo).is_empty());
        assert!(core.buffer.len() <= bound, "push {index}: {} samples", core.buffer.len());
    }
    assert_eq!(core.lag_ms(), 0);
}

#[test]
fn slow_engine_skips_partials() {
    let mut slow = |samples: &[f32], prompt: Option<&str>| {
        std::thread::sleep(Duration::from_millis(PARTIAL_BUDGET_MS + 100));
        echo(samples, prompt)
    };
    let mut core = LiveCore::new(None, true, true);
    let mut clock = Instant::now();
    // One push that holds a whole utterance: its final is the first inference, and it is slow.
    let first = [speech(2.0), silence(1.0)].concat();
    core.push(&first);
    clock += Duration::from_secs(3);
    let mut events = core.advance(clock, energy_vad, &mut slow).unwrap();
    let mut lags = Vec::new();
    for chunk in [speech(3.0), silence(1.0)].concat().chunks(PUSH) {
        events.extend(feed(&mut core, &mut clock, chunk, &mut slow));
        lags.push(core.lag_ms());
    }
    assert!(!events.iter().any(is_partial), "{events:?}");
    assert_eq!(finals(&events), vec![(0, 2_000), (3_000, 6_000)]);
    // With no partials the text falls behind the speech, until the final catches up.
    assert_eq!(lags, [500, 1_000, 1_500, 2_000, 2_500, 3_000, 3_500, 0]);
}

#[test]
fn short_blips_are_dropped() {
    let mut calls = 0;
    let mut counting = |samples: &[f32], prompt: Option<&str>| {
        calls += 1;
        echo(samples, prompt)
    };
    let mut core = LiveCore::new(None, true, false);
    let mut clock = Instant::now();
    let audio = [silence(1.0), speech(0.2), silence(1.3), speech(0.4), silence(1.1)].concat();
    let events = feed(&mut core, &mut clock, &audio, &mut counting);
    assert_eq!(finals(&events), vec![(2_500, 2_900)]);
    assert_eq!(calls, 1, "the blip never reached the engine");
}

#[test]
fn whisper_prompt_is_tail_of_committed_text() {
    fn tail(text: &str) -> String {
        let skip = text.chars().count().saturating_sub(PROMPT_TAIL_CHARS);
        text.chars().skip(skip).collect()
    }
    let utterance = [speech(1.0), silence(1.0)].concat();
    let texts: Vec<String> = (0..5).map(|n| format!("משפט {n} ").repeat(10)).collect();

    let mut prompts = Vec::new();
    let mut recording = |_: &[f32], prompt: Option<&str>| {
        prompts.push(prompt.map(str::to_owned));
        Ok(vec![Segment {
            start: 0,
            end: 100,
            text: format!(" {}", texts[prompts.len() - 1]),
            no_speech_prob: 0.0,
        }])
    };
    let mut core = LiveCore::new(Some("מילון".into()), true, false);
    let mut clock = Instant::now();
    feed(&mut core, &mut clock, &utterance.repeat(5), &mut recording);

    let mut committed = vec!["מילון".to_string()];
    for (index, prompt) in prompts.iter().enumerate() {
        assert_eq!(
            prompt.as_deref(),
            Some(tail(&committed.join(" ")).as_str()),
            "utterance {index}"
        );
        committed.push(texts[index].trim().to_string());
    }
    assert_eq!(prompts.len(), 5);
    assert!(texts.concat().chars().count() > PROMPT_TAIL_CHARS, "the tail must be cut");

    // Parakeet and Nemotron refuse a prompt, so they never get one.
    let mut prompts = Vec::new();
    let mut recording = |samples: &[f32], prompt: Option<&str>| {
        prompts.push(prompt.map(str::to_owned));
        echo(samples, prompt)
    };
    let mut core = LiveCore::new(Some("מילון".into()), false, true);
    feed(&mut core, &mut clock, &utterance.repeat(2), &mut recording);
    assert!(!prompts.is_empty() && prompts.iter().all(Option::is_none), "{prompts:?}");
}

#[test]
fn stale_partial_is_cleared_when_no_final_replaces_it() {
    let word = || Segment {
        start: 0,
        end: 100,
        text: "מילה".into(),
        no_speech_prob: 0.0,
    };
    // The engine hears a word while the utterance is open, and nothing once it closes.
    let mut replies = vec![Vec::new(), vec![word()]];
    let mut engine = |_: &[f32], _: Option<&str>| Ok(replies.pop().unwrap_or_default());
    let mut core = LiveCore::new(None, true, true);
    let t0 = Instant::now();
    core.push(&speech(1.0));
    let shown = core.advance(t0, energy_vad, &mut engine).unwrap();
    assert_eq!(
        shown,
        vec![LiveEvent::Partial {
            start: 0.0,
            end: 1.0,
            text: "מילה".into()
        }]
    );
    core.push(&silence(1.0));
    // Too soon for another partial; the utterance closes and transcribes to nothing.
    let closed = core
        .advance(t0 + Duration::from_millis(500), energy_vad, &mut engine)
        .unwrap();
    let cleared = LiveEvent::Partial {
        start: 0.0,
        end: 1.0,
        text: String::new(),
    };
    assert_eq!(closed, vec![cleared.clone()]);

    // A VAD failure takes the open utterance with it: a warning, and the partial cleared.
    let mut core = LiveCore::new(None, true, true);
    core.push(&speech(1.0));
    core.advance(t0, energy_vad, echo).unwrap();
    core.push(&speech(0.5));
    let broken = |_: &[f32]| -> anyhow::Result<Vec<SpeechSegment>> { Err(anyhow::anyhow!("vad broke")) };
    let failed = core.advance(t0 + Duration::from_secs(2), broken, echo).unwrap();
    assert!(
        matches!(failed[0], LiveEvent::Warning { code: "vad_failed", .. }),
        "{failed:?}"
    );
    assert_eq!(failed[1..], [cleared]);
}

#[test]
fn finish_commits_open_speech_without_a_partial() {
    let mut core = LiveCore::new(None, true, true);
    let mut clock = Instant::now();
    feed(&mut core, &mut clock, &[silence(1.0), speech(2.0)].concat(), &mut echo);
    let events = core.finish(energy_vad, echo).unwrap();
    assert_eq!(
        events,
        vec![LiveEvent::Final {
            start: 1.0,
            end: 3.0,
            text: "2000 ms".into()
        }]
    );
}
