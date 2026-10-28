use super::*;
use std::sync::Mutex as StdMutex;
use tokio::sync::mpsc::{self, unbounded_channel, UnboundedReceiver, UnboundedSender};

use crate::server::live::ApiError;

const CHUNK: usize = 1_600;
/// 250 ms: the tests send 300 ms at a time and expect each to go out as one push.
const PUSH: usize = 4_000;

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

    for _ in 0..600 {
        live.receive(vec![0.0; CHUNK]);
    }
    assert!(live.complete, "a minute fits in the backlog");
    for _ in 0..10 {
        live.receive(vec![0.0; CHUNK]);
    }
    assert_eq!(gaps(), 1);
    assert!(!live.complete, "the skipped audio was never transcribed");
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
    live.deliver(update(vec![final_(1.25, 2.5, "שלום"), partial]), 16_000);
    assert!(live.complete);
    live.deliver(update(vec![warning]), 16_000);
    assert!(!live.complete, "the server could not transcribe some of the audio");

    assert_eq!(*collected.lock().unwrap(), vec![segment(225, 350, "שלום")]);
    let emitted = emitted.lock().unwrap();
    let (event, payload) = &emitted[0];
    assert_eq!(event, "live_update");
    assert_eq!(
        (payload["events"][0]["start"].as_f64(), payload["events"][0]["end"].as_f64()),
        (Some(2.25), Some(3.5))
    );
    assert_eq!(payload["events"][1]["start"].as_f64(), Some(3.5), "partials move too");
    assert_eq!(
        emitted[1].1["events"][0]["code"], "transcription_failed",
        "warnings are forwarded"
    );
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
    let run = run_live(
        &fake,
        PUSH,
        rx,
        recorder(&emitted),
        cancel,
        Arc::default(),
        Arc::clone(&collected),
    );
    let (transcript, ()) = tokio::join!(run, driver);

    let expected = vec![segment(0, 30, "one"), segment(30, 60, "two"), segment(60, 70, "three")];
    assert_eq!(transcript.finals, expected);
    assert!(transcript.complete, "every sample was answered");
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
        run_live(&fake, PUSH, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
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
        // The server restarted under the session.
        Err(ApiError::unreachable("connection refused".to_string())),
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
        // The session died with the server: a new one gets the audio the old one never took.
        assert_eq!(next(&mut calls).await, "start");
        assert_eq!(next(&mut calls).await, "push s2 4800");
        drop(tx);
        stop.cancel();
        assert_eq!(next(&mut calls).await, "stop s2");
    };
    let (transcript, ()) = tokio::join!(
        run_live(&fake, PUSH, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
        driver
    );

    // The second session's clock starts 0.3 s into the recording.
    assert_eq!(transcript.finals, vec![segment(0, 30, "one"), segment(30, 60, "two")]);
    assert!(transcript.complete, "the reaped session's audio went to the new one");
    assert_eq!(states(&emitted), ["starting", "live", "paused", "live", "ended"]);
}

