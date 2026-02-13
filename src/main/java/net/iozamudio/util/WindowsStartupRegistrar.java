package net.iozamudio.util;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class WindowsStartupRegistrar {
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String RUN_VALUE_NAME = "VinilPlayer";

    private WindowsStartupRegistrar() {
    }

    public static void ensureCurrentExecutableStartsWithWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            return;
        }

        String executablePath = resolveCurrentExecutablePath();
        if (executablePath == null || executablePath.isBlank()) {
            return;
        }

        String lower = executablePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith("java.exe") || lower.endsWith("javaw.exe")) {
            return;
        }

        String quotedExecutable = '"' + executablePath + '"';

        Process process = null;
        try {
            process = new ProcessBuilder(
                    "reg",
                    "add",
                    RUN_KEY,
                    "/v", RUN_VALUE_NAME,
                    "/t", "REG_SZ",
                    "/d", quotedExecutable,
                    "/f")
                    .redirectErrorStream(true)
                    .start();

            process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String resolveCurrentExecutablePath() {
        String override = System.getProperty("vinil.startup.path.override");
        if (override != null && !override.isBlank()) {
            return override;
        }

        Optional<String> command = ProcessHandle.current().info().command();
        return command.orElse(null);
    }
}
