//! Live transcription: audio arrives in pushes while it is recorded, and each push answers with the
//! utterances that closed (finals) and a guess at the one still being spoken (a partial).
//!
//! [`LiveCore`] is the whole algorithm, with the VAD and the engine passed in as closures, so it is
//! tested without models. Positions are 16 kHz samples; `offset` maps the buffer to session time.

use std::ops::Range;
use std::time::{Duration, Instant};

use serde::Serialize;
use vad_rs::SpeechSegment;
use whisper_rs::Segment;

use crate::server::format::cs_to_seconds;

/// Silence after speech that closes an utterance.
pub const SILENCE_COMMIT_MS: u64 = 600;
/// An utterance still open after this long is committed anyway, which also bounds the buffer.
pub const MAX_UTTERANCE_MS: u64 = 20_000;
/// Shorter utterances are dropped: Whisper invents text for them.
pub const MIN_SPEECH_MS: u64 = 300;
pub const PARTIAL_INTERVAL_MS: u64 = 1_000;
/// Partials pause while an inference takes longer than this, so a slow engine spends itself on finals.
pub const PARTIAL_BUDGET_MS: u64 = 1_500;
/// Whisper's prompt: this much of the committed text, for continuity across utterances.
pub const PROMPT_TAIL_CHARS: usize = 200;
/// A session no push has reached for this long is dropped, and the model with it.
pub const IDLE_TTL: Duration = Duration::from_secs(30);
/// Audio kept ahead of speech, and all that is kept of silence.
const LEAD_IN_MS: u64 = 1_000;
const SAMPLES_PER_MS: usize = vad_rs::SAMPLE_RATE / 1000;

#[derive(Debug, Clone, PartialEq, Serialize, utoipa::ToSchema)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum LiveEvent {
    Final {
        start: f64,
        end: f64,
        text: String,
    },
    /// Replaces the previous partial.
    Partial {
        start: f64,
        end: f64,
        text: String,
    },
    Warning {
        code: &'static str,
        message: String,
    },
}

pub struct LiveCore {
    /// Audio not yet covered by a final; `buffer[0]` is sample `offset` of the session.
    buffer: Vec<f32>,
    offset: usize,
    /// The tail of the committed text for engines that take a prompt; `None` for those that do not.
    prompt: Option<String>,
    partials: bool,
    last_partial: Option<Instant>,
    last_inference: Duration,
}

impl LiveCore {
    /// `prompt` opens the committed text, so the first utterance has the caller's context too.
    pub fn new(prompt: Option<String>, text_prompts: bool, partials: bool) -> Self {
        let mut prompt = text_prompts.then(|| prompt.unwrap_or_default());
        if let Some(prompt) = prompt.as_mut() {
            keep_tail(prompt);
        }
        Self {
            buffer: Vec::new(),
            offset: 0,
            prompt,
            partials,
            last_partial: None,
            last_inference: Duration::ZERO,
        }
    }

    pub fn push(&mut self, pcm: &[f32]) {
        self.buffer.extend_from_slice(pcm);
    }

    /// Audio received and not yet covered by a final.
    pub fn lag_ms(&self) -> u64 {
        (self.buffer.len() / SAMPLES_PER_MS) as u64
    }

    /// Commits every utterance that closed, force-commits one that ran too long, offers a partial
    /// for the one still open, then trims the buffer to what may still become a final.
    pub fn advance(
        &mut self,
        now: Instant,
        mut vad: impl FnMut(&[f32]) -> anyhow::Result<Vec<SpeechSegment>>,
        mut transcribe: impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) -> anyhow::Result<Vec<LiveEvent>> {
        let len = self.buffer.len();
        let lead_in = samples(LEAD_IN_MS);
        let speech = match vad(&self.buffer) {
            Ok(speech) => speech,
            Err(err) => {
                // Without the VAD nothing here can be committed; keep the buffer bounded anyway.
                self.trim(len.saturating_sub(lead_in));
                return Err(err);
            }
        };
        let mut utterances = utterances(&speech);
        let open = utterances.pop_if(|utterance| len.saturating_sub(utterance.end) <= samples(SILENCE_COMMIT_MS));

        let mut events = Vec::new();
        let mut keep_from = 0;
        for utterance in utterances {
            keep_from = utterance.end;
            self.commit(utterance, &mut events, &mut transcribe);
        }
        match open {
            Some(utterance) if utterance.len() > samples(MAX_UTTERANCE_MS) => {
                // Cut at the last pause inside it, so no word is split; with none, at its end.
                let cut = speech
                    .iter()
                    .map(|segment| segment.end_sample)
                    .rfind(|&end| utterance.start < end && end < utterance.end)
                    .unwrap_or(utterance.end);
                self.commit(utterance.start..cut, &mut events, &mut transcribe);
                keep_from = cut;
            }
            Some(utterance) => {
                if self.partial_due(now) && utterance.len() >= samples(MIN_SPEECH_MS) {
                    self.last_partial = Some(now);
                    events.extend(self.partial(utterance.clone(), &mut transcribe));
                }
                keep_from = keep_from.max(utterance.start.saturating_sub(lead_in));
            }
            None => keep_from = keep_from.max(len.saturating_sub(lead_in)),
        }
        self.trim(keep_from);
        Ok(events)
    }

