package net.iozamudio;

import javafx.application.Application;
import javafx.stage.Stage;

import net.iozamudio.application.port.in.MediaControlUseCase;
import net.iozamudio.application.port.in.MediaPollingUseCase;
import net.iozamudio.application.port.in.LyricsUseCase;
import net.iozamudio.application.port.out.MediaControlPort;
import net.iozamudio.application.port.out.MediaInfoProviderPort;
import net.iozamudio.application.port.out.LyricsProviderPort;
import net.iozamudio.application.service.LyricsService;
import net.iozamudio.application.service.MediaPollingService;
import net.iozamudio.infrastructure.media.DemoMediaInfoProviderAdapter;
import net.iozamudio.infrastructure.media.ScriptMediaInfoProviderAdapter;
import net.iozamudio.infrastructure.media.WindowsMediaControlAdapter;
import net.iozamudio.infrastructure.lyrics.LrcLibLyricsProviderAdapter;
import net.iozamudio.infrastructure.api.LocalApiServer;
import net.iozamudio.ui.VinylPlayerView;
import net.iozamudio.util.SingleInstanceManager;
import net.iozamudio.util.SystemTrayManager;
import net.iozamudio.util.VolumeController;
import net.iozamudio.util.WindowsTaskbarMediaButtons;
import net.iozamudio.util.WindowsStartupRegistrar;

import java.util.List;

public class Main extends Application {

    private static SingleInstanceManager singleInstanceManager;

    private MediaPollingUseCase pollingUseCase;
    private MediaPollingService pollingService;
    private VinylPlayerView view;
    private SystemTrayManager trayManager;
    private LocalApiServer localApiServer;
    private Stage primaryStage;
    private boolean demoMode = false;
    private int fadeInDurationMs = 5000;

    @Override
    public void init() {
        List<String> args = getParameters().getRaw();
        demoMode = args.contains("--demo");
        fadeInDurationMs = resolveFadeInDurationMs(args);
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        MediaInfoProviderPort infoProvider = demoMode
            ? new DemoMediaInfoProviderAdapter()
            : new ScriptMediaInfoProviderAdapter();
        MediaControlPort controlPort = new WindowsMediaControlAdapter();
        LyricsProviderPort lyricsProvider = new LrcLibLyricsProviderAdapter();
        LyricsUseCase lyricsUseCase = new LyricsService(lyricsProvider);

        MediaPollingService pollingService = new MediaPollingService(
            infoProvider,
            controlPort,
            info -> view.updateMediaInfo(info),
            500);

        this.pollingService = pollingService;
        this.pollingUseCase = pollingService;

        MediaControlUseCase mediaControlUseCase = pollingService;

        localApiServer = new LocalApiServer(
            pollingService::getLatestInfo,
            mediaControlUseCase);
        localApiServer.start();

        this.view = new VinylPlayerView(mediaControlUseCase, lyricsUseCase, this::shutdownPolling);
        view.show(stage);

        WindowsStartupRegistrar.ensureCurrentExecutableStartsWithWindows();

        trayManager = new SystemTrayManager();
        trayManager.install(stage, this::restoreMainWindow);

        if (singleInstanceManager != null) {
            singleInstanceManager.startListening(() -> javafx.application.Platform.runLater(this::restoreMainWindow));
        }

        VolumeController.fadeInFromZeroToSystemVolume(fadeInDurationMs);
        pollingService.start();
        pollingService.attemptAutoPlayIfStopped(3, 700);
    }

    private void shutdownPolling() {
        if (pollingService != null) {
            pollingService.autoPauseIfPlaying();
        }

        if (pollingUseCase != null) {
            pollingUseCase.shutdown();
        }
    }

    @Override
    public void stop() {
        WindowsTaskbarMediaButtons.uninstallBestEffort();
        if (singleInstanceManager != null) {
            singleInstanceManager.close();
            singleInstanceManager = null;
        }
        if (trayManager != null) {
            trayManager.remove();
        }
        if (localApiServer != null) {
            localApiServer.stop();
            localApiServer = null;
        }
        shutdownPolling();
    }

    public static void main(String[] args) {
        singleInstanceManager = SingleInstanceManager.tryAcquirePrimary();
        if (singleInstanceManager == null) {
            SingleInstanceManager.signalExistingInstanceToShow();
            return;
        }

        launch(args);
    }

    private void restoreMainWindow() {
        if (view != null) {
            view.restoreFromTray();
            return;
        }

        if (primaryStage != null) {
            if (primaryStage.isIconified()) {
                primaryStage.setIconified(false);
            }
            if (!primaryStage.isShowing()) {
                primaryStage.show();
            }
            primaryStage.toFront();
            primaryStage.requestFocus();
        }
    }

    private int resolveFadeInDurationMs(List<String> args) {
        String property = System.getProperty("vinil.fade.ms");
        if (property != null && !property.isBlank()) {
            try {
                return sanitizeFadeInMs(Integer.parseInt(property));
            } catch (NumberFormatException ignored) {
            }
        }

        for (String arg : args) {
            if (arg.startsWith("--fade-ms=")) {
                String value = arg.substring("--fade-ms=".length());
                try {
                    return sanitizeFadeInMs(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    return 5000;
                }
            }
        }

        return 5000;
    }

    private int sanitizeFadeInMs(int value) {
        return Math.max(100, value);
    }
}
