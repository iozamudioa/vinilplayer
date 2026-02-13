package net.iozamudio.application.port.out;

import net.iozamudio.model.LyricsLine;

import java.util.List;

public interface LyricsProviderPort {
    List<LyricsLine> fetchSyncedLyrics(String artist, String title);
}