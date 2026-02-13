package net.iozamudio.ui;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.effect.GaussianBlur;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Circle;
import javafx.scene.input.MouseButton;
import javafx.scene.text.Text;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import net.iozamudio.application.port.in.LyricsUseCase;
import net.iozamudio.application.port.in.MediaControlUseCase;
import net.iozamudio.model.LyricsLine;
import net.iozamudio.model.MediaInfo;
import net.iozamudio.util.WindowsTaskbarMediaButtons;
import net.iozamudio.util.WindowsTaskbarUtils;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VinylPlayerView {
    private static final double MARQUEE_PIXELS_PER_SECOND = 18.0;
    private static final double MARQUEE_LOOP_GAP_PX = 8.0;
    private static final String MAIN_WINDOW_TITLE = "vinilplayer";
    private static final double POSITION_SYNC_TOLERANCE_SECONDS = 0.20;
    private static final double SEEK_BACK_THRESHOLD_SECONDS = 2.00;
    private static final double SEEK_SCROLL_REFRESH_SECONDS = 1.50;
    private static final Color DEFAULT_ACCENT_COLOR = Color.rgb(0, 255, 166);
    private static final Color YOUTUBE_ACCENT_COLOR = Color.rgb(255, 0, 0);
    private static final Color SPOTIFY_ACCENT_COLOR = Color.rgb(29, 185, 84);
    private static final Color APPLE_MUSIC_ACCENT_COLOR = Color.rgb(250, 67, 120);
    private static final Color AMAZON_MUSIC_ACCENT_COLOR = Color.rgb(0, 194, 255);

    private final MediaControlUseCase mediaControlUseCase;
    private final LyricsUseCase lyricsUseCase;
    private final Runnable onClose;

    private Label mediaLabel;
    private Label mediaLabelLoop;
    private HBox mediaTickerTrack;
    private StackPane mediaViewport;
    private Label statusLabel;
    private Label currentTimeLabel;
    private Label totalTimeLabel;
    private ProgressBar progressBar;
    private ImageView blurredBackground;
    private Circle vinylCoverCircle;
    private RotateTransition vinylSpin;
    private Animation marqueeAnimation;

    private boolean isPlaying = false;
    private Timeline progressTimeline;
    private double currentProgress = 0;
    private double reportedProgress = 0;
    private double durationSeconds = 0;
    private double currentPositionSeconds = 0;
    private Color currentAccentColor = DEFAULT_ACCENT_COLOR;

    private Stage mainStage;
    private Button playPauseButton;
    private Button lyricsToggleButton;
    private HBox transportControlsBox;
    private HBox serviceButtonsBox;
    private LyricsWidgetView lyricsWidgetView;

    private String currentTrackKey = "";
    private String currentMediaBaseText = "— —";
    private List<LyricsLine> currentLyrics = List.of();
    private int highlightedLyricIndex = -1;
    private double lastReportedPositionSeconds = -1;
    private boolean allowBackwardLyricHighlight = false;
    private boolean forceLyricScrollRefresh = false;

    private final ExecutorService lyricsExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsFetcher");
        thread.setDaemon(true);
        return thread;
    });

    private double xOffset;
    private double yOffset;

    public VinylPlayerView(MediaControlUseCase mediaControlUseCase, LyricsUseCase lyricsUseCase, Runnable onClose) {
        this.mediaControlUseCase = mediaControlUseCase;
        this.lyricsUseCase = lyricsUseCase;
        this.onClose = onClose;
    }

    public void show(Stage stage) {
        this.mainStage = stage;

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(MAIN_WINDOW_TITLE);
        stage.getIcons().setAll(createVinylAppIcon());

        StackPane root = createMainPanel(stage);
        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/vinyl.css").toExternalForm());

        stage.setScene(scene);
        stage.show();
        WindowsTaskbarUtils.showInTaskbarBestEffort(MAIN_WINDOW_TITLE);
        applyAccentColor(currentAccentColor);

        WindowsTaskbarMediaButtons.installBestEffort(
            MAIN_WINDOW_TITLE,
            mediaControlUseCase::openCurrentInBrowser,
            mediaControlUseCase::previous,
            mediaControlUseCase::playPause,
            mediaControlUseCase::next);

        setupLyricsWidget();
        ensureProgressAnimationRunning();
    }

    private Image createVinylAppIcon() {
        int size = 64;
        WritableImage image = new WritableImage(size, size);
        PixelWriter writer = image.getPixelWriter();

        double center = (size - 1) / 2.0;
        double maxRadius = 30.0;
        double labelRadius = 11.0;
        double holeRadius = 3.0;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x - center;
                double dy = y - center;
                double distance = Math.sqrt(dx * dx + dy * dy);

                Color pixel;
                if (distance > maxRadius) {
                    pixel = Color.TRANSPARENT;
                } else if (distance <= holeRadius) {
                    pixel = Color.rgb(8, 8, 8);
                } else if (distance <= labelRadius) {
                    pixel = Color.rgb(0, 225, 150);
                } else {
                    double groove = (Math.sin(distance * 2.6) + 1.0) * 0.5;
                    int shade = (int) Math.round(14 + groove * 18);
                    pixel = Color.rgb(shade, shade, shade);
                }

                writer.setColor(x, y, pixel);
            }
        }

        return image;
    }

    public void restoreFromTray() {
        if (mainStage == null) {
            return;
        }

        if (mainStage.isIconified()) {
            mainStage.setIconified(false);
        }

        if (!mainStage.isShowing()) {
            mainStage.show();
        }

        mainStage.toFront();
        mainStage.requestFocus();

        boolean lyricsWasOpen = lyricsWidgetView != null && lyricsWidgetView.isShowing();

        if (lyricsWasOpen && lyricsWidgetView != null) {
            lyricsWidgetView.show(mainStage);
        }

        updateLyricsToggleButtonState();
    }

    public void updateMediaInfo(MediaInfo info) {
        Platform.runLater(() -> {
            if (info.isEmpty()) {
                currentMediaBaseText = "— —";
                mediaLabel.setText("— —");
                if (mediaLabelLoop != null) {
                    mediaLabelLoop.setText("— —");
                }
                stopMarquee();
                statusLabel.setText("🎵 Esperando...");
                progressBar.setProgress(0);
                currentPositionSeconds = 0;
                durationSeconds = 0;
                updateProgressTimeLabel();
                setVinylPlaying(false);
                updateThumbnail(null);
                updateControlsVisibility(true);
                clearLyricsForNoTrack();
                return;
            }

            String text = info.getTitle() + " — " + info.getArtist();
            if (!text.equals(currentMediaBaseText)) {
                currentMediaBaseText = text;
                mediaLabel.setText(text);
                if (mediaLabelLoop != null) {
                    mediaLabelLoop.setText(text);
                }
                startMarqueeIfNeeded();
            }

            isPlaying = info.isPlaying();
            WindowsTaskbarMediaButtons.setPlaybackStateBestEffort(isPlaying);
            setVinylPlaying(isPlaying);

            durationSeconds = info.getDuration();
            reportedProgress = info.getProgress();
            syncCurrentPosition(info.getPosition(), isPlaying);

            if (!isPlaying) {
                currentProgress = reportedProgress;
                progressBar.setProgress(currentProgress);
            }

            updateControlsVisibility(false);

            updateProgressTimeLabel();

            statusLabel.setText(isPlaying ? "▶ Reproduciendo" : "⏸ Pausado");

            updateThumbnail(info.getThumbnail());
            ensureLyricsForTrack(info.getArtist(), info.getTitle());
            updateLyricsHighlight();
        });
    }

    private StackPane createMainPanel(Stage stage) {
        StackPane root = new StackPane();
        root.getStyleClass().add("vinyl-shell");

        blurredBackground = new ImageView();
        blurredBackground.getStyleClass().add("vinyl-bg-image");
        blurredBackground.setPreserveRatio(false);
        blurredBackground.setSmooth(true);
        blurredBackground.setOpacity(0.52);
        blurredBackground.setEffect(new GaussianBlur(22));
        blurredBackground.fitWidthProperty().bind(root.widthProperty());
        blurredBackground.fitHeightProperty().bind(root.heightProperty());

        VBox container = new VBox(8);
        container.getStyleClass().add("vinyl-container");
        container.setPadding(new Insets(16));

        lyricsToggleButton = new Button("[>]");
        lyricsToggleButton.getStyleClass().add("close-button");
        lyricsToggleButton.setMinWidth(Region.USE_PREF_SIZE);
        lyricsToggleButton.setMaxWidth(Region.USE_PREF_SIZE);
        lyricsToggleButton.setOnAction(e -> toggleLyricsWidget());

        Button minimize = new Button("—");
        minimize.getStyleClass().add("close-button");
        minimize.setMinWidth(Region.USE_PREF_SIZE);
        minimize.setMaxWidth(Region.USE_PREF_SIZE);
        minimize.setOnAction(e -> stage.setIconified(true));

        Button close = new Button("✕");
        close.getStyleClass().add("close-button");
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.setMaxWidth(Region.USE_PREF_SIZE);
        close.setOnAction(e -> {
            if (lyricsWidgetView != null) {
                lyricsWidgetView.close();
            }
            WindowsTaskbarMediaButtons.uninstallBestEffort();
            lyricsExecutor.shutdownNow();
            onClose.run();
            stage.close();
        });

        statusLabel = new Label("🎵 Esperando...");
        statusLabel.getStyleClass().add("status-label");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        mediaLabel = new Label("— —");
        mediaLabel.getStyleClass().add("media-label");
        mediaLabel.setMinWidth(Region.USE_PREF_SIZE);
        mediaLabel.setPrefWidth(Region.USE_COMPUTED_SIZE);
        mediaLabel.setMaxWidth(Region.USE_PREF_SIZE);
        mediaLabel.setWrapText(false);
        mediaLabel.setTextOverrun(OverrunStyle.CLIP);

        mediaLabelLoop = new Label("— —");
        mediaLabelLoop.getStyleClass().add("media-label");
        mediaLabelLoop.setMinWidth(Region.USE_PREF_SIZE);
        mediaLabelLoop.setPrefWidth(Region.USE_COMPUTED_SIZE);
        mediaLabelLoop.setMaxWidth(Region.USE_PREF_SIZE);
        mediaLabelLoop.setWrapText(false);
        mediaLabelLoop.setTextOverrun(OverrunStyle.CLIP);
        mediaLabelLoop.setVisible(false);
        mediaLabelLoop.setManaged(false);

        mediaTickerTrack = new HBox(MARQUEE_LOOP_GAP_PX, mediaLabel, mediaLabelLoop);
        mediaTickerTrack.setAlignment(Pos.CENTER_LEFT);

        mediaViewport = new StackPane(mediaTickerTrack);
        mediaViewport.setPrefWidth(Region.USE_COMPUTED_SIZE);
        mediaViewport.setMinWidth(0);
        mediaViewport.setMaxWidth(Double.MAX_VALUE);
        mediaViewport.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(mediaTickerTrack, Pos.CENTER_LEFT);
        mediaViewport.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            if (Math.abs(newWidth.doubleValue() - oldWidth.doubleValue()) < 0.5) {
                return;
            }

            Platform.runLater(this::startMarqueeIfNeeded);
        });

        Rectangle mediaClip = new Rectangle();
        mediaClip.widthProperty().bind(mediaViewport.widthProperty());
        mediaClip.heightProperty().bind(mediaViewport.heightProperty());
        mediaViewport.setClip(mediaClip);

        HBox header = new HBox(8, mediaViewport, lyricsToggleButton, minimize, close);
        HBox.setHgrow(mediaViewport, Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);

        StackPane vinyl = createVinyl();
        HBox artRow = new HBox(vinyl);
        artRow.setAlignment(Pos.CENTER);
        VBox.setMargin(artRow, new Insets(0, 0, 2, 0));

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(340);
        progressBar.setPrefHeight(8);
        progressBar.setCursor(Cursor.HAND);
        progressBar.setOnMouseClicked(event -> {
            if (durationSeconds <= 0) {
                return;
            }

            double width = progressBar.getWidth();
            if (width <= 0) {
                return;
            }

            double clickedRatio = Math.max(0, Math.min(1, event.getX() / width));
            requestSeekToSeconds(clickedRatio * durationSeconds);
        });

        currentTimeLabel = new Label("0:00");
        currentTimeLabel.getStyleClass().add("status-label");
        currentTimeLabel.getStyleClass().add("progress-time-label");
        currentTimeLabel.setMinWidth(Region.USE_PREF_SIZE);

        totalTimeLabel = new Label("0:00");
        totalTimeLabel.getStyleClass().add("status-label");
        totalTimeLabel.getStyleClass().add("progress-time-label");
        totalTimeLabel.setMinWidth(Region.USE_PREF_SIZE);

        HBox progressRow = new HBox(6, currentTimeLabel, progressBar, totalTimeLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        StackPane controls = createControlsSection();

        container.getChildren().addAll(header, artRow, progressRow, controls);

        root.getChildren().addAll(blurredBackground, container);

        Rectangle shellClip = new Rectangle();
        shellClip.widthProperty().bind(root.widthProperty());
        shellClip.heightProperty().bind(root.heightProperty());
        shellClip.setArcWidth(36);
        shellClip.setArcHeight(36);
        root.setClip(shellClip);

        root.setOnMousePressed(e -> {
            xOffset = e.getSceneX();
            yOffset = e.getSceneY();
        });

        root.setOnMouseDragged(e -> {
            mainStage.setX(e.getScreenX() - xOffset);
            mainStage.setY(e.getScreenY() - yOffset);
            if (lyricsWidgetView != null) {
                lyricsWidgetView.positionRightOf(mainStage);
            }
        });

        return root;
    }

    private void setupLyricsWidget() {
        lyricsWidgetView = new LyricsWidgetView(this::onLyricsWidgetHidden, this::requestSeekToSeconds);
        lyricsWidgetView.init();
        lyricsWidgetView.setAccentColor(currentAccentColor);

        ChangeListener<Number> reposition = (obs, oldVal, newVal) -> lyricsWidgetView.positionRightOf(mainStage);
        mainStage.xProperty().addListener(reposition);
        mainStage.yProperty().addListener(reposition);
        mainStage.widthProperty().addListener(reposition);

        mainStage.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
            if (isIconified) {
                lyricsWidgetView.hide();
            }
        });

        lyricsWidgetView.showNoTrack();
        updateLyricsToggleButtonState();
    }

    private void toggleLyricsWidget() {
        if (lyricsWidgetView == null) {
            return;
        }

        if (lyricsWidgetView.isShowing()) {
            lyricsWidgetView.hide();
            return;
        }

        lyricsWidgetView.show(mainStage);
        updateLyricsToggleButtonState();

        lyricsWidgetView.goToLineAnchor(highlightedLyricIndex, true);
    }

    private void onLyricsWidgetHidden() {
        updateLyricsToggleButtonState();
    }

    private void updateLyricsToggleButtonState() {
        if (lyricsToggleButton == null) {
            return;
        }

        boolean showing = lyricsWidgetView != null && lyricsWidgetView.isShowing();
        lyricsToggleButton.setText(showing ? "[X]" : "[>]");
        lyricsToggleButton.setTooltip(new Tooltip(showing ? "Ocultar lyrics" : "Mostrar lyrics"));
    }

    private StackPane createVinyl() {
        StackPane host = new StackPane();
        host.setPrefSize(294, 294);

        double r = 114;

        Circle outerRing = new Circle(r + 4);
        outerRing.setStroke(Color.rgb(80, 80, 80));
        outerRing.setFill(Color.TRANSPARENT);

        Circle disc = new Circle(r);
        disc.setFill(new RadialGradient(
                0, 0,
                -10, -10,
                r,
                false,
                CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(55, 55, 55)),
                new Stop(.3, Color.rgb(20, 20, 20)),
                new Stop(.8, Color.rgb(6, 6, 6)),
                new Stop(1, Color.BLACK)));

        Group grooves = new Group();
        for (int i = 0; i < 9; i++) {
            Circle g = new Circle(r - 6 - i * 3);
            g.setStroke(Color.rgb(35, 35, 35, 0.35));
            g.setFill(Color.TRANSPARENT);
            grooves.getChildren().add(g);
        }

        Arc shineArc = new Arc(0, 0, r * 0.92, r * 0.92, 214, 62);
        shineArc.setType(ArcType.OPEN);
        shineArc.setStroke(Color.rgb(255, 255, 255, 0.30));
        shineArc.setStrokeWidth(2.2);
        shineArc.setFill(Color.TRANSPARENT);

        Arc shineArcSoft = new Arc(0, 0, r * 0.78, r * 0.78, 232, 48);
        shineArcSoft.setType(ArcType.OPEN);
        shineArcSoft.setStroke(Color.rgb(255, 255, 255, 0.14));
        shineArcSoft.setStrokeWidth(1.6);
        shineArcSoft.setFill(Color.TRANSPARENT);

        Circle label = new Circle(r * .46, Color.web("#1db954"));
        vinylCoverCircle = new Circle(r * .44, Color.web("#1db954"));
        vinylCoverCircle.setStroke(Color.rgb(255, 255, 255, 0.20));
        vinylCoverCircle.setStrokeWidth(1.2);
        Circle hole = new Circle(r * .06, Color.BLACK);

        Group discGroup = new Group(outerRing, disc, grooves, shineArc, shineArcSoft, label, vinylCoverCircle, hole);

        vinylSpin = new RotateTransition(Duration.seconds(4.8), discGroup);
        vinylSpin.setByAngle(360);
        vinylSpin.setInterpolator(Interpolator.LINEAR);
        vinylSpin.setCycleCount(Animation.INDEFINITE);

        host.getChildren().add(discGroup);
        host.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() >= 2) {
                mediaControlUseCase.openCurrentInBrowser();
            }
        });
        return host;
    }

    private StackPane createControlsSection() {
        transportControlsBox = createControls();
        serviceButtonsBox = createServiceButtons();

        StackPane stack = new StackPane(transportControlsBox, serviceButtonsBox);
        updateControlsVisibility(true);
        return stack;
    }

    private HBox createControls() {
        Button prev = new Button("⏮");
        Button play = new Button("▶");
        Button next = new Button("⏭");

        prev.getStyleClass().add("transport-button");
        play.getStyleClass().add("transport-button");
        next.getStyleClass().add("transport-button");
        playPauseButton = play;
        updatePlayPauseButtonStyle();

        prev.setOnAction(e -> mediaControlUseCase.previous());
        play.setOnAction(e -> mediaControlUseCase.playPause());
        next.setOnAction(e -> mediaControlUseCase.next());

        HBox box = new HBox(12, prev, play, next);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private HBox createServiceButtons() {
        Button ytMusic = createServiceIconButton("▶", "Reproducir en YouTube Music", YOUTUBE_ACCENT_COLOR);
        Button spotify = createServiceIconButton("♫", "Reproducir en Spotify", SPOTIFY_ACCENT_COLOR);
        Button appleMusic = createServiceIconButton("♪", "Reproducir en Apple Music", APPLE_MUSIC_ACCENT_COLOR);
        Button amazonMusic = createServiceIconButton("a", "Reproducir en Amazon Music", AMAZON_MUSIC_ACCENT_COLOR);

        ytMusic.setOnAction(e -> openMusicService(
            YOUTUBE_ACCENT_COLOR,
                new String[] {"youtubemusic://"},
                "https://music.youtube.com"));
        spotify.setOnAction(e -> openMusicService(
            SPOTIFY_ACCENT_COLOR,
                new String[] {"spotify:"},
                "https://open.spotify.com"));
        appleMusic.setOnAction(e -> openMusicService(
            APPLE_MUSIC_ACCENT_COLOR,
                new String[] {"applemusic://", "music://"},
                "https://music.apple.com"));
        amazonMusic.setOnAction(e -> openMusicService(
            AMAZON_MUSIC_ACCENT_COLOR,
                new String[] {"amazonmusic://"},
                "https://music.amazon.com"));

        HBox box = new HBox(8, ytMusic, spotify, appleMusic, amazonMusic);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Button createServiceIconButton(String glyph, String tooltip, Color accentColor) {
        Label iconLabel = new Label(glyph);
        iconLabel.getStyleClass().add("service-icon-label");
        iconLabel.setTextFill(Color.WHITE);
        iconLabel.setFont(Font.font(14));

        Circle bg = new Circle(13, accentColor);
        bg.setOpacity(0.92);

        StackPane icon = new StackPane(bg, iconLabel);

        Button button = new Button();
        button.getStyleClass().addAll("control-button", "service-button");
        button.setGraphic(icon);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private void updateControlsVisibility(boolean noPlayback) {
        if (transportControlsBox == null || serviceButtonsBox == null) {
            return;
        }

        transportControlsBox.setVisible(!noPlayback);
        transportControlsBox.setManaged(!noPlayback);

        serviceButtonsBox.setVisible(noPlayback);
        serviceButtonsBox.setManaged(noPlayback);
    }

    private void openMusicService(Color accentColor, String[] appUris, String webUrl) {
        applyAccentColor(accentColor);

        for (String appUri : appUris) {
            if (isUriSchemeRegistered(appUri) && tryLaunchAppUri(appUri)) {
                return;
            }
        }

        tryOpenUri(webUrl);
    }

    private boolean isUriSchemeRegistered(String uriText) {
        String scheme = extractUriScheme(uriText);
        if (scheme == null || scheme.isBlank()) {
            return false;
        }

        if (!isWindows()) {
            return true;
        }

        return hasWindowsProtocolHandler(scheme);
    }

    private String extractUriScheme(String uriText) {
        if (uriText == null || uriText.isBlank()) {
            return null;
        }

        int separator = uriText.indexOf(':');
        if (separator <= 0) {
            return null;
        }

        return uriText.substring(0, separator).toLowerCase(Locale.ROOT);
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean hasWindowsProtocolHandler(String scheme) {
        if (queryRegistryKey("HKEY_CLASSES_ROOT\\" + scheme + "\\shell\\open\\command")) {
            return true;
        }
        return queryRegistryValue("HKEY_CLASSES_ROOT\\" + scheme, "URL Protocol");
    }

    private boolean queryRegistryKey(String key) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "reg",
                    "query",
                    key)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private boolean queryRegistryValue(String key, String valueName) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "reg",
                    "query",
                    key,
                    "/v",
                    valueName)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private boolean tryLaunchAppUri(String uriText) {
        if (isWindows()) {
            Process process = null;
            try {
                process = new ProcessBuilder("cmd", "/c", "start", "", uriText)
                        .redirectErrorStream(true)
                        .start();
                if (process.waitFor() == 0) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }

        return tryOpenUri(uriText);
    }

    private boolean tryOpenUri(String uriText) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }

            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }

            desktop.browse(URI.create(uriText));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applyAccentColor(Color accentColor) {
        if (accentColor == null) {
            accentColor = DEFAULT_ACCENT_COLOR;
        }

        currentAccentColor = accentColor;

        String cssColor = toCssRgba(accentColor);
        if (progressBar != null) {
            progressBar.setStyle("-fx-accent: " + cssColor + ";");
        }

        if (lyricsWidgetView != null) {
            lyricsWidgetView.setAccentColor(accentColor);
        }

        updatePlayPauseButtonStyle();
    }

    private String toCssRgba(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return "rgba(" + r + "," + g + "," + b + ",0.92)";
    }

    private void ensureProgressAnimationRunning() {
        if (progressTimeline != null) {
            return;
        }

        progressTimeline = new Timeline(
                new KeyFrame(Duration.millis(50), e -> {
                    if (!isPlaying) {
                        return;
                    }

                    currentPositionSeconds += 0.05;

                    double targetProgress = reportedProgress;
                    if (durationSeconds > 0) {
                        targetProgress = Math.min(1.0, Math.max(reportedProgress, currentPositionSeconds / durationSeconds));
                    }

                    if (Math.abs(targetProgress - currentProgress) > 0.08) {
                        currentProgress = targetProgress;
                    } else {
                        currentProgress += (targetProgress - currentProgress) * 0.22;
                    }

                    currentProgress = Math.max(0, Math.min(1, currentProgress));
                    progressBar.setProgress(currentProgress);
                    updateProgressTimeLabel();

                    updateLyricsHighlight();
                }));

        progressTimeline.setCycleCount(Animation.INDEFINITE);
        progressTimeline.play();
    }

    private void setVinylPlaying(boolean playing) {
        if (playing) {
            vinylSpin.play();
        } else {
            vinylSpin.pause();
        }

        updatePlayPauseButtonStyle();
    }

    private void updatePlayPauseButtonStyle() {
        if (playPauseButton == null) {
            return;
        }

        if (isPlaying) {
            if (!playPauseButton.getStyleClass().contains("transport-button-primary")) {
                playPauseButton.getStyleClass().add("transport-button-primary");
            }
            playPauseButton.setStyle("-fx-background-color: " + toCssRgba(currentAccentColor) + "; -fx-text-fill: rgba(0,0,0,0.92);");
        } else {
            playPauseButton.getStyleClass().remove("transport-button-primary");
            playPauseButton.setStyle("");
        }
    }

    private void updateThumbnail(String base64) {
        if (lyricsWidgetView != null) {
            lyricsWidgetView.updateBackgroundThumbnail(base64);
        }

        if (base64 == null || base64.isEmpty()) {
            if (blurredBackground != null) {
                blurredBackground.setImage(null);
            }
            if (vinylCoverCircle != null) {
                vinylCoverCircle.setFill(Color.web("#1db954"));
            }
            return;
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (blurredBackground != null) {
                blurredBackground.setImage(image);
            }
            if (vinylCoverCircle != null) {
                vinylCoverCircle.setFill(new ImagePattern(image));
            }
        } catch (Exception ignored) {
            if (blurredBackground != null) {
                blurredBackground.setImage(null);
            }
            if (vinylCoverCircle != null) {
                vinylCoverCircle.setFill(Color.web("#1db954"));
            }
        }
    }

    private void startMarqueeIfNeeded() {
        stopMarquee();

        if (mediaTickerTrack == null || mediaLabelLoop == null) {
            return;
        }

        String baseText = (currentMediaBaseText == null || currentMediaBaseText.isBlank())
            ? "— —"
            : currentMediaBaseText;

        mediaLabel.setText(baseText);
        mediaLabelLoop.setText(baseText);

        Text temp = new Text(baseText);
        temp.setFont(mediaLabel.getFont());
        double textWidth = temp.getLayoutBounds().getWidth();
        double containerWidth = mediaViewport != null ? mediaViewport.getWidth() : 0;
        if (containerWidth <= 1 && mediaViewport != null) {
            containerWidth = mediaViewport.getPrefWidth();
        }

        if (containerWidth <= 1) {
            Platform.runLater(this::startMarqueeIfNeeded);
            return;
        }

        double overflowWidth = textWidth - containerWidth;
        if (overflowWidth <= 1) {
            mediaLabelLoop.setVisible(false);
            mediaLabelLoop.setManaged(false);
            return;
        }

        String marqueeText = baseText + " —";
        mediaLabel.setText(marqueeText);
        mediaLabelLoop.setText(marqueeText);
        mediaLabelLoop.setVisible(true);
        mediaLabelLoop.setManaged(true);

        Text marqueeMeasure = new Text(marqueeText);
        marqueeMeasure.setFont(mediaLabel.getFont());
        double marqueeTextWidth = marqueeMeasure.getLayoutBounds().getWidth();

        double cycleDistance = marqueeTextWidth + MARQUEE_LOOP_GAP_PX;
        Duration travelDuration = Duration.seconds(cycleDistance / MARQUEE_PIXELS_PER_SECOND);

        TranslateTransition continuous = new TranslateTransition(travelDuration, mediaTickerTrack);
        continuous.setFromX(0);
        continuous.setToX(-cycleDistance);
        continuous.setInterpolator(Interpolator.LINEAR);
        continuous.setCycleCount(Animation.INDEFINITE);

        marqueeAnimation = continuous;
        marqueeAnimation.play();
    }

    private void stopMarquee() {
        if (marqueeAnimation != null) {
            marqueeAnimation.stop();
        }
        if (mediaTickerTrack != null) {
            mediaTickerTrack.setTranslateX(0);
        }
    }

    private void ensureLyricsForTrack(String artist, String title) {
        String trackKey = (artist + "::" + title).trim();
        if (trackKey.equals(currentTrackKey)) {
            return;
        }

        currentTrackKey = trackKey;
        currentLyrics = List.of();
        highlightedLyricIndex = -1;
        currentPositionSeconds = 0;
        lastReportedPositionSeconds = -1;
        allowBackwardLyricHighlight = false;
        forceLyricScrollRefresh = false;

        if (lyricsWidgetView != null) {
            lyricsWidgetView.showLoading();
        }

        lyricsExecutor.submit(() -> {
            List<LyricsLine> lyrics = lyricsUseCase.getSyncedLyrics(artist, title);

            Platform.runLater(() -> {
                String activeTrack = (artist + "::" + title).trim();
                if (!Objects.equals(currentTrackKey, activeTrack)) {
                    return;
                }

                currentLyrics = lyrics;
                if (lyricsWidgetView != null) {
                    lyricsWidgetView.setLyrics(currentLyrics);
                }
                updateLyricsHighlight();
            });
        });
    }

    private void clearLyricsForNoTrack() {
        currentTrackKey = "";
        currentLyrics = List.of();
        highlightedLyricIndex = -1;
        currentPositionSeconds = 0;
        lastReportedPositionSeconds = -1;
        allowBackwardLyricHighlight = false;
        forceLyricScrollRefresh = false;

        if (lyricsWidgetView != null) {
            lyricsWidgetView.showNoTrack();
        }
    }

    private void updateLyricsHighlight() {
        if (currentLyrics.isEmpty()) {
            return;
        }

        int indexToHighlight = -1;
        for (int i = 0; i < currentLyrics.size(); i++) {
            if (currentPositionSeconds >= currentLyrics.get(i).timeSeconds()) {
                indexToHighlight = i;
            } else {
                break;
            }
        }

        if (isPlaying && highlightedLyricIndex >= 0 && indexToHighlight < highlightedLyricIndex && !allowBackwardLyricHighlight) {
            return;
        }

        allowBackwardLyricHighlight = false;

        if (indexToHighlight == highlightedLyricIndex) {
            if (forceLyricScrollRefresh && lyricsWidgetView != null) {
                lyricsWidgetView.setActiveLine(highlightedLyricIndex, true);
            }
            forceLyricScrollRefresh = false;
            return;
        }

        highlightedLyricIndex = indexToHighlight;

        if (lyricsWidgetView != null) {
            lyricsWidgetView.setActiveLine(highlightedLyricIndex, true);
        }

        forceLyricScrollRefresh = false;
    }

    private void syncCurrentPosition(double reportedPositionSeconds, boolean playing) {
        double safeReported = Math.max(0, reportedPositionSeconds);

        if (!playing) {
            currentPositionSeconds = safeReported;
            lastReportedPositionSeconds = safeReported;
            return;
        }

        if (safeReported <= 0.01) {
            return;
        }

        if (lastReportedPositionSeconds < 0) {
            currentPositionSeconds = safeReported;
            lastReportedPositionSeconds = safeReported;
            return;
        }

        double delta = safeReported - lastReportedPositionSeconds;
        boolean forwardAdjust = Math.abs(delta) > POSITION_SYNC_TOLERANCE_SECONDS && delta > 0;
        boolean seekBack = delta < -SEEK_BACK_THRESHOLD_SECONDS;
        boolean significantJump = Math.abs(delta) > SEEK_SCROLL_REFRESH_SECONDS;

        if (forwardAdjust || seekBack) {
            currentPositionSeconds = safeReported;
        }

        if (seekBack) {
            allowBackwardLyricHighlight = true;
        }

        if (significantJump) {
            forceLyricScrollRefresh = true;
        }

        lastReportedPositionSeconds = safeReported;
    }

    private void requestSeekToSeconds(double targetSeconds) {
        double clampedSeconds = Math.max(0, targetSeconds);

        mediaControlUseCase.seekToSeconds(clampedSeconds);

        currentPositionSeconds = clampedSeconds;
        if (durationSeconds > 0) {
            double ratio = Math.max(0, Math.min(1, clampedSeconds / durationSeconds));
            reportedProgress = ratio;
            currentProgress = ratio;
            progressBar.setProgress(ratio);
        }

        forceLyricScrollRefresh = true;
        updateProgressTimeLabel();
        updateLyricsHighlight();
    }

    private void updateProgressTimeLabel() {
        if (currentTimeLabel == null || totalTimeLabel == null) {
            return;
        }

        long currentSeconds = Math.max(0, (long) Math.floor(currentPositionSeconds));
        long totalSeconds = Math.max(0, (long) Math.floor(durationSeconds));
        currentTimeLabel.setText(formatTime(currentSeconds));
        totalTimeLabel.setText(formatTime(totalSeconds));
    }

    private String formatTime(long seconds) {
        long mins = seconds / 60;
        long secs = seconds % 60;
        return mins + ":" + String.format("%02d", secs);
    }
}
