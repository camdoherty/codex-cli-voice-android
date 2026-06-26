use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use clap::Parser;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::time::timeout;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

const VERSION: &str = env!("CARGO_PKG_VERSION");
const BRIDGE_URL_DEFAULT: &str = "ws://127.0.0.1:8765/v1/audio";
const BRIDGE_SAMPLE_RATE: u32 = 24_000;
const BRIDGE_CHANNELS: u16 = 1;
const BRIDGE_FRAME_MS: u32 = 20;
const BRIDGE_SAMPLES_PER_FRAME: u32 = BRIDGE_SAMPLE_RATE * BRIDGE_FRAME_MS / 1_000;
const BRIDGE_BYTES_PER_FRAME: usize = (BRIDGE_SAMPLES_PER_FRAME as usize) * 2;

#[derive(Parser, Debug)]
#[command(version = VERSION, about = "Android Realtime adapter for Codex app-server and Codex Bridge")]
struct Args {
    /// Non-billable app-server JSON-RPC capability check.
    #[arg(long)]
    app_server_smoke: bool,

    /// Non-billable Bridge /v1/audio hello/start/stop check.
    #[arg(long)]
    bridge_smoke: bool,

    /// Bridge /v1/audio WebSocket URL.
    #[arg(long, env = "CODEX_ANDROID_AUDIO_WS_URL", default_value = BRIDGE_URL_DEFAULT)]
    bridge_url: String,

    /// Realtime model for the billable session.
    #[arg(long, env = "CODEX_REALTIME_MODEL", default_value = "gpt-realtime-2")]
    model: String,

    /// Realtime voice for the billable session.
    #[arg(long, env = "CODEX_REALTIME_VOICE", default_value = "marin")]
    voice: String,

    /// Optional working directory for the app-server thread.
    #[arg(long, env = "CODEX_REALTIME_CWD")]
    cwd: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    if args.bridge_smoke {
        return bridge_smoke(&args.bridge_url).await;
    }

    if args.app_server_smoke {
        let mut app = AppServer::spawn().await?;
        app.initialize().await?;
        let thread_id = app.thread_start(args.cwd.as_deref()).await?;
        let voices = app.list_voices().await?;
        app.shutdown().await;
        println!("app_server_smoke=ok thread_id={thread_id} voices={voices}");
        return Ok(());
    }

    if std::env::var_os("OPENAI_API_KEY").is_none() {
        bail!(
            "OPENAI_API_KEY is required for Realtime. Codex/Plus login is not enough for this app-server Realtime path."
        );
    }

    realtime_session(args).await
}

async fn realtime_session(args: Args) -> Result<()> {
    let mut app = AppServer::spawn().await?;
    app.initialize().await?;
    let thread_id = app.thread_start(args.cwd.as_deref()).await?;
    app.realtime_start(&thread_id, &args.model, &args.voice)
        .await?;

    let (ws_stream, _) = connect_async(&args.bridge_url)
        .await
        .with_context(|| format!("connect Bridge {}", args.bridge_url))?;
    let (mut bridge_write, mut bridge_read) = ws_stream.split();

    let hello = timeout(Duration::from_secs(3), bridge_read.next())
        .await
        .context("timed out waiting for Bridge hello")?
        .ok_or_else(|| anyhow!("Bridge closed before hello"))??;
    validate_bridge_hello(&hello)?;
    bridge_write
        .send(Message::Text("{\"type\":\"start\"}".into()))
        .await
        .context("send Bridge start")?;

    eprintln!(
        "realtime_session=started thread_id={thread_id} model={} voice={}",
        args.model, args.voice
    );
    let stop = Arc::new(AtomicBool::new(false));
    let stop_signal = Arc::clone(&stop);
    tokio::spawn(async move {
        let _ = tokio::signal::ctrl_c().await;
        stop_signal.store(true, Ordering::Release);
    });

    loop {
        if stop.load(Ordering::Acquire) {
            break;
        }

        tokio::select! {
            maybe_bridge = bridge_read.next() => {
                let Some(message) = maybe_bridge else {
                    bail!("Bridge WebSocket closed");
                };
                match message? {
                    Message::Binary(frame) => {
                        app.append_audio(&thread_id, &frame).await?;
                    }
                    Message::Text(text) => {
                        if text.contains("\"type\":\"error\"") || text.contains("\"event\":\"error\"") {
                            eprintln!("bridge_event={text}");
                        }
                    }
                    Message::Close(_) => break,
                    _ => {}
                }
            }
            message = app.read_message() => {
                let message = message?;
                if let Some((sample_rate, channels, samples, data)) = output_audio_delta(&message)? {
                    let frames = normalize_pcm(sample_rate, channels, samples, &data)?;
                    for frame in frames {
                        bridge_write.send(Message::Binary(frame.into())).await.context("send Bridge playback frame")?;
                    }
                } else if let Some(text) = transcript_text(&message) {
                    println!("{text}");
                } else if let Some(err) = realtime_error(&message) {
                    eprintln!("realtime_error={err}");
                }
            }
        }
    }

    let _ = app.realtime_stop(&thread_id).await;
    let _ = bridge_write
        .send(Message::Text("{\"type\":\"stop\"}".into()))
        .await;
    app.shutdown().await;
    eprintln!("realtime_session=stopped thread_id={thread_id}");
    Ok(())
}

