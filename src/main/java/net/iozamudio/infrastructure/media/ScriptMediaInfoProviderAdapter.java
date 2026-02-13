package net.iozamudio.infrastructure.media;

import com.google.gson.Gson;
import net.iozamudio.application.port.out.MediaInfoProviderPort;
import net.iozamudio.application.port.out.MediaInfoSubscriptionPort;
import net.iozamudio.model.MediaInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ScriptMediaInfoProviderAdapter implements MediaInfoProviderPort, MediaInfoSubscriptionPort {
    private final Gson gson;
    private final File scriptFile;
    private volatile MediaInfo latest = new MediaInfo("", "", "STOPPED", "");
    private volatile boolean subscribed = false;
    private Process process;
    private Thread outputThread;
    private Thread errorThread;

    public ScriptMediaInfoProviderAdapter() {
        this.gson = new Gson();
        this.scriptFile = resolveMediaReaderBinary();
        System.out.println("Using media reader: " + this.scriptFile.getAbsolutePath());
    }

    @Override
    public MediaInfo getCurrent() {
        return latest;
    }

    @Override
    public synchronized void subscribe(Consumer<MediaInfo> onMediaUpdate) throws Exception {
        if (subscribed) {
            return;
        }

        ProcessBuilder pb;
        String name = scriptFile.getName().toLowerCase();

        if (name.endsWith(".dll")) {
            pb = new ProcessBuilder("dotnet", scriptFile.getAbsolutePath());
        } else if (name.endsWith(".exe")) {
            pb = new ProcessBuilder(scriptFile.getAbsolutePath());
        } else {
            pb = new ProcessBuilder(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.getAbsolutePath());
        }

        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.put("DOTNET_CLI_UI_LANGUAGE", "en-US");

        process = pb.start();
        subscribed = true;

        errorThread = new Thread(() -> readErrors(process), "MediaReader-Stderr");
        errorThread.setDaemon(true);
        errorThread.start();

        outputThread = new Thread(() -> readOutput(process, onMediaUpdate), "MediaReader-Stdout");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    @Override
    public synchronized void unsubscribe() {
        subscribed = false;

        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }
        if (errorThread != null) {
            errorThread.interrupt();
            errorThread = null;
        }

        if (process != null) {
            Process processToStop = process;

            processToStop.descendants().forEach(child -> {
                try {
                    child.destroy();
                } catch (Exception ignored) {
                }
            });

            processToStop.destroy();
            try {
                if (!processToStop.waitFor(1200, TimeUnit.MILLISECONDS)) {
                    processToStop.descendants().forEach(child -> {
                        try {
                            child.destroyForcibly();
                        } catch (Exception ignored) {
                        }
                    });
                    processToStop.destroyForcibly();
                    processToStop.waitFor(1200, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                processToStop.destroyForcibly();
            }

            if (processToStop.isAlive()) {
                forceKillProcessTreeOnWindows(processToStop.pid());
            }

            if (processToStop.isAlive()) {
                System.err.println("media-reader process is still alive after shutdown attempt");
            } else {
                System.out.println("media-reader process stopped correctly");
            }

            process = null;
        }
    }

    private void readOutput(Process targetProcess, Consumer<MediaInfo> onMediaUpdate) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(targetProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String json = line.trim();
                if (json.isEmpty()) {
                    continue;
                }

                try {
                    MediaInfoJson data = gson.fromJson(json, MediaInfoJson.class);
                    MediaInfo info = new MediaInfo(
                            data.artist(),
                            data.title(),
                            data.status(),
                            data.position(),
                            data.duration(),
                            data.thumbnail());

                    latest = info;
                    onMediaUpdate.accept(info);
                } catch (Exception parseError) {
                    System.err.println("media-reader invalid JSON line: " + parseError.getMessage());
                }
            }
        } catch (Exception e) {
            if (subscribed) {
                System.err.println("media-reader stream stopped: " + e.getMessage());
            }
        } finally {
            subscribed = false;
        }
    }

    private void readErrors(Process targetProcess) {
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(targetProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                if (!line.isBlank()) {
                    System.err.println("[media-reader] " + line);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void forceKillProcessTreeOnWindows(long pid) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }

        try {
            Process taskkill = new ProcessBuilder(
                    "taskkill",
                    "/PID", String.valueOf(pid),
                    "/T",
                    "/F")
                    .redirectErrorStream(true)
                    .start();

            taskkill.waitFor(2500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.err.println("taskkill fallback failed: " + e.getMessage());
        }
    }

    private record MediaInfoJson(
            String artist,
            String title,
            String status,
            double position,
            double duration,
            String thumbnail) {
    }

    private File resolveMediaReaderBinary() {
        File appRoot = resolveAppRoot();

        File[] candidates = new File[] {
                new File(appRoot, "media-reader/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-reader.exe"),
                new File(appRoot, "media-reader/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-reader.dll"),
                new File("media-reader/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-reader.exe"),
                new File("media-reader/bin/Release/net8.0-windows10.0.19041.0/win-x64/media-reader.dll"),
                new File("scripts/get-media.ps1")
        };

        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate;
            }
        }

        return candidates[0];
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