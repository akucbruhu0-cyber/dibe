//! The client's loop: open a session, push the audio as it comes, ride out failures, and at the
//! stop send the rest and close the session.
//!
//! Positions are 16 kHz samples counted from the start of the recording. A session's clock starts
//! at its first sample, which is not the recording's once a session was replaced or audio was
//! dropped from the backlog, so every answer is moved by the offset of the push it answers.

use serde_json::{json, Value};
use std::collections::VecDeque;
use std::future::Future;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;
use tokio::sync::mpsc::Receiver;
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;

use super::{LiveApi, LiveEvent, LiveTranscript, LiveUpdate};
use crate::transcript::Segment;

const LIVE_HZ: usize = 16_000;
/// 250 ms: the least audio worth a push.
const MIN_PUSH_SAMPLES: usize = LIVE_HZ / 4;
/// The most the server takes in one push.
const MAX_PUSH_SAMPLES: usize = 30 * LIVE_HZ;
/// Audio kept while the server cannot take it; beyond this the oldest is dropped, as a gap.
const MAX_BACKLOG_SAMPLES: usize = 60 * LIVE_HZ;
/// The waits between failed attempts; the last one repeats.
const BACKOFF: [Duration; 3] = [Duration::from_secs(1), Duration::from_secs(2), Duration::from_secs(5)];
/// A `DELETE` answered 409 while a push of ours whose answer was lost still runs on the server.
const STOP_RETRIES: usize = 5;

/// Waits until at least `min_samples` arrived, or the channel closed, and returns them.
pub async fn drain_batch(rx: &mut Receiver<Vec<f32>>, min_samples: usize) -> Vec<f32> {
    let mut batch = Vec::new();
    while batch.len() < min_samples {
        match rx.recv().await {
            Some(chunk) => batch.extend(chunk),
            None => break,
        }
    }
    batch
}

/// Drops the oldest chunks until `backlog` holds at most `max_samples`; returns the samples dropped.
pub fn cap_backlog(backlog: &mut VecDeque<Vec<f32>>, max_samples: usize) -> u64 {
    let mut kept: usize = backlog.iter().map(Vec::len).sum();
    let mut dropped = 0;
    while kept > max_samples {
        let Some(oldest) = backlog.pop_front() else { break };
        kept -= oldest.len();
        dropped += oldest.len() as u64;
    }
    dropped
}

/// Runs a recording's live transcription until `cancel`, then sends what is left, closes the
/// session and returns every final, in order (also kept in `collected` as they come), and whether
/// they cover all of the audio. `emit` sends `live_update` and `live_status`; `wake` cuts a wait
/// between attempts short.
pub async fn run_live(
    api: impl LiveApi,
    rx: Receiver<Vec<f32>>,
    emit: impl Fn(&str, Value),
    cancel: CancellationToken,
    wake: Arc<Notify>,
    collected: Arc<Mutex<Vec<Segment>>>,
) -> LiveTranscript {
    let mut live = Live::new(rx, emit, collected);
    let mut session: Option<Session> = None;
    let mut failures = 0;
    live.status("starting", None);
    while !cancel.is_cancelled() && !live.closed {
        let failure = match session.as_mut() {
            None => match live.during(api.start()).await {
                Ok(id) => {
                    session = Some(Session::new(id, live.position));
                    None
                }
                Err(error) => Some(error),
            },
            Some(current) => {
                let missing = MIN_PUSH_SAMPLES.saturating_sub(live.backlog_samples());
                if missing > 0 {
                    // A stop closes the channel before it cancels, so this wait ends with the last
                    // audio; the token only matters should the tap outlive the stop.
                    let audio = tokio::select! {
                        biased;
                        audio = drain_batch(&mut live.rx, missing) => Some(audio),
                        () = cancel.cancelled() => None,
                    };
                    if let Some(audio) = audio {
                        live.closed = audio.len() < missing;
                        live.receive(audio);
                    }
                    continue;
                }
                let (batch, at) = live.take(MAX_PUSH_SAMPLES);
                match live.during(api.push(&current.id, &batch)).await {
                    Ok(update) => {
                        current.sent(at, batch.len());
                        live.deliver(update, current.offset);
                        None
                    }
                    Err(error) => {
                        if error.untaken() {
                            live.requeue(batch);
                        } else {
                            // The server had it and its finals are lost.
                            current.sent(at, batch.len());
                            live.complete = false;
                        }
                        if error.lost_session() {
                            session = None;
                        }
                        Some(error)
                    }
                }
            }
        };
        match failure {
            None => {
                failures = 0;
                live.status("live", None);
            }
            Some(error) => {
                live.status("paused", Some(error.message));
                let delay = BACKOFF[failures.min(BACKOFF.len() - 1)];
                failures += 1;
                live.during(async {
                    tokio::select! {
                        () = tokio::time::sleep(delay) => {}
                        () = wake.notified() => {}
                        () = cancel.cancelled() => {}
                    }
                })
                .await;
            }
        }
    }
    live.finish(&api, session).await
}

