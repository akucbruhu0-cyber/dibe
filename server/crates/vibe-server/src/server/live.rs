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

/// At most one partial is current: a `Final`, or a `Partial` with empty text, replaces or clears it.
#[derive(Debug, Clone, PartialEq, Serialize, utoipa::ToSchema)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum LiveEvent {
    Final {
        start: f64,
        end: f64,
        text: String,
    },
    /// Replaces the current partial; empty text clears it.
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
    /// Session sample where the latest final or partial ends: the text reaches this far.
    text_until: usize,
    /// Speech received after `text_until`, as of the last `advance`.
    lag: usize,
    /// The current partial's times, until a final replaces it or it is cleared.
    shown: Option<(f64, f64)>,
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
            text_until: 0,
            lag: 0,
            shown: None,
        }
    }

    pub fn push(&mut self, pcm: &[f32]) {
        self.buffer.extend_from_slice(pcm);
    }

    /// How far the text trails the speech: audio received after the end of the latest final or
    /// partial, counted from where the speech still waiting for text starts. Silence counts nothing.
    pub fn lag_ms(&self) -> u64 {
        (self.lag / SAMPLES_PER_MS) as u64
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
        let received = self.offset + len;
        let lead_in = samples(LEAD_IN_MS);
        let mut events = Vec::new();
        let speech = match vad(&self.buffer) {
            Ok(speech) => speech,
            Err(err) => {
                // Without the VAD nothing here can be committed; keep the buffer bounded anyway.
                self.vad_failed(&err, &mut events);
                self.trim(len.saturating_sub(lead_in));
                self.lag = 0;
                return Ok(events);
            }
        };
        let mut utterances = utterances(&speech);
        let open = utterances.pop_if(|utterance| len.saturating_sub(utterance.end) <= samples(SILENCE_COMMIT_MS));

        let mut keep_from = 0;
        // Where the speech still waiting for text starts, in session samples.
        let mut waiting = None;
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
                waiting = Some(self.offset + cut);
            }
            Some(utterance) => {
                if self.partial_due(now) && utterance.len() >= samples(MIN_SPEECH_MS) {
                    self.last_partial = Some(now);
                    events.extend(self.partial(utterance.clone(), &mut transcribe));
                }
                keep_from = keep_from.max(utterance.start.saturating_sub(lead_in));
                waiting = Some(self.offset + utterance.start);
            }
            None => keep_from = keep_from.max(len.saturating_sub(lead_in)),
        }
        self.lag = waiting.map_or(0, |start| received.saturating_sub(start.max(self.text_until)));
        self.trim(keep_from);
        Ok(events)
    }

    /// Everything still in the buffer becomes finals; nothing is left open for a partial.
    pub fn finish(
        mut self,
        mut vad: impl FnMut(&[f32]) -> anyhow::Result<Vec<SpeechSegment>>,
        mut transcribe: impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) -> anyhow::Result<Vec<LiveEvent>> {
        let mut events = Vec::new();
        match vad(&self.buffer) {
            Ok(speech) => {
                for utterance in utterances(&speech) {
                    self.commit(utterance, &mut events, &mut transcribe);
                }
            }
            Err(err) => self.vad_failed(&err, &mut events),
        }
        // The session ends here: no partial may outlive it.
        self.clear_partial(&mut events);
        Ok(events)
    }

    /// Reported in-band, like an engine failure, so the stale partial can be cleared in the same update.
    fn vad_failed(&mut self, err: &anyhow::Error, events: &mut Vec<LiveEvent>) {
        tracing::warn!("live VAD failed: {err:#}");
        events.push(LiveEvent::Warning {
            code: "vad_failed",
            message: format!("{err:#}"),
        });
        self.clear_partial(events);
    }

    fn clear_partial(&mut self, events: &mut Vec<LiveEvent>) {
        if let Some((start, end)) = self.shown.take() {
            events.push(LiveEvent::Partial {
                start,
                end,
                text: String::new(),
            });
        }
    }

    /// Transcribes `range` of the buffer into finals; too short to be speech, it is dropped. An
    /// engine failure costs this utterance alone, reported as a warning. With no final to replace
    /// it, the current partial is cleared.
    fn commit(
        &mut self,
        range: Range<usize>,
        events: &mut Vec<LiveEvent>,
        transcribe: &mut impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) {
        if self.transcribe_finals(range, events, transcribe) {
            self.shown = None;
        } else {
            self.clear_partial(events);
        }
    }

    /// Whether any final came of it.
    fn transcribe_finals(
        &mut self,
        range: Range<usize>,
        events: &mut Vec<LiveEvent>,
        transcribe: &mut impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>>,
    ) -> bool {
        if range.len() < samples(MIN_SPEECH_MS) {
            return false;
        }
        let start = self.seconds(range.start);
        let end_sample = self.offset + range.end;
        let segments = match self.infer(range, transcribe) {
            Ok(segments) => segments,
            Err(err) => {
                tracing::warn!("live transcription of an utterance failed: {err:#}");
                events.push(LiveEvent::Warning {
                    code: "transcription_failed",
                    message: format!("{err:#}"),
                });
                return false;
            }
        };
        let mut any = false;
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
            self.text_until = end_sample;
            any = true;
        }
        any
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
        let end_sample = self.offset + range.end;
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
        if text.is_empty() {
            return None;
        }
        self.shown = Some((start, end));
        self.text_until = end_sample;
        Some(LiveEvent::Partial { start, end, text })
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
mod tests;
