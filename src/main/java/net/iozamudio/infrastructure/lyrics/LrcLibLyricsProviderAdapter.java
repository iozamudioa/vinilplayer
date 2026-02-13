package net.iozamudio.infrastructure.lyrics;

import com.google.gson.Gson;
import net.iozamudio.application.port.out.LyricsProviderPort;
import net.iozamudio.model.LyricsLine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LrcLibLyricsProviderAdapter implements LyricsProviderPort {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_ATTEMPTS = 2;

    private final HttpClient httpClient;
    private final Gson gson;
    private final SqliteLyricsCache cache;

    public LrcLibLyricsProviderAdapter() {
        this(SqliteLyricsCache.createDefault());
    }

    public LrcLibLyricsProviderAdapter(SqliteLyricsCache cache) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.gson = new Gson();
        this.cache = cache;
    }

    @Override
    public List<LyricsLine> fetchSyncedLyrics(String artist, String title) {
        List<LyricsLine> cached = cache.getCachedLyrics(artist, title);
        if (!cached.isEmpty()) {
            return cached;
        }
        System.out.println("Lyrics cache MISS: " + artist + " - " + title + " (fetch LRCLIB)");

        String encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8);
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
        URI uri = URI.create("https://lrclib.net/api/get?artist_name=" + encodedArtist + "&track_name=" + encodedTitle);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            List<LyricsLine> result = fetchOnce(uri);
            if (!result.isEmpty()) {
                cache.saveLyrics(artist, title, result);
                return result;
            }

            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return List.of();
    }

    private List<LyricsLine> fetchOnce(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "vinilplayer/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return List.of();
            }

            LrcLibResponse payload = gson.fromJson(response.body(), LrcLibResponse.class);
            if (payload == null || payload.syncedLyrics == null || payload.syncedLyrics.isBlank()) {
                return List.of();
            }

            return parseSyncedLyrics(payload.syncedLyrics);
        } catch (Exception e) {
            System.err.println("Lyrics fetch failed: " + e.getMessage());
            return List.of();
        }
    }

    private List<LyricsLine> parseSyncedLyrics(String lrcText) {
        List<LyricsLine> lines = new ArrayList<>();
        String[] rawLines = lrcText.split("\\R");

        for (String raw : rawLines) {
            int start = raw.indexOf('[');
            int end = raw.indexOf(']');
            if (start != 0 || end <= start) {
                continue;
            }

            String timestamp = raw.substring(start + 1, end).trim();
            String text = raw.substring(end + 1).trim();
            if (text.isEmpty()) {
                continue;
            }

            double seconds = parseTimestampSeconds(timestamp);
            if (seconds >= 0) {
                lines.add(new LyricsLine(seconds, text));
            }
        }

        return lines;
    }

    private double parseTimestampSeconds(String timestamp) {
        try {
            String[] minSec = timestamp.split(":");
            if (minSec.length != 2) {
                return -1;
            }

            int minutes = Integer.parseInt(minSec[0]);
            double sec = Double.parseDouble(minSec[1]);
            return minutes * 60 + sec;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static class LrcLibResponse {
        String syncedLyrics;
    }
}