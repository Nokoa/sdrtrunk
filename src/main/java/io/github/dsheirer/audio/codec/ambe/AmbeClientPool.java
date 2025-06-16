package io.github.dsheirer.audio.codec.ambe;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages a pool of AmbeClient instances for efficient reuse across audio modules.
 * This class ensures that AmbeClient instances are initialized once and then
 * handed out and returned as needed, preventing excessive socket creation and
 * vocoder re-initialization on the AMBE server.
 */
public class AmbeClientPool {

    private static final Logger mLog = LoggerFactory.getLogger(AmbeClientPool.class);

    private static AmbeClientPool instance; // Singleton instance
    private final BlockingQueue<AmbeClient> availableClients;
    private final String serverAddress;
    private final int serverPort;
    private final int poolSize;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Private constructor to enforce singleton pattern.
     * Initializes the pool with a specified number of AmbeClient instances.
     *
     * @param poolSize The maximum number of AmbeClient instances in the pool.
     * @param serverAddress The IP address of the AMBE server.
     * @param serverPort The port of the AMBE server.
     * @throws IOException If an I/O error occurs during client initialization.
     * @throws InterruptedException If the thread is interrupted during initialization.
     */
    private AmbeClientPool(int poolSize, String serverAddress, int serverPort) throws IOException, InterruptedException {
        this.poolSize = poolSize;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.availableClients = new ArrayBlockingQueue<>(poolSize);

        mLog.info("Initializing AMBE Client Pool with size: " + poolSize + " to server " + serverAddress + ":" + serverPort);
        for (int i = 0; i < poolSize; i++) {
            try {
                AmbeClient client = new AmbeClient(serverAddress, serverPort, i + 1);
                client.initializeAmbeChip(); // Initialize each client once
                availableClients.put(client); // Add to available clients
                mLog.info("AmbeClient #" + (i + 1) + " initialized and added to pool (Local Port: " + client.getSocketLocalPort() + ").");
            } catch (IOException | InterruptedException e) {
                mLog.error("Failed to initialize AmbeClient #" + (i + 1) + ". Skipping this client. Error: " + e.getMessage(), e);
                // Continue to try initializing other clients, but the pool might end up smaller than requested.
                // In a real application, you might want a more robust retry or error handling here.
            }
        }
        if (availableClients.isEmpty()) {
            mLog.error("No AmbeClients could be initialized. The pool is empty. Vocoder functionality will be unavailable.");
            throw new IOException("AMBE Client Pool could not be initialized. No clients available.");
        }
        mLog.info("AMBE Client Pool initialization complete. Available clients: " + availableClients.size());
    }

    /**
     * Returns the singleton instance of the AmbeClientPool.
     * If the instance does not exist, it will be created with the specified parameters.
     * Subsequent calls will return the same instance, ignoring creation parameters.
     *
     * @param poolSize The size of the client pool (only used on first creation).
     * @param serverAddress The AMBE server address (only used on first creation).
     * @param serverPort The AMBE server port (only used on first creation).
     * @return The singleton AmbeClientPool instance.
     * @throws IOException If the pool cannot be initialized.
     * @throws InterruptedException If interrupted during initialization.
     */
    public static synchronized AmbeClientPool getInstance(int poolSize, String serverAddress, int serverPort) throws IOException, InterruptedException {
        if (instance == null) {
            instance = new AmbeClientPool(poolSize, serverAddress, serverPort);
        }
        return instance;
    }

    public static synchronized AmbeClientPool getInstance() throws IOException, InterruptedException {
        if (instance == null) {
            instance = new AmbeClientPool(10, "127.0.0.1", 25565);
        }
        return instance;
    }

    /**
     * Retrieves an available AmbeClient from the pool.
     * This method will block until a client is available or the timeout is reached.
     *
     * @param timeout The maximum time to wait for a client.
     * @param unit The time unit of the timeout argument.
     * @return An available AmbeClient instance, or null if a client cannot be retrieved within the timeout.
     * @throws InterruptedException If the current thread is interrupted while waiting.
     */
    public AmbeClient getClient(long timeout, TimeUnit unit) throws InterruptedException {
        if (shuttingDown.get()) {
            mLog.warn("Attempted to get client from pool during shutdown. Returning null.");
            return null;
        }
        AmbeClient client = availableClients.poll(timeout, unit);
        if (client != null) {
            client.setInUse(true);
//            mLog.debug("Client acquired from pool: Local Port: " + client.getSocketLocalPort() + ". Available: " + availableClients.size() + "/" + poolSize);
        } else {
            mLog.warn("Failed to acquire AmbeClient from pool within " + timeout + " " + unit + ". Pool size: " + availableClients.size() + "/" + poolSize);
        }
        return client;
    }

    /**
     * Returns an AmbeClient to the pool.
     *
     * @param client The AmbeClient instance to return.
     */
    public void returnClient(AmbeClient client) {
        if (client == null) {
            return;
        }
        client.setInUse(false);
        try {
            if (!shuttingDown.get()) { // Only return to pool if not shutting down
                availableClients.put(client); // Add back to available clients
//                mLog.debug("Client returned to pool: Local Port: " + client.getSocketLocalPort() + ". Available: " + availableClients.size() + "/" + poolSize);
            } else {
                client.dispose(); // If shutting down, dispose the client directly
                mLog.debug("Client disposed directly during shutdown: Local Port: " + client.getSocketLocalPort());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mLog.error("Interrupted while returning client to pool. Client (Port: " + client.getSocketLocalPort() + ") will be disposed.", e);
            client.dispose(); // Ensure client is disposed if cannot be returned
        }
    }

    /**
     * Shuts down the client pool, disposing of all contained AmbeClient instances.
     * This should be called during application shutdown.
     */
    public synchronized void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            mLog.warn("AMBE Client Pool is already shutting down or has shut down.");
            return;
        }
        mLog.info("Shutting down AMBE Client Pool. Disposing " + availableClients.size() + " clients.");
        while (!availableClients.isEmpty()) {
            AmbeClient client = availableClients.poll();
            if (client != null) {
                client.dispose();
            }
        }
        // In case there are clients still "in use" but not yet returned when shutdown is called:
        // Their dispose() will be called when they are eventually returned via returnClient()
        // because shuttingDown flag will be true.
        mLog.info("AMBE Client Pool shutdown complete.");
        instance = null; // Clear static instance
    }

    public int getAvailableClients(){
        return availableClients.size();
    }
}

