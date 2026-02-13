package net.iozamudio.ui;

import javafx.application.Platform;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import net.iozamudio.model.LyricsLine;
import net.iozamudio.util.WindowsTaskbarUtils;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.DoubleConsumer;

public class LyricsWidgetView {
    private static final double WIDGET_GAP = 12;
    private static final String LYRICS_WINDOW_TITLE = "VinilPlayerLyricsWidget";
    private static final double MIN_SCROLL_DURATION_MS = 240;
    private static final double MAX_SCROLL_DURATION_MS = 520;
    private static final Color DEFAULT_ACCENT_COLOR = Color.rgb(0, 255, 166);

    private final Runnable onHide;
    private final DoubleConsumer onSeekRequested;

    private Stage stage;
    private ImageView blurredBackground;
    private VBox lyricsLinesBox;
    private ScrollPane lyricsScrollPane;
    private final List<Hyperlink> lyricLineLinks = new ArrayList<>();
    private int activeLineIndex = -1;
    private Timeline scrollAnimation;
    private Color accentColor = DEFAULT_ACCENT_COLOR;

    public LyricsWidgetView(Runnable onHide, DoubleConsumer onSeekRequested) {
        this.onHide = onHide;
        this.onSeekRequested = onSeekRequested;
    }

    public void init() {
        StackPane root = createRoot();

        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/vinyl.css").toExternalForm());

        stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(LYRICS_WINDOW_TITLE);
        stage.setScene(scene);
        stage.setOnHidden(event -> onHide.run());
    }

    public void updateBackgroundThumbnail(String base64) {
        if (blurredBackground == null) {
            return;
        }

        if (base64 == null || base64.isEmpty()) {
            blurredBackground.setImage(null);
            return;
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            blurredBackground.setImage(new Image(new ByteArrayInputStream(bytes)));
        } catch (Exception ignored) {
            blurredBackground.setImage(null);
        }
    }

    public void show(Stage mainStage) {
        if (stage == null) {
            return;
        }

        positionRightOf(mainStage);

        if (!stage.isShowing()) {
            stage.show();
            WindowsTaskbarUtils.hideFromTaskbarBestEffort(LYRICS_WINDOW_TITLE);
        }

        stage.toFront();
        stage.requestFocus();

        Platform.runLater(() -> goToLineAnchor(activeLineIndex, true));
    }

    public void hide() {
        if (stage != null) {
            stage.hide();
        }
    }

    public void close() {
        if (stage != null) {
            stage.close();
        }
    }

    public void positionRightOf(Stage mainStage) {
        if (stage == null || mainStage == null) {
            return;
        }

        stage.setX(mainStage.getX() + mainStage.getWidth() + WIDGET_GAP);
        stage.setY(mainStage.getY());
    }

    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    public void showLoading() {
        setPlaceholder("Buscando lyrics...");
    }

    public void showNoTrack() {
        activeLineIndex = -1;
        setPlaceholder("Sin canción activa");
    }

    public void showNoLyrics() {
        activeLineIndex = -1;
        setPlaceholder("No hay lyrics sincronizadas");
    }

    public void setLyrics(List<LyricsLine> lyrics) {
        lyricsLinesBox.getChildren().clear();
        lyricLineLinks.clear();
        activeLineIndex = -1;

        if (lyrics == null || lyrics.isEmpty()) {
            showNoLyrics();
            return;
        }

        for (int i = 0; i < lyrics.size(); i++) {
            LyricsLine line = lyrics.get(i);
            final int lineIndex = i;

            Hyperlink lineLink = new Hyperlink(line.text());
            lineLink.setId("lyric-line-" + lineIndex);
            lineLink.getStyleClass().add("lyrics-link");
            lineLink.setWrapText(true);
            lineLink.setAlignment(Pos.CENTER);
            lineLink.setTextAlignment(TextAlignment.CENTER);
            lineLink.setMaxWidth(Double.MAX_VALUE);
            lineLink.setOnAction(e -> {
                lineLink.setVisited(false);
                setActiveLine(lineIndex, true);
                if (onSeekRequested != null) {
                    onSeekRequested.accept(line.timeSeconds());
                }
            });

            lyricLineLinks.add(lineLink);
            lyricsLinesBox.getChildren().add(lineLink);
        }
    }

    public void setAccentColor(Color accentColor) {
        if (accentColor == null) {
            this.accentColor = DEFAULT_ACCENT_COLOR;
        } else {
            this.accentColor = accentColor;
        }

        if (activeLineIndex >= 0 && activeLineIndex < lyricLineLinks.size()) {
            Hyperlink activeLink = lyricLineLinks.get(activeLineIndex);
            activeLink.setStyle("-fx-text-fill: " + toCssRgba(this.accentColor) + ";");
        }
    }

    public void setActiveLine(int lineIndex, boolean autoScroll) {
        if (lyricLineLinks.isEmpty()) {
            return;
        }

        int boundedIndex = Math.max(-1, Math.min(lineIndex, lyricLineLinks.size() - 1));
        if (boundedIndex == activeLineIndex) {
            if (autoScroll) {
                if (boundedIndex >= 0) {
                    goToLineAnchor(boundedIndex, true);
                } else {
                    scrollToTop();
                }
            }
            return;
        }

        activeLineIndex = boundedIndex;

        for (int i = 0; i < lyricLineLinks.size(); i++) {
            Hyperlink link = lyricLineLinks.get(i);
            link.getStyleClass().remove("lyrics-link-active");
            link.setStyle("");
            if (i == activeLineIndex) {
                link.getStyleClass().add("lyrics-link-active");
                link.setStyle("-fx-text-fill: " + toCssRgba(accentColor) + ";");
            }
        }

        if (autoScroll) {
            if (activeLineIndex >= 0) {
                goToLineAnchor(activeLineIndex, true);
            } else {
                scrollToTop();
            }
        }
    }

