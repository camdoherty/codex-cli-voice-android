package io.github.codex_cli_voice_android.aecshim;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public final class LoopbackAudioServer extends WebSocketServer {
    private final AudioEngine audioEngine;
    private volatile WebSocket client;

    LoopbackAudioServer(InetSocketAddress address, AudioEngine audioEngine) {
        super(address);
        this.audioEngine = audioEngine;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        WebSocket existing = client;
        if (existing != null && existing.isOpen()) {
            conn.send("{\"type\":\"error\",\"message\":\"client already connected\"}");
            conn.close(1013, "client already connected");
            return;
        }
        client = conn;
        AecShimState.clientConnected = true;
        AecShimState.resetCounters();
        conn.send("{\"type\":\"hello\",\"protocol\":1,\"sampleRate\":24000,\"channels\":1,\"pcm\":\"s16le\",\"frameMs\":20}");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (conn == client) {
            audioEngine.stop();
            client = null;
            AecShimState.clientConnected = false;
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.contains("\"type\":\"start\"")) {
            audioEngine.start(this::sendMicFrame);
            conn.send(AecShimState.statsJson());
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
        WebSocket conn = client;
        if (conn == null || !conn.isOpen()) {
            AecShimState.micDrops.incrementAndGet();
            return;
        }
        conn.send(frame);
        AecShimState.micFrames.incrementAndGet();
    }
}
