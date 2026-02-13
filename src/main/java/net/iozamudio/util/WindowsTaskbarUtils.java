package net.iozamudio.util;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.Locale;

public final class WindowsTaskbarUtils {
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_APPWINDOW = 0x00040000;

    private WindowsTaskbarUtils() {
    }

    public static void hideFromTaskbarBestEffort(String windowTitle) {
        if (windowTitle == null || windowTitle.isBlank()) {
            return;
        }

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            return;
        }

        Thread worker = new Thread(() -> {
            for (int attempt = 0; attempt < 12; attempt++) {
                WinDef.HWND hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
                if (hwnd != null) {
                    applyToolWindowStyle(hwnd);
                    return;
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "TaskbarHider");

        worker.setDaemon(true);
        worker.start();
    }

    public static void showInTaskbarBestEffort(String windowTitle) {
        if (windowTitle == null || windowTitle.isBlank()) {
            return;
        }

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            return;
        }

        Thread worker = new Thread(() -> {
            for (int attempt = 0; attempt < 12; attempt++) {
                WinDef.HWND hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
                if (hwnd != null) {
                    applyAppWindowStyle(hwnd);
                    return;
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "TaskbarShower");

        worker.setDaemon(true);
        worker.start();
    }

    private static void applyToolWindowStyle(WinDef.HWND hwnd) {
        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        int updatedStyle = (exStyle | WS_EX_TOOLWINDOW) & ~WS_EX_APPWINDOW;
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, updatedStyle);

        int flags = WinUser.SWP_NOMOVE
                | WinUser.SWP_NOSIZE
                | WinUser.SWP_NOZORDER
                | WinUser.SWP_NOACTIVATE
                | WinUser.SWP_FRAMECHANGED;

        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, flags);
    }

    private static void applyAppWindowStyle(WinDef.HWND hwnd) {
        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        int updatedStyle = (exStyle | WS_EX_APPWINDOW) & ~WS_EX_TOOLWINDOW;
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, updatedStyle);

        int flags = WinUser.SWP_NOMOVE
                | WinUser.SWP_NOSIZE
                | WinUser.SWP_NOZORDER
                | WinUser.SWP_NOACTIVATE
                | WinUser.SWP_FRAMECHANGED;

        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, flags);
    }
}