async fn bridge_smoke(url: &str) -> Result<()> {
    let (mut ws, _) = connect_async(url)
        .await
        .with_context(|| format!("connect Bridge {url}"))?;
    let hello = timeout(Duration::from_secs(3), ws.next())
        .await
        .context("timed out waiting for Bridge hello")?
        .ok_or_else(|| anyhow!("Bridge closed before hello"))??;
    validate_bridge_hello(&hello)?;
    ws.send(Message::Text("{\"type\":\"start\"}".into()))
        .await?;
    let _ = timeout(Duration::from_secs(3), ws.next()).await;
    ws.send(Message::Text("{\"type\":\"stop\"}".into())).await?;
    let _ = timeout(Duration::from_secs(3), ws.next()).await;
    println!("bridge_smoke=ok url={url}");
    Ok(())
}

fn validate_bridge_hello(message: &Message) -> Result<()> {
    let text = match message {
        Message::Text(text) => text,
        other => bail!("expected Bridge hello text frame, got {other:?}"),
    };
    let value: Value = serde_json::from_str(text).context("parse Bridge hello")?;
    let ok = value.get("type").and_then(Value::as_str) == Some("hello")
        && value.get("protocol").and_then(Value::as_i64) == Some(1)
        && value.get("sampleRate").and_then(Value::as_u64) == Some(BRIDGE_SAMPLE_RATE as u64)
        && value.get("channels").and_then(Value::as_u64) == Some(BRIDGE_CHANNELS as u64)
        && value.get("pcm").and_then(Value::as_str) == Some("s16le")
        && value.get("frameMs").and_then(Value::as_u64) == Some(BRIDGE_FRAME_MS as u64);
    if !ok {
        bail!("unexpected Bridge audio contract: {text}");
    }
    Ok(())
}

fn normalize_pcm(
    sample_rate: u32,
    channels: u16,
    samples: Option<u32>,
    data: &[u8],
) -> Result<Vec<Vec<u8>>> {
    if sample_rate != BRIDGE_SAMPLE_RATE || channels != BRIDGE_CHANNELS {
        bail!(
            "unsupported Realtime output audio format: sampleRate={sample_rate} channels={channels}; expected 24000 mono s16le"
        );
    }
    if data.len() % 2 != 0 {
        bail!("Realtime output PCM has odd byte length: {}", data.len());
    }
    if let Some(samples) = samples {
        let expected = samples as usize * channels as usize * 2;
        if expected != data.len() {
            bail!(
                "Realtime samplesPerChannel mismatch: samples={samples} channels={channels} bytes={} expected={expected}",
                data.len()
            );
        }
    }
    let mut frames = Vec::new();
    for chunk in data.chunks(BRIDGE_BYTES_PER_FRAME) {
        let mut frame = chunk.to_vec();
        if frame.len() < BRIDGE_BYTES_PER_FRAME {
            frame.resize(BRIDGE_BYTES_PER_FRAME, 0);
        }
        frames.push(frame);
    }
    Ok(frames)
}

fn output_audio_delta(message: &Value) -> Result<Option<(u32, u16, Option<u32>, Vec<u8>)>> {
    if message.get("method").and_then(Value::as_str) != Some("thread/realtime/outputAudio/delta") {
        return Ok(None);
    }
    let audio = message
        .get("params")
        .and_then(|p| p.get("audio"))
        .ok_or_else(|| anyhow!("missing outputAudio audio payload"))?;
    let sample_rate = audio
        .get("sampleRate")
        .and_then(Value::as_u64)
        .ok_or_else(|| anyhow!("missing outputAudio sampleRate"))? as u32;
    let channels = audio
        .get("numChannels")
        .and_then(Value::as_u64)
        .ok_or_else(|| anyhow!("missing outputAudio numChannels"))? as u16;
    let samples = audio
        .get("samplesPerChannel")
        .and_then(Value::as_u64)
        .map(|v| v as u32);
    let data_b64 = audio
        .get("data")
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("missing outputAudio data"))?;
    let data = BASE64.decode(data_b64).context("decode outputAudio data")?;
    Ok(Some((sample_rate, channels, samples, data)))
}

fn transcript_text(message: &Value) -> Option<String> {
    match message.get("method").and_then(Value::as_str) {
        Some("thread/realtime/transcript/delta") => message
            .get("params")
            .and_then(|p| p.get("delta"))
            .and_then(Value::as_str)
            .map(ToOwned::to_owned),
        Some("thread/realtime/transcript/done") => message
            .get("params")
            .and_then(|p| p.get("text"))
            .and_then(Value::as_str)
            .map(|s| format!("\n{s}")),
        _ => None,
    }
}

