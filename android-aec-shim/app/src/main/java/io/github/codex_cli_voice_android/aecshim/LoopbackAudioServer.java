package io.github.codex_cli_voice_android.aecshim;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public final class LoopbackAudioServer extends WebSocketServer {
    private final AudioEngine audioEngine;
    private final TextVoiceController textVoiceController;
    private volatile WebSocket audioClient;

    LoopbackAudioServer(
            InetSocketAddress address,
            AudioEngine audioEngine,
            TextVoiceController textVoiceController) {
        super(address);
        this.audioEngine = audioEngine;
        this.textVoiceController = textVoiceController;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (isTextVoicePath(handshake)) {
            textVoiceController.onOpen(conn);
            return;
        }

        WebSocket existing = audioClient;
        if (existing != null && existing.isOpen()) {
            conn.send("{\"type\":\"error\",\"message\":\"client already connected\"}");
            conn.close(1013, "client already connected");
            return;
        }
        audioClient = conn;
        AecShimState.clientConnected = true;
        AecShimState.resetCounters();
        conn.send("{\"type\":\"hello\",\"protocol\":1,\"sampleRate\":24000,\"channels\":1,\"pcm\":\"s16le\",\"frameMs\":20}");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (textVoiceController.owns(conn)) {
            textVoiceController.onClose(conn);
            return;
        }
        if (conn == audioClient) {
            audioEngine.stop();
            audioClient = null;
            AecShimState.clientConnected = false;
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (textVoiceController.owns(conn)) {
            textVoiceController.onMessage(message);
            return;
        }
        if (message.contains("\"type\":\"start\"")) {
            if (audioEngine.start(this::sendMicFrame)) {
                conn.send(AecShimState.statsJson());
            } else {
                conn.send("{\"type\":\"error\",\"message\":\"" + escapeJson(AecShimState.lastError) + "\"}");
            }
        } else if (message.contains("\"type\":\"stop\"")) {
            audioEngine.stop();
            conn.send(AecShimState.statsJson());
        } else if (message.contains("\"type\":\"playback.clear\"")) {
            audioEngine.clearPlayback();
            conn.send(AecShimState.statsJson());
        } else if (message.contains("\"type\":\"ping\"")) {
            conn.send(message.replace("\"ping\"", "\"pong\""));
        } else {
            conn.send("{\"type\":\"error\",\"message\":\"unknown control frame\"}");
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        if (textVoiceController.owns(conn)) {
            conn.send("{\"event\":\"error\",\"code\":\"invalid_action\",\"message\":\"binary frames are not supported on /v1/text-voice\"}");
            return;
        }
        byte[] data = new byte[message.remaining()];
        message.get(data);
        audioEngine.enqueuePlayback(data);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        AecShimState.lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    @Override
    public void onStart() {
        AecShimState.serverListening = true;
    }

    private void sendMicFrame(byte[] frame) {
        WebSocket conn = audioClient;
        if (conn == null || !conn.isOpen()) {
            AecShimState.micDrops.incrementAndGet();
            return;
        }
        conn.send(frame);
        AecShimState.micFrames.incrementAndGet();
    }

    private static boolean isTextVoicePath(ClientHandshake handshake) {
        String path = handshake == null ? null : handshake.getResourceDescriptor();
        return path != null && (path.equals("/v1/text-voice") || path.startsWith("/v1/text-voice?"));
    }

    private static String escapeJson(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
