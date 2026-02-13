package net.iozamudio.application.service;

import net.iozamudio.application.port.in.LyricsUseCase;
import net.iozamudio.application.port.out.LyricsProviderPort;
import net.iozamudio.model.LyricsLine;

import java.util.List;

public class LyricsService implements LyricsUseCase {
    private final LyricsProviderPort lyricsProvider;

    public LyricsService(LyricsProviderPort lyricsProvider) {
        this.lyricsProvider = lyricsProvider;
    }

    @Override
    public List<LyricsLine> getSyncedLyrics(String artist, String title) {
        if (artist == null || artist.isBlank() || title == null || title.isBlank()) {
            return List.of();
        }

        return lyricsProvider.fetchSyncedLyrics(artist, title);
    }
}