#[tokio::test(start_paused = true)]
async fn a_session_reaped_during_a_pause_is_replaced_quietly() {
    let (fake, mut calls) = Fake::new();
    fake.starts
        .lock()
        .unwrap()
        .extend([Ok("s1".to_string()), Ok("s2".to_string())]);
    fake.pushes.lock().unwrap().extend([
        Ok(update(vec![final_(0.0, 0.3, "before")])),
        // Nothing came for 30 s while the recording was paused, and the server let the session go.
        Err(refused(404, "no such live session")),
        Ok(update(vec![final_(0.0, 0.3, "after")])),
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
        assert_eq!(next(&mut calls).await, "start");
        assert_eq!(next(&mut calls).await, "push s2 4800");
        drop(tx);
        stop.cancel();
        assert_eq!(next(&mut calls).await, "stop s2");
    };
    let (transcript, ()) = tokio::join!(
        run_live(&fake, PUSH, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
        driver
    );

    assert_eq!(transcript.finals, vec![segment(0, 30, "before"), segment(30, 60, "after")]);
    // Not trouble: no "paused" between the two sessions.
    assert_eq!(states(&emitted), ["starting", "live", "ended"]);
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
    let (transcript, ()) = tokio::join!(
        run_live(&fake, PUSH, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
        driver
    );

    assert_eq!(transcript.finals, vec![segment(0, 30, "last")]);
    assert!(transcript.complete);
    let emitted = emitted.lock().unwrap();
    let (_, ended) = emitted.last().unwrap();
    assert_eq!((ended["state"].as_str(), ended["message"].is_null()), (Some("ended"), true));
}

#[tokio::test(start_paused = true)]
async fn audio_the_server_lost_makes_the_transcript_incomplete() {
    let (fake, mut calls) = Fake::new();
    // The server took the audio and failed it: those finals never come.
    fake.pushes.lock().unwrap().push_back(Err(refused(500, "engine failed")));
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
    };
    let (transcript, ()) = tokio::join!(
        run_live(&fake, PUSH, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
        driver
    );

    assert!(!transcript.complete);
}

#[tokio::test]
async fn a_stop_that_cannot_send_the_rest_is_incomplete() {
    let (fake, mut calls) = Fake::new();
    fake.pushes
        .lock()
        .unwrap()
        .extend([Ok(update(vec![final_(0.0, 0.3, "one")])), Err(refused(500, "engine failed"))]);
    let emitted = Emitted::default();
    let (tx, rx) = mpsc::channel(64);
    let cancel = CancellationToken::new();
    let stop = cancel.clone();

    let driver = async move {
        send(&tx, 3).await;
        assert_eq!(next(&mut calls).await, "start");
        assert_eq!(next(&mut calls).await, "push s1 4800");
        // The last 100 ms go out at the stop, and that push fails.
        send(&tx, 1).await;
        drop(tx);
        stop.cancel();
        assert_eq!(next(&mut calls).await, "push s1 1600");
    };
    let (transcript, ()) = tokio::join!(
        run_live(&fake, PUSH, rx, recorder(&emitted), cancel, Arc::default(), Arc::default()),
        driver
    );

    assert_eq!(transcript.finals, vec![segment(0, 30, "one")]);
    assert!(!transcript.complete);
}

#[tokio::test(start_paused = true)]
async fn a_server_without_live_routes_ends_live_at_once() {
    let (fake, mut calls) = Fake::new();
    // A pinned older sidecar: no /v1/live at all.
    fake.starts.lock().unwrap().push_back(Err(refused(404, "404 Not Found")));
    let emitted = Emitted::default();
    let (tx, rx) = mpsc::channel(64);

    // The recording goes on (the tap stays open, nothing is cancelled), yet the client is done.
    let run = run_live(
        &fake,
        PUSH,
        rx,
        recorder(&emitted),
        CancellationToken::new(),
        Arc::default(),
        Arc::default(),
    );
    let transcript = tokio::time::timeout(Duration::from_secs(1), run)
        .await
        .expect("no retries against a server that has no live routes");

    assert!(!transcript.complete);
    assert_eq!(next(&mut calls).await, "start");
    assert!(calls.try_recv().is_err(), "started once, closed nothing");
    assert_eq!(states(&emitted), ["starting", "ended"]);
    let emitted = emitted.lock().unwrap();
    let (_, ended) = emitted.last().unwrap();
    assert_eq!(ended["code"], "unsupported");
    assert!(ended["message"]
        .as_str()
        .is_some_and(|message| message.contains("doesn't support live")));
    drop(tx);
}

#[test]
fn live_text_waits_three_seconds_a_push_and_finals_only_one() {
    // The server refreshes the live text at most once a push: fewer pushes, fewer engine runs.
    assert_eq!(push_samples(true), 3 * 16_000);
    assert_eq!(push_samples(false), 16_000);
}

#[tokio::test(start_paused = true)]
async fn audio_waits_for_a_full_push() {
    let (fake, mut calls) = Fake::new();
    let emitted = Emitted::default();
    let (tx, rx) = mpsc::channel(64);
    let cancel = CancellationToken::new();
    let stop = cancel.clone();

    let driver = async move {
        send(&tx, 3).await;
        assert_eq!(next(&mut calls).await, "start");
        tokio::task::yield_now().await;
        assert!(calls.try_recv().is_err(), "300 ms is not a second yet");
        send(&tx, 7).await;
        assert_eq!(next(&mut calls).await, "push s1 16000");
        drop(tx);
        stop.cancel();
        assert_eq!(next(&mut calls).await, "stop s1");
    };
    tokio::join!(
        run_live(
            &fake,
            push_samples(false),
            rx,
            recorder(&emitted),
            cancel,
            Arc::default(),
            Arc::default()
        ),
        driver
    );
}