/// A server session, and where its clock stands against the recording's.
struct Session {
    id: String,
    /// Samples it was sent: its clock.
    sent: u64,
    /// Recording position minus session position, as of the last push.
    offset: u64,
}

impl Session {
    fn new(id: String, position: u64) -> Self {
        Self {
            id,
            sent: 0,
            offset: position,
        }
    }

    /// `samples` starting at recording position `at` went to the server.
    fn sent(&mut self, at: u64, samples: usize) {
        self.offset = at.saturating_sub(self.sent);
        self.sent += samples as u64;
    }
}

/// `DELETE`, again after a wait while a push of ours still runs on the server.
async fn stop_session(api: &impl LiveApi, id: &str) -> Result<LiveUpdate, super::ApiError> {
    let mut retries = 0;
    loop {
        match api.stop(id).await {
            Err(error) if error.status == Some(409) && retries < STOP_RETRIES => {
                tokio::time::sleep(BACKOFF[retries.min(BACKOFF.len() - 1)]).await;
                retries += 1;
            }
            answer => return answer,
        }
    }
}

/// The audio between the tap and the server, and what the UI is told.
struct Live<E> {
    rx: Receiver<Vec<f32>>,
    emit: E,
    collected: Arc<Mutex<Vec<Segment>>>,
    /// Audio received and not pushed yet, oldest first.
    backlog: VecDeque<Vec<f32>>,
    /// Where the backlog starts.
    position: u64,
    /// The audio being dropped now was reported; cleared once the server takes audio again.
    in_gap: bool,
    /// The tap is gone: no more audio is coming.
    closed: bool,
    /// No audio was skipped, lost or failed so far.
    complete: bool,
    /// The last `live_status` sent.
    status: Option<(&'static str, Option<String>)>,
}

impl<E: Fn(&str, Value)> Live<E> {
    fn new(rx: Receiver<Vec<f32>>, emit: E, collected: Arc<Mutex<Vec<Segment>>>) -> Self {
        Self {
            rx,
            emit,
            collected,
            backlog: VecDeque::new(),
            position: 0,
            in_gap: false,
            closed: false,
            complete: true,
            status: None,
        }
    }

    fn backlog_samples(&self) -> usize {
        self.backlog.iter().map(Vec::len).sum()
    }

    fn backlog_ms(&self) -> u64 {
        (self.backlog_samples() * 1_000 / LIVE_HZ) as u64
    }

    fn receive(&mut self, audio: Vec<f32>) {
        if !audio.is_empty() {
            self.backlog.push_back(audio);
        }
        self.cap();
    }

    /// Puts audio the server never took back in front, to send again.
    fn requeue(&mut self, batch: Vec<f32>) {
        self.position = self.position.saturating_sub(batch.len() as u64);
        self.backlog.push_front(batch);
        self.cap();
    }

    fn cap(&mut self) {
        let dropped = cap_backlog(&mut self.backlog, MAX_BACKLOG_SAMPLES);
        self.position += dropped;
        self.complete &= dropped == 0;
        if dropped > 0 && !self.in_gap {
            self.in_gap = true;
            let message = "Live transcription fell a minute behind and skipped some audio; the recording has all of it";
            self.update(LiveUpdate {
                events: vec![LiveEvent::Warning {
                    code: "gap".to_string(),
                    message: message.to_string(),
                }],
                lag_ms: self.backlog_ms(),
            });
        }
    }

