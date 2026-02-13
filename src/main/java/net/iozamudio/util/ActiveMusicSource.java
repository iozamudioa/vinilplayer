package net.iozamudio.util;

import java.util.Locale;

public final class ActiveMusicSource {
    public static final String DEFAULT = "default";
    public static final String YOUTUBE_MUSIC = "youtube_music";
    public static final String SPOTIFY = "spotify";
    public static final String APPLE_MUSIC = "apple_music";
    public static final String AMAZON_MUSIC = "amazon_music";

    private static volatile String currentSource = DEFAULT;

    private ActiveMusicSource() {
    }

    public static void set(String source) {
        currentSource = normalize(source);
    }

    public static String get() {
        return currentSource;
    }

    private static String normalize(String source) {
        if (source == null || source.isBlank()) {
            return DEFAULT;
        }

        String normalized = source.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case YOUTUBE_MUSIC, SPOTIFY, APPLE_MUSIC, AMAZON_MUSIC -> normalized;
            default -> DEFAULT;
        };
    }
}
