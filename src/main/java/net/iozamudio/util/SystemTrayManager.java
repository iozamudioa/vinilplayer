package net.iozamudio.util;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

public class SystemTrayManager {
    private TrayIcon trayIcon;

    public void install(Stage stage, Runnable onRestoreRequested) {
        if (!SystemTray.isSupported() || trayIcon != null) {
            return;
        }

        trayIcon = new TrayIcon(createTrayImage(), "VinilPlayer");
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(event -> Platform.runLater(() -> {
            if (onRestoreRequested != null) {
                onRestoreRequested.run();
                return;
            }
            restoreStage(stage);
        }));

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            System.err.println("No se pudo crear icono de bandeja: " + e.getMessage());
            trayIcon = null;
        }
    }

    public void remove() {
        if (trayIcon == null) {
            return;
        }

        SystemTray.getSystemTray().remove(trayIcon);
        trayIcon = null;
    }

    private void restoreStage(Stage stage) {
        if (stage.isIconified()) {
            stage.setIconified(false);
        }

        if (!stage.isShowing()) {
            stage.show();
        }

        stage.toFront();
        stage.requestFocus();
    }

    private Image createTrayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(0, 0, 0, 0));
        graphics.fillRect(0, 0, 16, 16);
        graphics.setColor(new Color(24, 24, 24, 220));
        graphics.fillOval(1, 1, 14, 14);
        graphics.setColor(new Color(0, 255, 166, 240));
        graphics.fillOval(5, 5, 6, 6);
        graphics.dispose();
        return image;
    }
}
