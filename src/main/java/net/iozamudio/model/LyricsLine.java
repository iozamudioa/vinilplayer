package net.iozamudio.model;

public record LyricsLine(double timeSeconds, String text) {
    public LyricsLine {
        timeSeconds = Math.max(0, timeSeconds);
        text = text != null ? text : "";
    }
}