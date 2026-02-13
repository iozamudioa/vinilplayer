package net.iozamudio.infrastructure.lyrics;

import com.google.gson.Gson;
import net.iozamudio.model.LyricsLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SqliteLyricsCache {
    private static final int MAX_CACHE_ROWS = 400;

    private final String jdbcUrl;
    private final Path databasePath;
    private final Gson gson;

    public SqliteLyricsCache(Path dbFile) {
        try {
            Path parent = dbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create cache directory", e);
        }

        this.databasePath = dbFile.toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + databasePath;
        this.gson = new Gson();
        initSchema();
        System.out.println("Lyrics cache DB: " + databasePath);
    }

    public static SqliteLyricsCache createDefault() {
        Path dbPath = Path.of(System.getProperty("user.home"), ".vinilplayer", "cache", "lyrics-cache.db");
        return new SqliteLyricsCache(dbPath);
    }

    public List<LyricsLine> getCachedLyrics(String artist, String title) {
        String artistNorm = normalize(artist);
        String titleNorm = normalize(title);
        if (artistNorm.isBlank() || titleNorm.isBlank()) {
            return List.of();
        }

        try (Connection connection = openConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT lyrics_json FROM lyrics_cache WHERE artist_norm = ? AND title_norm = ?")) {
            select.setString(1, artistNorm);
            select.setString(2, titleNorm);

            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return List.of();
                }

                List<LyricsLine> lyrics = parseLyrics(rs.getString("lyrics_json"));
                if (!lyrics.isEmpty()) {
                    System.out.println("Lyrics cache HIT: " + artist + " - " + title);
                }
                touchEntry(connection, artistNorm, titleNorm);
                return lyrics;
            }
        } catch (Exception e) {
            System.err.println("Lyrics cache read failed: " + e.getMessage());
            return List.of();
        }
    }

    public void saveLyrics(String artist, String title, List<LyricsLine> lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }

        String artistNorm = normalize(artist);
        String titleNorm = normalize(title);
        if (artistNorm.isBlank() || titleNorm.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        String json = gson.toJson(lyrics);

        String upsert = """
                INSERT INTO lyrics_cache (artist, title, artist_norm, title_norm, lyrics_json, last_played, updated_at, play_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(artist_norm, title_norm) DO UPDATE SET
                  artist = excluded.artist,
                  title = excluded.title,
                  lyrics_json = excluded.lyrics_json,
                  last_played = excluded.last_played,
                  updated_at = excluded.updated_at,
                  play_count = lyrics_cache.play_count + 1
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setString(1, artist);
            statement.setString(2, title);
            statement.setString(3, artistNorm);
            statement.setString(4, titleNorm);
            statement.setString(5, json);
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
            System.out.println("Lyrics cache SAVE: " + artist + " - " + title + " (" + lyrics.size() + " lines)");

            pruneOldEntries(connection);
        } catch (Exception e) {
            System.err.println("Lyrics cache write failed: " + e.getMessage());
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() {
        String createTable = """
                CREATE TABLE IF NOT EXISTS lyrics_cache (
                    artist TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist_norm TEXT NOT NULL,
                    title_norm TEXT NOT NULL,
                    lyrics_json TEXT NOT NULL,
                    last_played INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    play_count INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (artist_norm, title_norm)
                )
                """;

        String createIndex = "CREATE INDEX IF NOT EXISTS idx_lyrics_cache_last_played ON lyrics_cache(last_played DESC)";

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(createTable);
            statement.execute(createIndex);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize lyrics cache", e);
        }
    }

    private void touchEntry(Connection connection, String artistNorm, String titleNorm) {
        String touchSql = """
                UPDATE lyrics_cache
                SET last_played = ?,
                    play_count = play_count + 1
                WHERE artist_norm = ? AND title_norm = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(touchSql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, artistNorm);
            statement.setString(3, titleNorm);
            statement.executeUpdate();
        } catch (Exception e) {
            System.err.println("Lyrics cache touch failed: " + e.getMessage());
        }
    }

    private void pruneOldEntries(Connection connection) {
        String pruneSql = """
                DELETE FROM lyrics_cache
                WHERE rowid IN (
                    SELECT rowid
                    FROM lyrics_cache
                    ORDER BY last_played DESC
                    LIMIT -1 OFFSET ?
                )
                """;

        try (PreparedStatement statement = connection.prepareStatement(pruneSql)) {
            statement.setInt(1, MAX_CACHE_ROWS);
            statement.executeUpdate();
        } catch (Exception e) {
            System.err.println("Lyrics cache prune failed: " + e.getMessage());
        }
    }

    private List<LyricsLine> parseLyrics(String json) {
        try {
            LyricsLine[] parsed = gson.fromJson(json, LyricsLine[].class);
            if (parsed == null || parsed.length == 0) {
                return List.of();
            }
            return Arrays.asList(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}