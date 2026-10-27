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

use super::{LiveApi, LiveEvent, LiveUpdate};
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
/// session and returns every final, in order (also kept in `collected` as they come). `emit` sends
/// `live_update` and `live_status`; `wake` cuts a wait between attempts short.
pub async fn run_live(
    api: impl LiveApi,
    rx: Receiver<Vec<f32>>,
    emit: impl Fn(&str, Value),
    cancel: CancellationToken,
    wake: Arc<Notify>,
    collected: Arc<Mutex<Vec<Segment>>>,
) -> Vec<Segment> {
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
                            current.sent(at, batch.len());
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
    /// (`offset` samples ahead of the session's). The lag counts the audio not sent yet, too.
    fn deliver(&mut self, mut update: LiveUpdate, offset: u64) {
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
    async fn finish(mut self, api: &impl LiveApi, mut session: Option<Session>) -> Vec<Segment> {
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
        self.status("ended", trouble);
        self.collected.lock().unwrap_or_else(PoisonError::into_inner).clone()
    }
}

fn centiseconds(seconds: f64) -> i64 {
    (seconds * 100.0).round() as i64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;
    use tokio::sync::mpsc::{self, unbounded_channel, UnboundedReceiver, UnboundedSender};

    use crate::server::live::ApiError;

    const CHUNK: usize = 1_600;

    fn final_(start: f64, end: f64, text: &str) -> LiveEvent {
        LiveEvent::Final {
            start,
            end,
            text: text.to_string(),
        }
    }

    fn update(events: Vec<LiveEvent>) -> LiveUpdate {
        LiveUpdate { events, lag_ms: 0 }
    }

    fn refused(status: u16, message: &str) -> ApiError {
        ApiError {
            status: Some(status),
            message: message.to_string(),
        }
    }

    fn segment(start: i64, stop: i64, text: &str) -> Segment {
        Segment {
            start,
            stop,
            text: text.to_string(),
            speaker: None,
        }
    }

    /// The server's live routes, answering from a script (and with an empty success once it runs
    /// out). Every call is reported on the channel as it is made, so a test can follow along.
    struct Fake {
        starts: StdMutex<VecDeque<Result<String, ApiError>>>,
        pushes: StdMutex<VecDeque<Result<LiveUpdate, ApiError>>>,
        stops: StdMutex<VecDeque<Result<LiveUpdate, ApiError>>>,
        calls: UnboundedSender<String>,
    }

    impl Fake {
        fn new() -> (Self, UnboundedReceiver<String>) {
            let (calls, seen) = unbounded_channel();
            let fake = Self {
                starts: StdMutex::default(),
                pushes: StdMutex::default(),
                stops: StdMutex::default(),
                calls,
            };
            (fake, seen)
        }
    }

    impl LiveApi for &Fake {
        async fn start(&self) -> Result<String, ApiError> {
            self.calls.send("start".to_string()).ok();
            self.starts.lock().unwrap().pop_front().unwrap_or(Ok("s1".to_string()))
        }

        async fn push(&self, id: &str, pcm: &[f32]) -> Result<LiveUpdate, ApiError> {
            self.calls.send(format!("push {id} {}", pcm.len())).ok();
            self.pushes.lock().unwrap().pop_front().unwrap_or(Ok(LiveUpdate::default()))
        }

        async fn stop(&self, id: &str) -> Result<LiveUpdate, ApiError> {
            self.calls.send(format!("stop {id}")).ok();
            self.stops.lock().unwrap().pop_front().unwrap_or(Ok(LiveUpdate::default()))
        }
    }

    type Emitted = StdMutex<Vec<(String, Value)>>;

    fn recorder(emitted: &Emitted) -> impl Fn(&str, Value) + '_ {
        move |event, payload| emitted.lock().unwrap().push((event.to_string(), payload))
    }

    fn states(emitted: &Emitted) -> Vec<String> {
        let emitted = emitted.lock().unwrap();
        let statuses = emitted.iter().filter(|(event, _)| event == "live_status");
        statuses
            .map(|(_, payload)| payload["state"].as_str().unwrap().to_string())
            .collect()
    }

    /// `count` chunks of 100 ms from the tap.
    async fn send(tx: &mpsc::Sender<Vec<f32>>, count: usize) {
        for _ in 0..count {
            tx.send(vec![0.5; CHUNK]).await.unwrap();
        }
    }

    async fn next(calls: &mut UnboundedReceiver<String>) -> String {
        calls.recv().await.unwrap()
    }

    #[tokio::test]
    async fn drain_batch_waits_for_min_samples() {
        let (tx, mut rx) = mpsc::channel(8);
        send(&tx, 1).await;
        {
            let mut batch = std::pin::pin!(drain_batch(&mut rx, 4_000));
            assert!(futures::poll!(&mut batch).is_pending(), "100 ms is not a batch yet");

            send(&tx, 2).await;
            assert_eq!(batch.await.len(), 3 * CHUNK);
        }

        // Once the tap is gone, what is there is all there will be.
        send(&tx, 1).await;
        drop(tx);
        assert_eq!(drain_batch(&mut rx, 4_000).await.len(), CHUNK);
    }

    #[test]
    fn paused_buffer_caps_at_60s() {
        let mut backlog: VecDeque<Vec<f32>> = (0..601).map(|n| vec![n as f32; CHUNK]).collect();

        assert_eq!(cap_backlog(&mut backlog, 60 * 16_000), CHUNK as u64);

        assert_eq!(backlog.len(), 600);
        assert_eq!(backlog[0][0], 1.0, "the oldest audio goes first");
        assert_eq!(cap_backlog(&mut backlog, 60 * 16_000), 0);
    }

    #[test]
    fn gap_is_reported_once_per_gap() {
        let emitted = Emitted::default();
        let (_tx, rx) = mpsc::channel(1);
        let mut live = Live::new(rx, recorder(&emitted), Arc::default());
        let gaps = || {
            let emitted = emitted.lock().unwrap();
            emitted
                .iter()
                .filter(|(_, payload)| payload["events"][0]["code"] == "gap")
                .count()
        };

        for _ in 0..610 {
            live.receive(vec![0.0; CHUNK]);
        }
        assert_eq!(gaps(), 1);
        assert_eq!(live.position, 10 * CHUNK as u64, "the clock keeps counting the audio dropped");

        // The server takes audio again: the next drop is a new gap.
        live.deliver(LiveUpdate::default(), 0);
        live.receive(vec![0.0; CHUNK]);
        assert_eq!(gaps(), 2);
    }

    #[test]
    fn finals_are_collected_in_centiseconds() {
        let emitted = Emitted::default();
        let collected = Arc::default();
        let (_tx, rx) = mpsc::channel(1);
        let mut live = Live::new(rx, recorder(&emitted), Arc::clone(&collected));
        let partial = LiveEvent::Partial {
            start: 2.5,
            end: 3.0,
            text: "עוד".to_string(),
        };
        let warning = LiveEvent::Warning {
            code: "transcription_failed".to_string(),
            message: "boom".to_string(),
        };

        // This session started 1 s into the recording.
        live.deliver(update(vec![final_(1.25, 2.5, "שלום"), partial, warning]), 16_000);

        assert_eq!(*collected.lock().unwrap(), vec![segment(225, 350, "שלום")]);
        let emitted = emitted.lock().unwrap();
        let (event, payload) = &emitted[0];
        assert_eq!(event, "live_update");
        assert_eq!(
            (payload["events"][0]["start"].as_f64(), payload["events"][0]["end"].as_f64()),
            (Some(2.25), Some(3.5))
        );
        assert_eq!(payload["events"][1]["start"].as_f64(), Some(3.5), "partials move too");
        assert_eq!(payload["events"][2]["code"], "transcription_failed", "warnings are forwarded");
    }

    #[tokio::test]
    async fn session_is_flushed_and_closed_on_cancel() {
        let (fake, mut calls) = Fake::new();
        fake.pushes.lock().unwrap().extend([
            Ok(update(vec![final_(0.0, 0.3, "one")])),
            Ok(update(vec![final_(0.3, 0.6, "two")])),
        ]);
        fake.stops
            .lock()
            .unwrap()
            .push_back(Ok(update(vec![final_(0.6, 0.7, "three")])));
        let emitted = Emitted::default();
        let collected = Arc::default();
        let (tx, rx) = mpsc::channel(64);
        let cancel = CancellationToken::new();
        let stop = cancel.clone();

        let driver = async move {
            send(&tx, 3).await;
            assert_eq!(next(&mut calls).await, "start");
            assert_eq!(next(&mut calls).await, "push s1 4800");
            send(&tx, 3).await;
            assert_eq!(next(&mut calls).await, "push s1 4800");
            // What a stop does: the tap's padded last chunk, the channel closed, the token cancelled.
            send(&tx, 1).await;
            drop(tx);
            stop.cancel();
            assert_eq!(next(&mut calls).await, "push s1 1600");
            assert_eq!(next(&mut calls).await, "stop s1");
        };
        let run = run_live(&fake, rx, recorder(&emitted), cancel, Arc::default(), Arc::clone(&collected));
        let (finals, ()) = tokio::join!(run, driver);

        let expected = vec![segment(0, 30, "one"), segment(30, 60, "two"), segment(60, 70, "three")];
        assert_eq!(finals, expected);
        assert_eq!(*collected.lock().unwrap(), expected);
        assert_eq!(states(&emitted), ["starting", "live", "ended"]);
    }

    #[tokio::test(start_paused = true)]
    async fn retry_after_no_model_then_live() {
        let (fake, mut calls) = Fake::new();
        fake.starts
            .lock()
            .unwrap()
            .extend([Err(refused(503, "no model loaded")), Ok("s1".to_string())]);
        let emitted = Emitted::default();
        let (tx, rx) = mpsc::channel(64);
        let cancel = CancellationToken::new();
        let stop = cancel.clone();

        let driver = async move {
            assert_eq!(next(&mut calls).await, "start");
            let paused_at = tokio::time::Instant::now();
            // The recording goes on while the client waits: this audio is kept for the session.
            send(&tx, 3).await;
            assert_eq!(next(&mut calls).await, "start");
            assert!(paused_at.elapsed() >= Duration::from_secs(1), "the first retry waits 1 s");
            assert_eq!(next(&mut calls).await, "push s1 4800");
            drop(tx);
            stop.cancel();
            assert_eq!(next(&mut calls).await, "stop s1");
        };
        tokio::join!(
            run_live(&fake, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
            driver
        );

        assert_eq!(states(&emitted), ["starting", "paused", "live", "ended"]);
        let emitted = emitted.lock().unwrap();
        let paused = emitted.iter().find(|(_, payload)| payload["state"] == "paused").unwrap();
        assert_eq!(paused.1["message"], "no model loaded");
    }

    #[tokio::test(start_paused = true)]
    async fn push_error_pauses_and_resumes() {
        let (fake, mut calls) = Fake::new();
        fake.starts
            .lock()
            .unwrap()
            .extend([Ok("s1".to_string()), Ok("s2".to_string())]);
        fake.pushes.lock().unwrap().extend([
            Ok(update(vec![final_(0.0, 0.3, "one")])),
            Err(refused(404, "no such live session")),
            Ok(update(vec![final_(0.0, 0.3, "two")])),
        ]);
        let emitted = Emitted::default();
        let (tx, rx) = mpsc::channel(64);
        let cancel = CancellationToken::new();
        let stop = cancel.clone();

        let driver = async move {
            send(&tx, 3).await;
            assert_eq!(next(&mut calls).await, "start");
            assert_eq!(next(&mut calls).await, "push s1 4800");
            send(&tx, 3).await;
            assert_eq!(next(&mut calls).await, "push s1 4800");
            // The session was reaped: a new one gets the audio the old one never took.
            assert_eq!(next(&mut calls).await, "start");
            assert_eq!(next(&mut calls).await, "push s2 4800");
            drop(tx);
            stop.cancel();
            assert_eq!(next(&mut calls).await, "stop s2");
        };
        let (finals, ()) = tokio::join!(
            run_live(&fake, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
            driver
        );

        // The second session's clock starts 0.3 s into the recording.
        assert_eq!(finals, vec![segment(0, 30, "one"), segment(30, 60, "two")]);
        assert_eq!(states(&emitted), ["starting", "live", "paused", "live", "ended"]);
    }

    #[tokio::test(start_paused = true)]
    async fn delete_retried_on_409() {
        let (fake, mut calls) = Fake::new();
        fake.stops.lock().unwrap().extend([
            Err(refused(409, "a push to this session is still running")),
            Ok(update(vec![final_(0.0, 0.3, "last")])),
        ]);
        let emitted = Emitted::default();
        let (tx, rx) = mpsc::channel(64);
        let cancel = CancellationToken::new();
        let stop = cancel.clone();

        let driver = async move {
            send(&tx, 3).await;
            assert_eq!(next(&mut calls).await, "start");
            assert_eq!(next(&mut calls).await, "push s1 4800");
            drop(tx);
            stop.cancel();
            assert_eq!(next(&mut calls).await, "stop s1");
            assert_eq!(next(&mut calls).await, "stop s1");
        };
        let (finals, ()) = tokio::join!(
            run_live(&fake, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
            driver
        );

        assert_eq!(finals, vec![segment(0, 30, "last")]);
        let emitted = emitted.lock().unwrap();
        let (_, ended) = emitted.last().unwrap();
        assert_eq!((ended["state"].as_str(), ended["message"].is_null()), (Some("ended"), true));
    }
}
