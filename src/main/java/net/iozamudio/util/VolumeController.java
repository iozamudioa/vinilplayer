package net.iozamudio.util;

import com.sun.jna.Native; 
import com.sun.jna.win32.StdCallLibrary;

/**
 * Utilidad para controlar el volumen del sistema Windows.
 */
public class VolumeController {

    private static final int MIN_FADE_STEPS = 80;
    private static final int STEP_INTERVAL_MS = 50;

    // Interfaz para winmm.dll (control de volumen)
    public interface WinMM extends StdCallLibrary {
        WinMM INSTANCE = Native.load("winmm", WinMM.class);

        int waveOutSetVolume(int deviceId, int volume);

        int waveOutGetVolume(int deviceId, int[] volume);
    }

    /**
     * Obtiene el volumen actual del sistema (0-100).
     */
    public static int getVolume() {
        int[] volume = new int[1];
        WinMM.INSTANCE.waveOutGetVolume(-1, volume);

        // El volumen está en formato 0xLLLLRRRR (left/right channels)
        // Extraer el canal izquierdo y convertir a 0-100
        int leftChannel = volume[0] & 0xFFFF;
        return (int) ((leftChannel / 65535.0) * 100);
    }

    /**
     * Establece el volumen del sistema (0-100).
     */
    public static void setVolume(int volumePercent) {
        // Limitar entre 0 y 100
        volumePercent = Math.max(0, Math.min(100, volumePercent));

        // Convertir de 0-100 a 0-65535
        int volumeValue = (int) ((volumePercent / 100.0) * 65535);

        // Combinar ambos canales (left y right)
        int bothChannels = (volumeValue << 16) | volumeValue;

        WinMM.INSTANCE.waveOutSetVolume(-1, bothChannels);
    }

    /**
     * Hace un fade-in del volumen desde 0 hasta el volumen objetivo.
     * 
     * @param targetVolume Volumen objetivo (0-100)
     * @param durationMs   Duración del fade en milisegundos
     */
    public static void fadeIn(int targetVolume, int durationMs) {
        new Thread(() -> {
            try {
                int safeDurationMs = Math.max(100, durationMs);
                int steps = Math.max(MIN_FADE_STEPS, safeDurationMs / STEP_INTERVAL_MS);
                int stepDelay = Math.max(10, safeDurationMs / steps);

                for (int i = 0; i <= steps; i++) {
                    double progress = i / (double) steps;
                    double easedProgress = progress * progress * (3 - (2 * progress));
                    int currentVolume = (int) Math.round(easedProgress * targetVolume);
                    setVolume(currentVolume);
                    Thread.sleep(stepDelay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "VolumeFadeIn").start();
    }

    public static void fadeInFromZeroToSystemVolume(int durationMs) {
        int safeDurationMs = Math.max(100, durationMs);
        int targetVolume = getVolume();

        if (targetVolume <= 0) {
            return;
        }

        setVolume(0);
        fadeIn(targetVolume, safeDurationMs);
    }
}
