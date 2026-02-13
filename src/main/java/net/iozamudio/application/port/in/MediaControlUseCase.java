package net.iozamudio.application.port.in;

public interface MediaControlUseCase {
    void next();

    void previous();

    void playPause();

    void seekToSeconds(double seconds);

    void openCurrentInBrowser();
}