package net.iozamudio.infrastructure.media;

import net.iozamudio.application.port.out.MediaControlPort;
import net.iozamudio.util.MediaKeySimulator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class WindowsMediaControlAdapter implements MediaControlPort {

    private final File controllerExecutable;

    public WindowsMediaControlAdapter() {
        this.controllerExecutable = resolveControllerExecutable();
    }

    @Override
    public void next() {
        if (!executeController("next")) {
            MediaKeySimulator.next();
        }
        System.out.println("Command 'next' executed");
    }

    @Override
    public void previous() {
        if (!executeController("previous")) {
            MediaKeySimulator.previous();
        }
        System.out.println("Command 'previous' executed");
    }

    @Override
    public void playPause() {
        if (!executeController("playpause")) {
            MediaKeySimulator.playPause();
        }
        System.out.println("Command 'playpause' executed");
    }

    @Override
    public void seekToSeconds(double seconds) {
        int seekSeconds = (int) Math.max(0, Math.round(seconds));
        if (!executeController("seek", String.valueOf(seekSeconds))) {
            System.err.println("Seek command failed via media-controller");
        } else {
            System.out.println("Command 'seek' executed to second " + seekSeconds);
        }
    }

    @Override
    public void openCurrentInBrowser() {
        if (!executeController("focussource")) {
            System.err.println("Focus source command failed via media-controller");
        } else {
            System.out.println("Command 'focussource' executed");
        }
    }

    private boolean executeController(String command, String... args) {
        if (controllerExecutable == null || !controllerExecutable.exists()) {
            return false;
        }

        try {
            List<String> commandLine = new ArrayList<>();
            String path = controllerExecutable.getAbsolutePath();

            if (path.toLowerCase().endsWith(".dll")) {
                commandLine.add("dotnet");
                commandLine.add(path);
            } else {
                commandLine.add(path);
            }

            commandLine.add(command);
            for (String arg : args) {
                commandLine.add(arg);
            }

            Process process = new ProcessBuilder(commandLine)
                    .redirectErrorStream(true)
                    .start();

            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            System.err.println("media-controller invocation failed: " + e.getMessage());
            return false;
        }
    }

    private File resolveControllerExecutable() {
        File appRoot = resolveAppRoot();

        List<File> candidates = List.of(
            new File(appRoot, "media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-controller.exe"),
            new File(appRoot, "media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-controller.dll"),
                new File("media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-controller.exe"),
                new File("media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-controller.dll"),
                new File("media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64/publish/media-controller.exe"),
                new File("media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64/publish/media-controller.dll"));

        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate;
            }
        }

        return candidates.get(0);
    }

    private File resolveAppRoot() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            File executable = new File(appPath);
            File appDir = executable.getParentFile();
            if (appDir != null) {
                File root = appDir.getParentFile();
                if (root != null) {
                    return root;
                }
                return appDir;
            }
        }

        return new File(System.getProperty("user.dir", "."));
    }
}