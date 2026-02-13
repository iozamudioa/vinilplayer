package net.iozamudio.infrastructure.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.iozamudio.util.ActiveMusicSource;
import net.iozamudio.application.port.in.LyricsUseCase;
import net.iozamudio.model.LyricsLine;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import net.iozamudio.application.port.in.MediaControlUseCase;
import net.iozamudio.model.MediaInfo;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class LocalApiServer {
    private static final String API_PREFIX = "/api/v1";

    private final HttpServer server;
    private final Gson gson;
    private final Supplier<MediaInfo> stateSupplier;
    private final MediaControlUseCase mediaControl;
    private final LyricsUseCase lyricsUseCase;
    private final String apiToken;
    private final long startedAtMs;
    private final StateWebSocketServer wsServer;
    private final ScheduledExecutorService wsBroadcastExecutor;
    private final int wsPort;
    private volatile String thumbnailHdCacheKey = "";
    private volatile String thumbnailHdCacheValue = "";
    private volatile String lyricsCacheTrackKey = "";
    private volatile List<LyricsLine> lyricsCache = List.of();
    private volatile String playbackStatusCache = "STOPPED";
    private volatile double playbackBasePosition = 0;
    private volatile double playbackBaseDuration = 0;
    private volatile long playbackBaseCapturedAtMs = 0;
    private volatile double lastReportedRawPosition = -1;

    public LocalApiServer(Supplier<MediaInfo> stateSupplier, MediaControlUseCase mediaControl, LyricsUseCase lyricsUseCase) {
        this.gson = new Gson();
        this.stateSupplier = stateSupplier;
        this.mediaControl = mediaControl;
        this.lyricsUseCase = lyricsUseCase;
        this.startedAtMs = System.currentTimeMillis();

        String configuredToken = System.getProperty("vinil.api.token", "token-prueba").trim();
        this.apiToken = configuredToken.isEmpty()
            ? "token-prueba"
                : configuredToken;

        String host = resolveHost();
        int port = resolvePort();
        this.wsPort = resolveWsPort(port);

        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start local API server", e);
        }

        this.wsServer = new StateWebSocketServer(host, wsPort);
        this.wsBroadcastExecutor = Executors.newSingleThreadScheduledExecutor();

        this.server.createContext(API_PREFIX + "/health", new HealthHandler());
        this.server.createContext(API_PREFIX + "/state", new StateHandler());
        this.server.createContext(API_PREFIX + "/control", new ControlHandler());
        this.server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
        wsServer.start();
        wsBroadcastExecutor.scheduleAtFixedRate(this::pushStateToWebSocketClients, 0, 250, TimeUnit.MILLISECONDS);

        InetSocketAddress address = server.getAddress();
        System.out.println("Local API listening at http://" + address.getHostString() + ":" + address.getPort() + API_PREFIX);
        System.out.println("Local API websocket at ws://" + normalizeHostForLog(address.getHostString()) + ":" + wsPort + API_PREFIX + "/ws");
        System.out.println("Local API control token: " + apiToken);
    }

    public void stop() {
        server.stop(0);
        wsBroadcastExecutor.shutdownNow();
        try {
            wsServer.stop(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("version", resolveAppVersion());
            payload.put("uptimeMs", System.currentTimeMillis() - startedAtMs);
            payload.put("timestamp", Instant.now().toString());

            sendJson(exchange, 200, payload);
        }
    }

    private class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }

            sendJson(exchange, 200, buildStatePayload());
        }
    }

    private class ControlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCorsHeaders(exchange.getResponseHeaders());
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }

            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "unauthorized", "Missing or invalid API token");
                return;
            }

            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            if (rawBody.length == 0) {
                sendError(exchange, 400, "invalid_request", "Request body is required");
                return;
            }

            ControlRequest request;
            try {
                request = gson.fromJson(new String(rawBody, StandardCharsets.UTF_8), ControlRequest.class);
            } catch (JsonSyntaxException e) {
                sendError(exchange, 400, "invalid_json", "Malformed JSON payload");
                return;
            }

            if (request == null || request.action == null || request.action.isBlank()) {
                sendError(exchange, 400, "invalid_action", "Field 'action' is required");
                return;
            }

            String action = request.action.toLowerCase(Locale.ROOT);
            try {
                switch (action) {
                    case "playpause" -> mediaControl.playPause();
                    case "next" -> mediaControl.next();
                    case "previous" -> mediaControl.previous();
                    case "focussource" -> mediaControl.openCurrentInBrowser();
                    case "seek" -> {
                        if (request.seekSeconds == null) {
                            sendError(exchange, 400, "invalid_seek", "Field 'seekSeconds' is required for seek action");
                            return;
                        }
                        mediaControl.seekToSeconds(request.seekSeconds);
                    }
                    default -> {
                        sendError(exchange, 400, "unknown_action", "Unsupported action: " + action);
                        return;
                    }
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("accepted", true);
                payload.put("action", action);
                payload.put("executedAt", Instant.now().toString());
                payload.put("state", buildStatePayload());
                sendJson(exchange, 200, payload);
                pushStateToWebSocketClients();
            } catch (Exception e) {
                sendError(exchange, 500, "control_failed", e.getMessage() == null ? "Control command failed" : e.getMessage());
            }
        }
    }

    private Map<String, Object> buildStatePayload() {
        MediaInfo info = stateSupplier.get();
        if (info == null) {
            info = new MediaInfo("", "", "STOPPED", "");
        }

        double effectivePosition = resolveEffectivePositionSeconds(info);

        Map<String, Object> playback = new LinkedHashMap<>();
        playback.put("status", info.status());
        playback.put("positionSeconds", effectivePosition);
        playback.put("durationSeconds", info.duration());
        playback.put("progress", resolveProgress(effectivePosition, info.duration()));

        Map<String, Object> track = new LinkedHashMap<>();
        track.put("artist", info.artist());
        track.put("title", info.title());
        track.put("thumbnailBase64", info.thumbnail());
        track.put("thumbnailHdBase64", resolveThumbnailHd(info.thumbnail()));
        track.put("source", ActiveMusicSource.get());

        Map<String, Object> lyrics = buildLyricsPayload(info, effectivePosition);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("canPlayPause", true);
        capabilities.put("canSeek", true);
        capabilities.put("canNext", true);
        capabilities.put("canPrevious", true);
        capabilities.put("canFocusSource", true);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("playback", playback);
        payload.put("track", track);
        payload.put("lyrics", lyrics);
        payload.put("capabilities", capabilities);
        return payload;
    }

    private Map<String, Object> buildLyricsPayload(MediaInfo info, double effectivePositionSeconds) {
        List<LyricsLine> lines = resolveLyrics(info.artist(), info.title());

        List<Map<String, Object>> serialized = lines.stream()
            .map(line -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("timeSeconds", line.timeSeconds());
                item.put("text", line.text());
                return item;
            })
            .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lines", serialized);
        payload.put("activeIndex", resolveActiveLyricsIndex(lines, effectivePositionSeconds));
        return payload;
    }

    private double resolveEffectivePositionSeconds(MediaInfo info) {
        String status = info.status() == null ? "STOPPED" : info.status().toUpperCase(Locale.ROOT);
        double rawPosition = Math.max(0, info.position());
        double duration = Math.max(0, info.duration());
        long nowMs = System.currentTimeMillis();

        boolean shouldResetBase = !"PLAYING".equals(status)
            || !"PLAYING".equals(playbackStatusCache)
            || playbackBaseCapturedAtMs <= 0
            || rawPosition != lastReportedRawPosition
            || rawPosition < playbackBasePosition - 0.75;

        if (shouldResetBase) {
            playbackStatusCache = status;
            playbackBasePosition = rawPosition;
            playbackBaseDuration = duration;
            playbackBaseCapturedAtMs = nowMs;
            lastReportedRawPosition = rawPosition;
            return rawPosition;
        }

        double elapsedSeconds = Math.max(0, (nowMs - playbackBaseCapturedAtMs) / 1000.0);
        double extrapolated = playbackBasePosition + elapsedSeconds;
        if (playbackBaseDuration > 0) {
            extrapolated = Math.min(playbackBaseDuration, extrapolated);
        }

        return extrapolated;
    }

    private double resolveProgress(double positionSeconds, double durationSeconds) {
        if (durationSeconds <= 0) {
            return 0;
        }
        return Math.min(1.0, Math.max(0, positionSeconds / durationSeconds));
    }

    private List<LyricsLine> resolveLyrics(String artist, String title) {
        if (lyricsUseCase == null) {
            return List.of();
        }

        String trackKey = (artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT))
            + "::"
            + (title == null ? "" : title.trim().toLowerCase(Locale.ROOT));

        if (trackKey.equals("::")) {
            lyricsCacheTrackKey = "";
            lyricsCache = List.of();
            return List.of();
        }

        if (trackKey.equals(lyricsCacheTrackKey)) {
            return lyricsCache;
        }

        try {
            List<LyricsLine> fetched = lyricsUseCase.getSyncedLyrics(artist, title);
            lyricsCacheTrackKey = trackKey;
            lyricsCache = fetched == null ? List.of() : fetched;
            return lyricsCache;
        } catch (Exception ignored) {
            lyricsCacheTrackKey = trackKey;
            lyricsCache = List.of();
            return List.of();
        }
    }

    private int resolveActiveLyricsIndex(List<LyricsLine> lines, double positionSeconds) {
        if (lines == null || lines.isEmpty()) {
            return -1;
        }

        int activeIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (positionSeconds >= lines.get(i).timeSeconds()) {
                activeIndex = i;
            } else {
                break;
            }
        }

        return activeIndex;
    }

    private String resolveThumbnailHd(String base64Thumbnail) {
        String normalized = base64Thumbnail == null ? "" : base64Thumbnail.trim();
        if (normalized.isEmpty()) {
            thumbnailHdCacheKey = "";
            thumbnailHdCacheValue = "";
            return "";
        }

        if (normalized.equals(thumbnailHdCacheKey) && !thumbnailHdCacheValue.isEmpty()) {
            return thumbnailHdCacheValue;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(normalized);
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(decoded));
            if (source == null) {
                return normalized;
            }

            int width = source.getWidth();
            int height = source.getHeight();
            int minDimension = Math.min(width, height);
            if (minDimension >= 640) {
                thumbnailHdCacheKey = normalized;
                thumbnailHdCacheValue = normalized;
                return normalized;
            }

            double scale = 640.0 / Math.max(1, minDimension);
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));

            BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = output.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(output, "png", outputStream);
            String encoded = Base64.getEncoder().encodeToString(outputStream.toByteArray());

            thumbnailHdCacheKey = normalized;
            thumbnailHdCacheValue = encoded;
            return encoded;
        } catch (Exception ignored) {
            return normalized;
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-Api-Token");
        if (provided == null || provided.isBlank()) {
            return false;
        }

        return apiToken.equals(provided.trim());
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("timestamp", Instant.now().toString());
        sendJson(exchange, status, payload);
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowedMethod) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowedMethod + ", OPTIONS");
        sendError(exchange, 405, "method_not_allowed", "Use method " + allowedMethod);
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] payload = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        addCorsHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type, X-Api-Token");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private String resolveAppVersion() {
        Package pkg = LocalApiServer.class.getPackage();
        String implementationVersion = pkg == null ? null : pkg.getImplementationVersion();
        return implementationVersion == null ? "dev" : implementationVersion;
    }

    private String resolveHost() {
        String host = System.getProperty("vinil.api.host", "0.0.0.0").trim();
        return host.isEmpty() ? "127.0.0.1" : host;
    }

    private int resolveWsPort(int httpPort) {
        String raw = System.getProperty("vinil.api.ws.port", String.valueOf(httpPort + 1)).trim();
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1 || parsed > 65535) {
                return httpPort + 1;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return httpPort + 1;
        }
    }

    private int resolvePort() {
        String raw = System.getProperty("vinil.api.port", "8750").trim();
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1 || parsed > 65535) {
                return 8750;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return 8750;
        }
    }

    private void pushStateToWebSocketClients() {
        try {
            if (!wsServer.hasConnections()) {
                return;
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "state");
            envelope.put("state", buildStatePayload());
            wsServer.broadcastState(gson.toJson(envelope));
        } catch (Exception ignored) {
        }
    }

    private String normalizeHostForLog(String host) {
        if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
            return "localhost";
        }
        return host;
    }

    private class StateWebSocketServer extends WebSocketServer {
        private final CopyOnWriteArraySet<WebSocket> clients = new CopyOnWriteArraySet<>();

        StateWebSocketServer(String host, int port) {
            super(new InetSocketAddress(host, port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            if (!isExpectedPath(handshake) || !isWebSocketAuthorized(handshake)) {
                conn.close(1008, "Unauthorized or invalid path");
                return;
            }

            clients.add(conn);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            clients.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            if (conn != null) {
                clients.remove(conn);
            }
        }

        @Override
        public void onStart() {
            setConnectionLostTimeout(30);
        }

        boolean hasConnections() {
            return !clients.isEmpty();
        }

        void broadcastState(String payload) {
            for (WebSocket client : clients) {
                if (client.isOpen()) {
                    client.send(payload);
                } else {
                    clients.remove(client);
                }
            }
        }

        private boolean isExpectedPath(ClientHandshake handshake) {
            String resource = handshake.getResourceDescriptor();
            if (resource == null || resource.isBlank()) {
                return false;
            }

            String path = resource;
            int queryIndex = resource.indexOf('?');
            if (queryIndex >= 0) {
                path = resource.substring(0, queryIndex);
            }

            return (API_PREFIX + "/ws").equals(path);
        }

        private boolean isWebSocketAuthorized(ClientHandshake handshake) {
            if (apiToken.isBlank()) {
                return true;
            }

            String headerToken = handshake.getFieldValue("X-Api-Token");
            if (headerToken != null && apiToken.equals(headerToken.trim())) {
                return true;
            }

            String resource = handshake.getResourceDescriptor();
            if (resource == null || resource.isBlank()) {
                return false;
            }

            int queryIndex = resource.indexOf('?');
            if (queryIndex < 0 || queryIndex + 1 >= resource.length()) {
                return false;
            }

            String query = resource.substring(queryIndex + 1);
            for (String pair : query.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }

                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }

                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                if ("token".equals(key) && apiToken.equals(value)) {
                    return true;
                }
            }

            return false;
        }
    }

    private static class ControlRequest {
        String action;
        Double seekSeconds;
        String requestId;
    }
}