    /// The oldest audio, whole chunks up to `max` samples (at least one), and where it starts.
    fn take(&mut self, max: usize) -> (Vec<f32>, u64) {
        let at = self.position;
        let mut batch = Vec::new();
        while self
            .backlog
            .front()
            .is_some_and(|chunk| batch.is_empty() || batch.len() + chunk.len() <= max)
        {
            batch.extend(self.backlog.pop_front().unwrap_or_default());
        }
        self.position += batch.len() as u64;
        (batch, at)
    }

    /// Awaits `work` while the audio keeps coming into the backlog, so a slow push or a wait
    /// between attempts never backs up the tap.
    async fn during<T>(&mut self, work: impl Future<Output = T>) -> T {
        let mut work = std::pin::pin!(work);
        loop {
            let chunk = tokio::select! {
                biased;
                done = &mut work => return done,
                chunk = self.rx.recv(), if !self.closed => chunk,
            };
            match chunk {
                Some(chunk) => self.receive(chunk),
                None => self.closed = true,
            }
        }
    }

    /// Hands the server's answer to the UI, and keeps its finals, both on the recording's clock
    /// (`offset` samples ahead of the session's). The lag counts the audio not sent yet, too. A
    /// warning means the server could not transcribe some of the audio.
    fn deliver(&mut self, mut update: LiveUpdate, offset: u64) {
        self.complete &= !update.events.iter().any(|event| matches!(event, LiveEvent::Warning { .. }));
        let shift = offset as f64 / LIVE_HZ as f64;
        for event in &mut update.events {
            if let LiveEvent::Final { start, end, .. } | LiveEvent::Partial { start, end, .. } = event {
                *start += shift;
                *end += shift;
            }
        }
        let finals = update.events.iter().filter_map(|event| match event {
            LiveEvent::Final { start, end, text } => Some(Segment {
                start: centiseconds(*start),
                stop: centiseconds(*end),
                text: text.clone(),
                speaker: None,
            }),
            _ => None,
        });
        self.collected.lock().unwrap_or_else(PoisonError::into_inner).extend(finals);
        self.in_gap = false;
        update.lag_ms += self.backlog_ms();
        self.update(update);
    }

    fn update(&self, update: LiveUpdate) {
        (self.emit)("live_update", json!({ "events": update.events, "lag_ms": update.lag_ms }));
    }

    /// Sends `live_status` when the state, or its message, changed.
    fn status(&mut self, state: &'static str, message: Option<String>) {
        let next = Some((state, message));
        if self.status != next {
            if let Some((state, message)) = &next {
                (self.emit)("live_status", json!({ "state": state, "message": message }));
            }
            self.status = next;
        }
    }

    /// The stop: sends what is left, at most 30 s a push, closes the session and returns every final.
    async fn finish(mut self, api: &impl LiveApi, mut session: Option<Session>) -> LiveTranscript {
        while let Ok(chunk) = self.rx.try_recv() {
            self.receive(chunk);
        }
        let mut trouble = None;
        if session.is_none() && self.backlog_samples() > 0 {
            match api.start().await {
                Ok(id) => session = Some(Session::new(id, self.position)),
                Err(error) => trouble = Some(error.message),
            }
        }
        if let Some(mut current) = session {
            let mut lost = false;
            while self.backlog_samples() > 0 {
                let (batch, at) = self.take(MAX_PUSH_SAMPLES);
                match api.push(&current.id, &batch).await {
                    Ok(update) => {
                        current.sent(at, batch.len());
                        self.deliver(update, current.offset);
                    }
                    Err(error) => {
                        lost = error.lost_session();
                        trouble = Some(error.message);
                        break;
                    }
                }
            }
            if !lost {
                match stop_session(api, &current.id).await {
                    Ok(update) => self.deliver(update, current.offset),
                    Err(error) => trouble = Some(error.message),
                }
            }
        }
        // Audio left unsent, or finals never answered.
        self.complete &= trouble.is_none();
        self.status("ended", trouble);
        LiveTranscript {
            finals: self.collected.lock().unwrap_or_else(PoisonError::into_inner).clone(),
            complete: self.complete,
        }
    }
}

fn centiseconds(seconds: f64) -> i64 {
    (seconds * 100.0).round() as i64
}

#[cfg(test)]
mod tests;