    /// Everything still in the buffer becomes finals; nothing is left open for a partial.
    pub fn finish(
        mut self,
        mut vad: impl FnMut(&[f32]) -> anyhow::Result<Vec<SpeechSegment>>,
        mut transcribe: impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) -> anyhow::Result<Vec<LiveEvent>> {
        let speech = vad(&self.buffer)?;
        let mut events = Vec::new();
        for utterance in utterances(&speech) {
            self.commit(utterance, &mut events, &mut transcribe);
        }
        Ok(events)
    }

    /// Transcribes `range` of the buffer into finals; too short to be speech, it is dropped. An
    /// engine failure costs this utterance alone, reported as a warning.
    fn commit(
        &mut self,
        range: Range<usize>,
        events: &mut Vec<LiveEvent>,
        transcribe: &mut impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) {
        if range.len() < samples(MIN_SPEECH_MS) {
            return;
        }
        let start = self.seconds(range.start);
        let segments = match self.infer(range, transcribe) {
            Ok(segments) => segments,
            Err(err) => {
                tracing::warn!("live transcription of an utterance failed: {err:#}");
                events.push(LiveEvent::Warning {
                    code: "transcription_failed",
                    message: format!("{err:#}"),
                });
                return;
            }
        };
        for segment in segments {
            let text = segment.text.trim();
            if text.is_empty() {
                continue;
            }
            if let Some(prompt) = self.prompt.as_mut() {
                if !prompt.is_empty() {
                    prompt.push(' ');
                }
                prompt.push_str(text);
                keep_tail(prompt);
            }
            events.push(LiveEvent::Final {
                start: start + cs_to_seconds(segment.start),
                end: start + cs_to_seconds(segment.end),
                text: text.to_owned(),
            });
        }
    }

    fn partial_due(&self, now: Instant) -> bool {
        self.partials
            && self.last_inference < Duration::from_millis(PARTIAL_BUDGET_MS)
            && self
                .last_partial
                .is_none_or(|last| now.duration_since(last) >= Duration::from_millis(PARTIAL_INTERVAL_MS))
    }

    /// A best-effort guess at the open utterance: a failure only costs this partial.
    fn partial(
        &mut self,
        range: Range<usize>,
        transcribe: &mut impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) -> Option<LiveEvent> {
        let (start, end) = (self.seconds(range.start), self.seconds(range.end));
        let segments = self
            .infer(range, transcribe)
            .inspect_err(|err| tracing::warn!("live partial failed: {err:#}"))
            .ok()?;
        let text = segments
            .iter()
            .map(|segment| segment.text.trim())
            .filter(|text| !text.is_empty())
            .collect::<Vec<_>>()
            .join(" ");
        (!text.is_empty()).then_some(LiveEvent::Partial { start, end, text })
    }

    /// Runs the engine on `range`, timing it for the partial budget.
    fn infer(
        &mut self,
        range: Range<usize>,
        transcribe: &mut impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) -> anyhow::Result<Vec<Segment>> {
        let started = Instant::now();
        let result = transcribe(
            &self.buffer[range],
            self.prompt.as_deref().filter(|prompt| !prompt.is_empty()),
        );
        self.last_inference = started.elapsed();
        result
    }

    /// Session time of buffer position `index`.
    fn seconds(&self, index: usize) -> f64 {
        (self.offset + index) as f64 / vad_rs::SAMPLE_RATE as f64
    }

    fn trim(&mut self, keep_from: usize) {
        let keep_from = keep_from.min(self.buffer.len());
        self.buffer.drain(..keep_from);
        self.offset += keep_from;
    }
}

/// Speech segments joined across pauses too short to close an utterance.
fn utterances(speech: &[SpeechSegment]) -> Vec<Range<usize>> {
    let mut utterances: Vec<Range<usize>> = Vec::new();
    for segment in speech {
        match utterances.last_mut() {
            Some(last) if segment.start_sample <= last.end + samples(SILENCE_COMMIT_MS) => {
                last.end = last.end.max(segment.end_sample);
            }
            _ => utterances.push(segment.start_sample..segment.end_sample),
        }
    }
    utterances
}

fn samples(ms: u64) -> usize {
    ms as usize * SAMPLES_PER_MS
}

/// Keeps the last `PROMPT_TAIL_CHARS` characters (not bytes: Hebrew is two per character).
fn keep_tail(text: &mut String) {
    if let Some((cut, _)) = text.char_indices().rev().nth(PROMPT_TAIL_CHARS - 1) {
        text.drain(..cut);
    }
}

#[cfg(test)]
mod tests {
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
        assert!(core.lag_ms() <= 1_000, "{}", core.lag_ms());
    }

    #[test]
    fn open_utterance_partial_at_most_once_per_interval() {
        let mut core = LiveCore::new(None, true, true);
        let mut clock = Instant::now();
        let per_push: Vec<Vec<LiveEvent>> = speech(5.0)
            .chunks(PUSH)
            .map(|chunk| feed(&mut core, &mut clock, chunk, &mut echo))
            .collect();
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
        assert!(core.lag_ms() <= 1_000, "{}", core.lag_ms());
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
        events.extend(feed(&mut core, &mut clock, &[speech(3.0), silence(1.0)].concat(), &mut slow));
        assert!(!events.iter().any(is_partial), "{events:?}");
        assert_eq!(finals(&events), vec![(0, 2_000), (3_000, 6_000)]);
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
}