fn realtime_error(message: &Value) -> Option<String> {
    if message.get("method").and_then(Value::as_str) != Some("thread/realtime/error") {
        return None;
    }
    message
        .get("params")
        .and_then(|p| p.get("message"))
        .and_then(Value::as_str)
        .map(ToOwned::to_owned)
}

struct AppServer {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    next_id: AtomicI64,
}

impl AppServer {
    async fn spawn() -> Result<Self> {
        let mut child = Command::new("codex")
            .arg("--enable")
            .arg("realtime_conversation")
            .arg("app-server")
            .arg("--stdio")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .context("spawn codex app-server --stdio")?;
        let stdin = child.stdin.take().context("app-server stdin unavailable")?;
        let stdout = child
            .stdout
            .take()
            .context("app-server stdout unavailable")?;
        Ok(Self {
            child,
            stdin,
            stdout: BufReader::new(stdout),
            next_id: AtomicI64::new(1),
        })
    }

    async fn initialize(&mut self) -> Result<()> {
        self.request(
            "initialize",
            json!({
                "clientInfo": {
                    "name": "codex-realtime-adapter",
                    "version": VERSION
                },
                "capabilities": {
                    "experimentalApi": true
                }
            }),
        )
        .await
        .context("initialize app-server with experimentalApi=true")?;
        self.notify("initialized", json!({})).await?;
        Ok(())
    }

    async fn thread_start(&mut self, cwd: Option<&str>) -> Result<String> {
        let mut params = json!({
            "ephemeral": true
        });
        if let Some(cwd) = cwd {
            params["cwd"] = json!(cwd);
        }
        let response = self.request("thread/start", params).await?;
        response
            .get("thread")
            .and_then(|t| t.get("id"))
            .and_then(Value::as_str)
            .map(ToOwned::to_owned)
            .ok_or_else(|| anyhow!("thread/start response missing thread.id: {response}"))
    }

    async fn list_voices(&mut self) -> Result<String> {
        let response = self
            .request("thread/realtime/listVoices", json!({}))
            .await?;
        Ok(response
            .get("voices")
            .map(Value::to_string)
            .unwrap_or_else(|| response.to_string()))
    }

    async fn realtime_start(&mut self, thread_id: &str, model: &str, voice: &str) -> Result<()> {
        self.request(
            "thread/realtime/start",
            json!({
                "threadId": thread_id,
                "model": model,
                "outputModality": "audio",
                "includeStartupContext": true,
                "transport": { "type": "websocket" },
                "version": "v2",
                "voice": voice
            }),
        )
        .await
        .context("thread/realtime/start")?;
        Ok(())
    }

    async fn append_audio(&mut self, thread_id: &str, frame: &[u8]) -> Result<()> {
        if frame.len() != BRIDGE_BYTES_PER_FRAME {
            bail!(
                "unexpected Bridge mic frame size: {} bytes; expected {BRIDGE_BYTES_PER_FRAME}",
                frame.len()
            );
        }
        self.send_request(
            "thread/realtime/appendAudio",
            json!({
                "threadId": thread_id,
                "audio": {
                    "data": BASE64.encode(frame),
                    "sampleRate": BRIDGE_SAMPLE_RATE,
                    "numChannels": BRIDGE_CHANNELS,
                    "samplesPerChannel": BRIDGE_SAMPLES_PER_FRAME,
                    "itemId": null
                }
            }),
        )
        .await?;
        Ok(())
    }

    async fn realtime_stop(&mut self, thread_id: &str) -> Result<()> {
        self.request("thread/realtime/stop", json!({ "threadId": thread_id }))
            .await?;
        Ok(())
    }

    async fn request(&mut self, method: &str, params: Value) -> Result<Value> {
        let id = self.send_request(method, params).await?;
        loop {
            let message = self.read_message().await?;
            if message.get("id").and_then(Value::as_i64) == Some(id) {
                if let Some(error) = message.get("error") {
                    bail!("{method} failed: {error}");
                }
                return Ok(message.get("result").cloned().unwrap_or_else(|| json!({})));
            }
        }
    }

    async fn send_request(&mut self, method: &str, params: Value) -> Result<i64> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let request = json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params
        });
        self.write_message(&request).await?;
        Ok(id)
    }

    async fn notify(&mut self, method: &str, params: Value) -> Result<()> {
        self.write_message(&json!({
            "jsonrpc": "2.0",
            "method": method,
            "params": params
        }))
        .await
    }

    async fn write_message(&mut self, value: &Value) -> Result<()> {
        let payload = serde_json::to_string(value)?;
        self.stdin.write_all(payload.as_bytes()).await?;
        self.stdin.write_all(b"\n").await?;
        self.stdin.flush().await?;
        Ok(())
    }

    async fn read_message(&mut self) -> Result<Value> {
        let mut line = String::new();
        let n = self.stdout.read_line(&mut line).await?;
        if n == 0 {
            bail!("app-server stdout closed");
        }
        serde_json::from_str(&line).with_context(|| format!("parse app-server JSON-RPC: {line}"))
    }

    async fn shutdown(&mut self) {
        let _ = self.child.start_kill();
        let _ = timeout(Duration::from_secs(2), self.child.wait()).await;
    }
}
