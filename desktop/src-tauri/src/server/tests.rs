use super::{decode_event_reader, ServerEvent, ServerProcess};
use bytes::Bytes;
use futures_util::{stream, StreamExt};
use tokio_util::io::StreamReader;

const EVENTS: &str = concat!(
    "{\"type\":\"progress\",\"progress\":25}\n",
    "{\"type\":\"segment\",\"start\":0.0,\"end\":1.5,\"text\":\"hello\",\"speaker\":null}\n",
    "{\"type\":\"result\",\"text\":\"hello\"}\n",
);

#[tokio::test]
async fn decodes_events_across_arbitrary_chunk_boundaries() {
    for chunk_size in 1..EVENTS.len() {
        let chunks = EVENTS
            .as_bytes()
            .chunks(chunk_size)
            .map(|chunk| Ok::<_, std::io::Error>(Bytes::copy_from_slice(chunk)))
            .collect::<Vec<_>>();
        let events = decode_event_reader(StreamReader::new(stream::iter(chunks)))
            .collect::<Vec<_>>()
            .await;

        assert_eq!(events.len(), 3, "chunk size {chunk_size}");
        assert!(matches!(events[0], Ok(ServerEvent::Progress { progress: 25 })));
        assert!(matches!(events[1], Ok(ServerEvent::Segment { ref text, .. }) if text == "hello"));
        assert!(matches!(events[2], Ok(ServerEvent::Result { ref text }) if text == "hello"));
    }
}

#[tokio::test]
async fn decodes_a_warning_event_ahead_of_the_segments() {
    let lines = concat!(
        "{\"type\":\"warning\",\"code\":\"diarization_failed\",\"message\":\"model missing\"}\n",
        "{\"type\":\"segment\",\"start\":0.0,\"end\":1.5,\"text\":\"hello\"}\n",
    );
    let chunks = stream::iter([Ok::<_, std::io::Error>(Bytes::from_static(lines.as_bytes()))]);
    let events = decode_event_reader(StreamReader::new(chunks)).collect::<Vec<_>>().await;

    assert_eq!(events.len(), 2);
    assert!(matches!(
        events[0],
        Ok(ServerEvent::Warning { ref code, ref message }) if code == "diarization_failed" && message == "model missing"
    ));
    // `speaker` is absent when diarization produced nothing, and must still decode.
    assert!(matches!(events[1], Ok(ServerEvent::Segment { speaker: None, .. })));
}

#[tokio::test]
async fn reports_invalid_json_instead_of_dropping_it() {
    let chunks = stream::iter([Ok::<_, std::io::Error>(Bytes::from_static(b"not json\n"))]);
    let events = decode_event_reader(StreamReader::new(chunks)).collect::<Vec<_>>().await;

    assert_eq!(events.len(), 1);
    assert!(events[0].is_err());
}

#[tokio::test]
async fn accepts_result_lines_larger_than_the_default_codec_limit() {
    let text = "a".repeat(16 * 1024);
    let line = format!("{{\"type\":\"result\",\"text\":\"{text}\"}}\n");
    let chunks = stream::iter([Ok::<_, std::io::Error>(Bytes::from(line))]);
    let events = decode_event_reader(StreamReader::new(chunks)).collect::<Vec<_>>().await;

    assert!(matches!(events.as_slice(), [Ok(ServerEvent::Result { text: result })] if result == &text));
}

#[test]
fn stderr_tail_keeps_the_newest_output() {
    let mut tail = super::StderrTail::default();
    for index in 0..2000 {
        tail.push(&format!("line {index}\n"));
    }
    let snapshot = tail.snapshot();

    assert!(snapshot.len() <= super::StderrTail::LIMIT);
    // The crash message is the last thing a dying child writes, so that is the
    // end that has to survive the cap.
    assert!(snapshot.ends_with("line 1999"), "{snapshot}");
    assert!(!snapshot.contains("line 0\n"), "{snapshot}");
}

#[test]
fn stderr_tail_reports_a_read_failure_instead_of_looking_empty() {
    let mut tail = super::StderrTail::default();
    tail.finish(Some("stream did not contain valid UTF-8".to_string()));

    assert!(tail.is_finished());
    assert!(tail.snapshot().contains("stream did not contain valid UTF-8"));
}

/// One token, three places: the server's environment, the client's `Authorization` header and
/// `server.json`. If any of them drifts, the app locks itself out or an agent gets a dead token.
#[cfg(unix)]
#[tokio::test]
async fn one_token_reaches_the_server_the_client_and_server_json() {
    use std::io::{Read, Write};
    use std::os::unix::fs::PermissionsExt;

    let dir = std::env::temp_dir().join(format!("vibe-spawn-test-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    // A stand-in for vibe-server: writes down the token it was started with, reports `port` as its
    // own, and stays alive until killed. The listener plays the API behind that port.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let script = dir.join("fake-server");
    std::fs::write(
        &script,
        format!(
            "#!/bin/sh\necho \"$VIBE_SERVER_TOKEN\" > \"$(dirname \"$0\")/seen-token\"\n\
             echo '{{\"status\":\"ready\",\"port\":{port}}}'\nexec sleep 30\n"
        ),
    )
    .unwrap();
    std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
    let request = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let (mut head, mut chunk) = (Vec::new(), [0u8; 1024]);
        while !head.windows(4).any(|window| window == b"\r\n\r\n") {
            match stream.read(&mut chunk).unwrap() {
                0 => break,
                read => head.extend_from_slice(&chunk[..read]),
            }
        }
        stream
            .write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 0\r\nconnection: close\r\n\r\n")
            .unwrap();
        String::from_utf8_lossy(&head).to_ascii_lowercase()
    });

    let info_path = dir.join("server.json");
    let mut process = ServerProcess::spawn(
        &script,
        None,
        0,
        crate::cmd::config::CpuVariant::Auto,
        Some(info_path.clone()),
    )
    .unwrap();

    let token = std::fs::read_to_string(dir.join("seen-token")).unwrap().trim().to_string();
    assert!(!token.is_empty(), "the server was started without a token");
    let info: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&info_path).unwrap()).unwrap();
    assert_eq!(info, serde_json::json!({ "port": port, "token": token }));

    process
        .client()
        .get(format!("{}/ready", process.base_url()))
        .send()
        .await
        .unwrap();
    let head = request.join().unwrap();
    assert!(head.contains(&format!("authorization: bearer {token}")), "{head}");

    process.kill();
    assert!(!info_path.exists(), "server.json outlived its server");
    std::fs::remove_dir_all(&dir).unwrap();
}
