package net.iozamudio.util;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Utilidad para simular teclas multimedia usando la API de Windows.
 */
public class MediaKeySimulator {

    // Interfaz para User32.dll
    public interface User32Ext extends StdCallLibrary {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class);

        void keybd_event(byte bVk, byte bScan, int dwFlags, BaseTSD.ULONG_PTR dwExtraInfo);
    }

    // Constantes
    private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    // Códigos de teclas multimedia de Windows
    private static final byte VK_MEDIA_NEXT_TRACK = (byte) 0xB0;
    private static final byte VK_MEDIA_PREV_TRACK = (byte) 0xB1;
    private static final byte VK_MEDIA_PLAY_PAUSE = (byte) 0xB3;

    /**
     * Simula presionar una tecla multimedia.
     */
    private static void pressKey(byte keyCode) {
        User32Ext user32 = User32Ext.INSTANCE;

        // Presionar tecla
        user32.keybd_event(keyCode, (byte) 0, KEYEVENTF_EXTENDEDKEY, new BaseTSD.ULONG_PTR(0));

        // Soltar tecla
        user32.keybd_event(keyCode, (byte) 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, new BaseTSD.ULONG_PTR(0));
    }

    /**
     * Simula presionar la tecla "Next Track".
     */
    public static void next() {
        pressKey(VK_MEDIA_NEXT_TRACK);
    }

    /**
     * Simula presionar la tecla "Previous Track".
     */
    public static void previous() {
        pressKey(VK_MEDIA_PREV_TRACK);
    }

    /**
     * Simula presionar la tecla "Play/Pause".
     */
    public static void playPause() {
        pressKey(VK_MEDIA_PLAY_PAUSE);
    }
}