    public void goToLineAnchor(int lineIndex, boolean centered) {
        if (lyricsScrollPane == null || lyricLineLinks.isEmpty() || lineIndex < 0 || lineIndex >= lyricLineLinks.size()) {
            return;
        }

        scrollToLine(lineIndex, centered, 3);
    }

    private void scrollToLine(int lineIndex, boolean centered, int retries) {
        Hyperlink targetLink = lyricLineLinks.get(lineIndex);

        Platform.runLater(() -> {
            double contentHeight = lyricsLinesBox.getHeight();
            double viewportHeight = lyricsScrollPane.getViewportBounds().getHeight();

            if ((contentHeight <= 0 || viewportHeight <= 0) && retries > 0) {
                scrollToLine(lineIndex, centered, retries - 1);
                return;
            }

            if (contentHeight <= viewportHeight || viewportHeight <= 0) {
                lyricsScrollPane.setVvalue(0);
                return;
            }

            Bounds bounds = targetLink.getBoundsInParent();
            double lineCenterY = bounds.getMinY() + (bounds.getHeight() / 2.0);

            double targetY;
            if (centered) {
                targetY = lineCenterY - (viewportHeight * 0.40);
            } else {
                targetY = bounds.getMinY();
            }

            double maxScrollY = contentHeight - viewportHeight;
            double clampedTargetY = Math.max(0, Math.min(maxScrollY, targetY));
            double vValue = clampedTargetY / maxScrollY;

            animateScrollTo(Math.max(0, Math.min(1, vValue)));
        });
    }

    private VBox createPanel() {
        VBox panel = new VBox(8);
        panel.getStyleClass().add("lyrics-panel");
        panel.setPadding(new Insets(12));
        panel.setPrefWidth(270);
        panel.setMinWidth(270);

        Label titleLabel = new Label("Lyrics");
        titleLabel.getStyleClass().add("media-label");

        Button hideLyrics = new Button("[X]");
        hideLyrics.getStyleClass().add("close-button");
        hideLyrics.setOnAction(e -> hide());

        HBox panelHeader = new HBox(8, titleLabel, hideLyrics);
        panelHeader.setAlignment(Pos.CENTER_RIGHT);

        lyricsLinesBox = new VBox(8);
        lyricsLinesBox.setAlignment(Pos.TOP_CENTER);
        lyricsLinesBox.setFillWidth(true);
        lyricsLinesBox.getChildren().add(createPlaceholderLabel("Selecciona una canción"));

        lyricsScrollPane = new ScrollPane(lyricsLinesBox);
        lyricsScrollPane.setFitToWidth(true);
        lyricsScrollPane.setPrefHeight(210);
        lyricsScrollPane.getStyleClass().add("lyrics-scroll");
        lyricsLinesBox.prefWidthProperty().bind(lyricsScrollPane.widthProperty().subtract(18));

        panel.getChildren().addAll(panelHeader, lyricsScrollPane);
        return panel;
    }

    private StackPane createRoot() {
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

        VBox panel = createPanel();
        root.getChildren().addAll(blurredBackground, panel);

        Rectangle shellClip = new Rectangle();
        shellClip.widthProperty().bind(root.widthProperty());
        shellClip.heightProperty().bind(root.heightProperty());
        shellClip.setArcWidth(36);
        shellClip.setArcHeight(36);
        root.setClip(shellClip);

        return root;
    }

    private void scrollToTop() {
        if (lyricsScrollPane == null) {
            return;
        }

        Platform.runLater(() -> animateScrollTo(0));
    }

    private void animateScrollTo(double targetVValue) {
        if (lyricsScrollPane == null) {
            return;
        }

        double target = Math.max(0, Math.min(1, targetVValue));
        double current = lyricsScrollPane.getVvalue();
        double delta = Math.abs(target - current);

        if (delta < 0.002) {
            lyricsScrollPane.setVvalue(target);
            return;
        }

        if (scrollAnimation != null) {
            scrollAnimation.stop();
        }

        double durationMs = MIN_SCROLL_DURATION_MS + ((MAX_SCROLL_DURATION_MS - MIN_SCROLL_DURATION_MS) * Math.min(1.0, delta * 1.8));

        scrollAnimation = new Timeline(
                new KeyFrame(Duration.millis(durationMs),
                        new KeyValue(lyricsScrollPane.vvalueProperty(), target, Interpolator.EASE_BOTH)));
        scrollAnimation.play();
    }

    private void setPlaceholder(String text) {
        lyricsLinesBox.getChildren().setAll(createPlaceholderLabel(text));
        lyricLineLinks.clear();
    }

    private Label createPlaceholderLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status-label");
        label.setWrapText(true);
        label.setAlignment(Pos.CENTER);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private String toCssRgba(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return "rgba(" + r + "," + g + "," + b + ",0.95)";
    }
}
