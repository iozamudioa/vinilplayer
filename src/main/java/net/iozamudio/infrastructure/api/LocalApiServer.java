package net.iozamudio.infrastructure.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.iozamudio.application.port.in.MediaControlUseCase;
import net.iozamudio.model.MediaInfo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class LocalApiServer {
    private static final String API_PREFIX = "/api/v1";

    private final HttpServer server;
    private final Gson gson;
    private final Supplier<MediaInfo> stateSupplier;
    private final MediaControlUseCase mediaControl;
    private final String apiToken;
    private final long startedAtMs;

    public LocalApiServer(Supplier<MediaInfo> stateSupplier, MediaControlUseCase mediaControl) {
        this.gson = new Gson();
        this.stateSupplier = stateSupplier;
        this.mediaControl = mediaControl;
        this.startedAtMs = System.currentTimeMillis();

        String configuredToken = System.getProperty("vinil.api.token", "token-prueba").trim();
        this.apiToken = configuredToken.isEmpty()
            ? "token-prueba"
                : configuredToken;

        String host = resolveHost();
        int port = resolvePort();

        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start local API server", e);
        }

        this.server.createContext(API_PREFIX + "/health", new HealthHandler());
        this.server.createContext(API_PREFIX + "/state", new StateHandler());
        this.server.createContext(API_PREFIX + "/control", new ControlHandler());
        this.server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
        InetSocketAddress address = server.getAddress();
        System.out.println("Local API listening at http://" + address.getHostString() + ":" + address.getPort() + API_PREFIX);
        System.out.println("Local API control token: " + apiToken);
    }

    public void stop() {
        server.stop(0);
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

        Map<String, Object> playback = new LinkedHashMap<>();
        playback.put("status", info.status());
        playback.put("positionSeconds", info.position());
        playback.put("durationSeconds", info.duration());
        playback.put("progress", info.getProgress());

        Map<String, Object> track = new LinkedHashMap<>();
        track.put("artist", info.artist());
        track.put("title", info.title());
        track.put("thumbnailBase64", info.thumbnail());

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
        payload.put("capabilities", capabilities);
        return payload;
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

    private static class ControlRequest {
        String action;
        Double seekSeconds;
        String requestId;
    }
}
