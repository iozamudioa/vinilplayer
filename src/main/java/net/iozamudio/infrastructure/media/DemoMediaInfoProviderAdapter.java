package net.iozamudio.infrastructure.media;

import net.iozamudio.application.port.out.MediaInfoProviderPort;
import net.iozamudio.model.MediaInfo;

public class DemoMediaInfoProviderAdapter implements MediaInfoProviderPort {
    private int demoSongIndex = 0;

    private static final String[][] DEMO_SONGS = {
            { "Daft Punk", "Get Lucky" },
            { "The Weeknd", "Blinding Lights" },
            { "Billie Eilish", "bad guy" },
            { "Post Malone", "Circles" },
            { "Tame Impala", "The Less I Know The Better" }
    };

    @Override
    public MediaInfo getCurrent() {
        String[] song = DEMO_SONGS[demoSongIndex];
        demoSongIndex = (demoSongIndex + 1) % DEMO_SONGS.length;
        return new MediaInfo(song[0], song[1], "PLAYING", "");
    }
}