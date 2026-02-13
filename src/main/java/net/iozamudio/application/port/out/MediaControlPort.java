package net.iozamudio.application.port.out;

public interface MediaControlPort {
    void next();

    void previous();

    void playPause();

    void seekToSeconds(double seconds);

    void openCurrentInBrowser();
}