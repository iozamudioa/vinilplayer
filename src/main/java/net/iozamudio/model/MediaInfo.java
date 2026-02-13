package net.iozamudio.model;

/**
 * Modelo inmutable que representa la información multimedia actual.
 */
public record MediaInfo(
        String artist,
        String title,
        String status,
        double position,
        double duration,
    String thumbnail) {

    public MediaInfo {
        artist = artist != null ? artist : "";
        title = title != null ? title : "";
        status = status != null ? status : "STOPPED";
        position = Math.max(0, position);
        duration = Math.max(0, duration);
        thumbnail = thumbnail != null ? thumbnail : "";
    }

    public MediaInfo(String artist, String title, String status, String thumbnail) {
        this(artist, title, status, 0, 0, thumbnail);
    }

    public String getArtist() {
        return artist;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public double getPosition() {
        return position;
    }

    public double getDuration() {
        return duration;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public boolean isPlaying() {
        return "PLAYING".equals(status);
    }

    public boolean isEmpty() {
        return artist.isEmpty() && title.isEmpty();
    }

    public double getProgress() {
        if (duration <= 0)
            return 0;
        return Math.min(1.0, position / duration);
    }

    public double getRemaining() {
        return duration - position;
    }

    @Override
    public String toString() {
        return String.format("MediaInfo{artist='%s', title='%s', status='%s', position=%.1f, duration=%.1f}",
            artist, title, status, position, duration);
    }
}
