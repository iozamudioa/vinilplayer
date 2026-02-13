package net.iozamudio.application.port.in;

import net.iozamudio.model.LyricsLine;

import java.util.List;

public interface LyricsUseCase {
    List<LyricsLine> getSyncedLyrics(String artist, String title);
}