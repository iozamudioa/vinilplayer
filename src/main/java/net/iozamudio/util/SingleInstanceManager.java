package net.iozamudio.util;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SingleInstanceManager implements AutoCloseable {
    private static final int PORT = 44567;
    private static final String SHOW_COMMAND = "SHOW";

    private final ServerSocket serverSocket;
    private final ExecutorService listenerExecutor;

    private SingleInstanceManager(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.listenerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VinilPlayer-SingleInstanceListener");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static SingleInstanceManager tryAcquirePrimary() {
        try {
            ServerSocket socket = new ServerSocket(PORT, 10, InetAddress.getLoopbackAddress());
            return new SingleInstanceManager(socket);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean signalExistingInstanceToShow() {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(SHOW_COMMAND);
            writer.newLine();
            writer.flush();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public void startListening(Runnable onShowRequested) {
        listenerExecutor.submit(() -> {
            while (!serverSocket.isClosed()) {
                try (Socket ignored = serverSocket.accept()) {
                    onShowRequested.run();
                } catch (Exception ignoredAccept) {
                    if (serverSocket.isClosed()) {
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void close() {
        listenerExecutor.shutdownNow();
        try {
            serverSocket.close();
        } catch (Exception ignored) {
        }
    }
}
