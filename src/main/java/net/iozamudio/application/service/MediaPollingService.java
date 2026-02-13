package net.iozamudio.application.service;

import net.iozamudio.application.port.in.MediaControlUseCase;
import net.iozamudio.application.port.in.MediaPollingUseCase;
import net.iozamudio.application.port.out.MediaControlPort;
import net.iozamudio.application.port.out.MediaInfoProviderPort;
import net.iozamudio.application.port.out.MediaInfoSubscriptionPort;
import net.iozamudio.model.MediaInfo;

import java.util.function.Consumer;

public class MediaPollingService extends Thread implements MediaPollingUseCase, MediaControlUseCase {
    private final MediaInfoProviderPort mediaInfoProvider;
    private final MediaControlPort mediaControl;
    private final Consumer<MediaInfo> onMediaUpdate;
    private final long pollIntervalMs;
    private volatile boolean running = true;
    private volatile MediaInfo latestInfo = new MediaInfo("", "", "STOPPED", "");

    public MediaPollingService(
            MediaInfoProviderPort mediaInfoProvider,
            MediaControlPort mediaControl,
            Consumer<MediaInfo> onMediaUpdate,
            long pollIntervalMs) {
        this.mediaInfoProvider = mediaInfoProvider;
        this.mediaControl = mediaControl;
        this.onMediaUpdate = onMediaUpdate;
        this.pollIntervalMs = pollIntervalMs;
        this.setDaemon(true);
        this.setName("MediaPollingService");
    }

    @Override
    public void run() {
        if (mediaInfoProvider instanceof MediaInfoSubscriptionPort subscriptionPort) {
            runSubscriptionMode(subscriptionPort);
            return;
        }

        runPollingMode();
    }

    private void runSubscriptionMode(MediaInfoSubscriptionPort subscriptionPort) {
        try {
            subscriptionPort.subscribe(this::dispatchMediaInfo);

            while (running) {
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Error subscribing media info: " + e.getMessage());
            onMediaUpdate.accept(new MediaInfo("", "", "STOPPED", ""));
        } finally {
            subscriptionPort.unsubscribe();
        }
    }

    private void runPollingMode() {
        while (running) {
            try {
                MediaInfo info = mediaInfoProvider.getCurrent();
                dispatchMediaInfo(info);
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error fetching media info: " + e.getMessage());
                onMediaUpdate.accept(new MediaInfo("", "", "STOPPED", ""));
            }
        }
    }

    @Override
    public void shutdown() {
        running = false;
        this.interrupt();
    }

    @Override
    public void next() {
        mediaControl.next();
    }

    @Override
    public void previous() {
        mediaControl.previous();
    }

    @Override
    public void playPause() {
        mediaControl.playPause();
    }

    @Override
    public void seekToSeconds(double seconds) {
        mediaControl.seekToSeconds(seconds);
    }

    @Override
    public void openCurrentInBrowser() {
        mediaControl.openCurrentInBrowser();
    }

    private void dispatchMediaInfo(MediaInfo info) {
        latestInfo = info;
        onMediaUpdate.accept(info);
    }

    public void attemptAutoPlayIfStopped(int retries, long waitBetweenAttemptsMs) {
        Thread autoPlayThread = new Thread(() -> {
            for (int i = 0; i < retries && running; i++) {
                try {
                    Thread.sleep(waitBetweenAttemptsMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (!running) {
                    return;
                }

                MediaInfo current = latestInfo;
                if (current != null && current.isPlaying()) {
                    return;
                }

                try {
                    mediaControl.playPause();
                    System.out.println("Startup autoplay attempted");
                } catch (Exception e) {
                    System.err.println("Startup autoplay failed: " + e.getMessage());
                }
            }
        }, "StartupAutoPlay");

        autoPlayThread.setDaemon(true);
        autoPlayThread.start();
    }

    public void autoPauseIfPlaying() {
        MediaInfo current = latestInfo;
        if (current == null || !current.isPlaying()) {
            return;
        }

        try {
            mediaControl.playPause();
            System.out.println("Shutdown autopause executed");
        } catch (Exception e) {
            System.err.println("Shutdown autopause failed: " + e.getMessage());
        }
    }
}