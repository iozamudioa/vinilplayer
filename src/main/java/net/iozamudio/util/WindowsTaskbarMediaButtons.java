package net.iozamudio.util;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.CallbackReference;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import javafx.application.Platform;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class WindowsTaskbarMediaButtons {
    private static final Guid.GUID CLSID_TASKBAR_LIST = new Guid.GUID("56FDF344-FD6D-11d0-958A-006097C9A090");
    private static final Guid.GUID IID_ITASKBAR_LIST = new Guid.GUID("56FDF342-FD6D-11d0-958A-006097C9A090");
    private static final Guid.GUID IID_ITASKBAR_LIST3 = new Guid.GUID("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF");

    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int GWL_WNDPROC = -4;

    private static final int WM_COMMAND = 0x0111;
    private static final int THBN_CLICKED = 0x1800;
    private static final int BUTTON_OPEN = 3000;
    private static final int BUTTON_PREVIOUS = 3001;
    private static final int BUTTON_PLAY_PAUSE = 3002;
    private static final int BUTTON_NEXT = 3003;

    private static final int THB_ICON = 0x2;
    private static final int THB_TOOLTIP = 0x4;
    private static final int THB_FLAGS = 0x8;
    private static final int LR_LOADFROMFILE = 0x00000010;

    private static final int THBF_ENABLED = 0x0000;
    private static final int THBF_DISMISSONCLICK = 0x0002;

    private static final int MAX_TOOLTIP = 260;

    private static volatile WinDef.HWND targetWindow;
    private static volatile Pointer taskbarList3;
    private static volatile Pointer oldWindowProc;
    private static volatile WinUser.WindowProc installedWindowProc;
    private static volatile WinDef.HICON playIconHandle;
    private static volatile WinDef.HICON pauseIconHandle;
    private static volatile boolean currentPlayingState;

    private WindowsTaskbarMediaButtons() {
    }

    public static void installBestEffort(String windowTitle,
                                         Runnable onOpen,
                                         Runnable onPrevious,
                                         Runnable onPlayPause,
                                         Runnable onNext) {
        if (!isWindows() || windowTitle == null || windowTitle.isBlank()) {
            return;
        }

        Thread worker = new Thread(() -> {
            for (int attempt = 0; attempt < 35; attempt++) {
                WinDef.HWND hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
                if (hwnd != null) {
                    if (installOnWindow(hwnd, onOpen, onPrevious, onPlayPause, onNext)) {
                        return;
                    }
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            System.err.println("Taskbar media buttons: installation timed out");
        }, "TaskbarMediaButtons-Installer");

        worker.setDaemon(true);
        worker.start();
    }

    public static synchronized void uninstallBestEffort() {
        try {
            if (targetWindow != null && oldWindowProc != null) {
                User32.INSTANCE.SetWindowLongPtr(targetWindow, GWL_WNDPROC, oldWindowProc);
            }
        } catch (Throwable ignored) {
        }

        targetWindow = null;
        oldWindowProc = null;
        installedWindowProc = null;
        playIconHandle = null;
        pauseIconHandle = null;
        currentPlayingState = false;

        if (taskbarList3 != null) {
            try {
                invokeComMethod(taskbarList3, 2);
            } catch (Throwable ignored) {
            }
            taskbarList3 = null;
        }

        try {
            Ole32.INSTANCE.CoUninitialize();
        } catch (Throwable ignored) {
        }
    }

    private static synchronized boolean installOnWindow(WinDef.HWND hwnd,
                                                        Runnable onOpen,
                                                        Runnable onPrevious,
                                                        Runnable onPlayPause,
                                                        Runnable onNext) {
        if (targetWindow != null) {
            return true;
        }

        WinNT.HRESULT initResult = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED);
        if (initResult.intValue() < 0) {
            System.err.println("Taskbar media buttons: CoInitializeEx failed hr=" + initResult.intValue());
            return false;
        }

        PointerByReference ppv = new PointerByReference();
        WinNT.HRESULT hr = Ole32.INSTANCE.CoCreateInstance(
                CLSID_TASKBAR_LIST,
                null,
                CLSCTX_INPROC_SERVER,
            IID_ITASKBAR_LIST,
                ppv);

        if (hr.intValue() < 0 || ppv.getValue() == null) {
            System.err.println("Taskbar media buttons: CoCreateInstance failed hr=" + hr.intValue());
            Ole32.INSTANCE.CoUninitialize();
            return false;
        }

        Pointer baseComObject = ppv.getValue();
        PointerByReference taskbar3Ref = new PointerByReference();
        int hrQuery = invokeComMethod(baseComObject, 0, IID_ITASKBAR_LIST3, taskbar3Ref);
        if (hrQuery < 0 || taskbar3Ref.getValue() == null) {
            System.err.println("Taskbar media buttons: QueryInterface(ITaskbarList3) failed hr=" + hrQuery);
            invokeComMethod(baseComObject, 2);
            Ole32.INSTANCE.CoUninitialize();
            return false;
        }

        Pointer comObject = taskbar3Ref.getValue();
        invokeComMethod(baseComObject, 2);
        int hrInit = invokeComMethod(comObject, 3);
        if (hrInit < 0) {
            System.err.println("Taskbar media buttons: HrInit failed hr=" + hrInit);
            invokeComMethod(comObject, 2);
            Ole32.INSTANCE.CoUninitialize();
            return false;
        }

        THUMBBUTTON[] buttons = (THUMBBUTTON[]) new THUMBBUTTON().toArray(4);
        WinDef.HICON openIcon = loadTransportIcon("open", IconKind.OPEN);
        WinDef.HICON prevIcon = loadTransportIcon("prev", IconKind.PREVIOUS);
        WinDef.HICON playIcon = loadTransportIcon("play", IconKind.PLAY);
        WinDef.HICON pauseIcon = loadTransportIcon("pause", IconKind.PAUSE);
        WinDef.HICON nextIcon = loadTransportIcon("next", IconKind.NEXT);

        configureButton(buttons[0], BUTTON_OPEN, openIcon, "Abrir medio", false);
        configureButton(buttons[1], BUTTON_PREVIOUS, prevIcon, "Anterior", false);
        configureButton(buttons[2], BUTTON_PLAY_PAUSE, playIcon, "Reproducir", false);
        configureButton(buttons[3], BUTTON_NEXT, nextIcon, "Siguiente", false);

        int addButtonsHr = invokeComMethod(comObject, 15, hwnd, buttons.length, buttons[0].getPointer());
        if (addButtonsHr < 0) {
            System.err.println("Taskbar media buttons: ThumbBarAddButtons failed hr=" + addButtonsHr);
            invokeComMethod(comObject, 2);
            Ole32.INSTANCE.CoUninitialize();
            return false;
        }

        WinUser.WindowProc wndProc = (window, uMsg, wParam, lParam) -> {
            if (uMsg == WM_COMMAND) {
                int commandId = wParam.intValue() & 0xFFFF;
                int notificationCode = (wParam.intValue() >> 16) & 0xFFFF;

                if (notificationCode == THBN_CLICKED && commandId == BUTTON_OPEN) {
                    Platform.runLater(onOpen);
                    return new WinDef.LRESULT(0);
                }
                if (notificationCode == THBN_CLICKED && commandId == BUTTON_PREVIOUS) {
                    Platform.runLater(onPrevious);
                    return new WinDef.LRESULT(0);
                }
                if (notificationCode == THBN_CLICKED && commandId == BUTTON_PLAY_PAUSE) {
                    Platform.runLater(onPlayPause);
                    return new WinDef.LRESULT(0);
                }
                if (notificationCode == THBN_CLICKED && commandId == BUTTON_NEXT) {
                    Platform.runLater(onNext);
                    return new WinDef.LRESULT(0);
                }
            }

            return User32.INSTANCE.CallWindowProc(oldWindowProc, window, uMsg, wParam, lParam);
        };

        Pointer callbackPointer = CallbackReference.getFunctionPointer(wndProc);
        Pointer previousProc = User32.INSTANCE.SetWindowLongPtr(hwnd, GWL_WNDPROC, callbackPointer);
        if (previousProc == null) {
            System.err.println("Taskbar media buttons: SetWindowLongPtr failed");
            invokeComMethod(comObject, 2);
            Ole32.INSTANCE.CoUninitialize();
            return false;
        }

        targetWindow = hwnd;
        taskbarList3 = comObject;
        oldWindowProc = previousProc;
        installedWindowProc = wndProc;
        playIconHandle = playIcon;
        pauseIconHandle = pauseIcon;
        currentPlayingState = false;
        System.out.println("Taskbar media buttons installed");
        return true;
    }

    public static synchronized void setPlaybackStateBestEffort(boolean isPlaying) {
        if (!isWindows() || taskbarList3 == null || targetWindow == null) {
            return;
        }

        if (isPlaying == currentPlayingState) {
            return;
        }

        WinDef.HICON icon = isPlaying ? pauseIconHandle : playIconHandle;
        if (icon == null) {
            return;
        }

        THUMBBUTTON[] updateButtons = (THUMBBUTTON[]) new THUMBBUTTON().toArray(1);
        configureButton(updateButtons[0], BUTTON_PLAY_PAUSE, icon, isPlaying ? "Pausar" : "Reproducir", false);

        int updateHr = invokeComMethod(taskbarList3, 16, targetWindow, 1, updateButtons[0].getPointer());
        if (updateHr < 0) {
            System.err.println("Taskbar media buttons: ThumbBarUpdateButtons failed hr=" + updateHr);
            return;
        }

        currentPlayingState = isPlaying;
    }

    private static void configureButton(THUMBBUTTON button,
                                        int id,
                                        WinDef.HICON icon,
                                        String tooltip,
                                        boolean dismissOnClick) {
        button.dwMask = new WinDef.DWORD(THB_ICON | THB_TOOLTIP | THB_FLAGS);
        button.iId = id;
        button.hIcon = icon;
        int flags = THBF_ENABLED;
        if (dismissOnClick) {
            flags |= THBF_DISMISSONCLICK;
        }
        button.dwFlags = new WinDef.DWORD(flags);

        char[] tipChars = tooltip.toCharArray();
        int length = Math.min(tipChars.length, MAX_TOOLTIP - 1);
        System.arraycopy(tipChars, 0, button.szTip, 0, length);
        button.szTip[length] = '\0';
        button.write();
    }

    private static int invokeComMethod(Pointer comObject, int methodIndex, Object... args) {
        Pointer vtbl = comObject.getPointer(0);
        Pointer functionPointer = vtbl.getPointer((long) methodIndex * Native.POINTER_SIZE);
        Function function = Function.getFunction(functionPointer, Function.ALT_CONVENTION);

        Object[] invokeArgs = new Object[args.length + 1];
        invokeArgs[0] = comObject;
        System.arraycopy(args, 0, invokeArgs, 1, args.length);

        return function.invokeInt(invokeArgs);
    }

    private static WinDef.HICON loadTransportIcon(String iconName, IconKind kind) {
        try {
            Path iconPath = ensureTransportIconFile(iconName, kind);
            WinNT.HANDLE handle = User32.INSTANCE.LoadImage(
                    null,
                    iconPath.toString(),
                    WinUser.IMAGE_ICON,
                    16,
                    16,
                    LR_LOADFROMFILE);

            if (handle != null && handle.getPointer() != null) {
                return new WinDef.HICON(handle.getPointer());
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Path ensureTransportIconFile(String iconName, IconKind kind) throws Exception {
        Path iconDir = Path.of(System.getProperty("user.home"), ".vinilplayer", "cache", "taskbar-icons");
        Files.createDirectories(iconDir);

        Path iconPath = iconDir.resolve(iconName + ".ico");
        if (Files.exists(iconPath) && Files.size(iconPath) > 0) {
            return iconPath;
        }

        BufferedImage image = drawTransportIcon(kind);
        byte[] icoBytes = encodeIco(image);
        Files.write(iconPath, icoBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return iconPath;
    }

    private static BufferedImage drawTransportIcon(IconKind kind) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(255, 255, 255, 230));

            switch (kind) {
                case OPEN -> {
                    g.setStroke(new BasicStroke(2.0f));
                    g.drawLine(8, 3, 8, 13);
                    g.drawLine(3, 8, 13, 8);
                }
                case PREVIOUS -> {
                    g.fillPolygon(new int[] {10, 5, 10}, new int[] {3, 8, 13}, 3);
                    g.fillPolygon(new int[] {14, 9, 14}, new int[] {3, 8, 13}, 3);
                }
                case PLAY -> {
                    g.fillPolygon(new int[] {5, 5, 12}, new int[] {3, 13, 8}, 3);
                }
                case PAUSE -> {
                    g.fillRect(4, 3, 3, 10);
                    g.fillRect(9, 3, 3, 10);
                }
                case NEXT -> {
                    g.fillPolygon(new int[] {2, 7, 2}, new int[] {3, 8, 13}, 3);
                    g.fillPolygon(new int[] {6, 11, 6}, new int[] {3, 8, 13}, 3);
                }
            }
        } finally {
            g.dispose();
        }

        return image;
    }

    private static byte[] encodeIco(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int xorSize = width * height * 4;
        int andRowSize = ((width + 31) / 32) * 4;
        int andSize = andRowSize * height;
        int imageDataSize = 40 + xorSize + andSize;

        ByteBuffer buffer = ByteBuffer.allocate(6 + 16 + imageDataSize).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);

        buffer.put((byte) width);
        buffer.put((byte) height);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 32);
        buffer.putInt(imageDataSize);
        buffer.putInt(22);

        buffer.putInt(40);
        buffer.putInt(width);
        buffer.putInt(height * 2);
        buffer.putShort((short) 1);
        buffer.putShort((short) 32);
        buffer.putInt(0);
        buffer.putInt(xorSize + andSize);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);

        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) (argb & 0xFF));
                buffer.put((byte) ((argb >> 8) & 0xFF));
                buffer.put((byte) ((argb >> 16) & 0xFF));
                buffer.put((byte) ((argb >> 24) & 0xFF));
            }
        }

        for (int i = 0; i < andSize; i++) {
            buffer.put((byte) 0);
        }

        return buffer.array();
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win");
    }

    @Structure.FieldOrder({"dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags"})
    public static class THUMBBUTTON extends Structure {
        public WinDef.DWORD dwMask;
        public int iId;
        public int iBitmap;
        public WinDef.HICON hIcon;
        public char[] szTip = new char[MAX_TOOLTIP];
        public WinDef.DWORD dwFlags;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags");
        }
    }

    private enum IconKind {
        OPEN,
        PREVIOUS,
        PLAY,
        PAUSE,
        NEXT
    }
}
