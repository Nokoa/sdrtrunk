// AmbeClient.java
package io.github.dsheirer.audio.codec.ambe;

import io.github.dsheirer.sample.Listener;
import jmbe.iface.IAudioWithMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class AmbeClient {

    private static final Logger mLog = LoggerFactory.getLogger(AmbeClient.class);

    private static final byte START_BYTE = (byte) 0x61;
    private static final byte CONTROL_TYPE = (byte) 0x00;
    private static final byte CHANNEL_TYPE = (byte) 0x01;
    private static final byte SPEECH_TYPE = (byte) 0x02;

    // Control Packet Field Identifiers
    private static final byte PKT_PRODID = (byte) 0x30;
    private static final byte PKT_INIT = (byte) 0x0B;
    private static final byte PKT_RATET = (byte) 0x09;
    private static final byte PKT_GAIN = (byte) 0x4B;

    // Channel Packet Field Identifiers
    private static final byte PKT_CHANNEL0 = (byte) 0x40;
    private static final byte CHAND = (byte) 0x01;
    private static final byte SAMPLES = (byte) 0x03;

    private static final int AMBE_FRAME_LENGTH = 9; // 9 bytes for an AMBE voice frame
    private static final int PCM_SAMPLE_LENGTH = 160; // 160 samples per AMBE frame (8kHz * 20ms)
    private static final int PCM_BYTE_LENGTH = PCM_SAMPLE_LENGTH * 2; // 160 samples * 2 bytes/sample (16-bit PCM)
    private static final int MAX_UDP_PACKET_SIZE = 512; // A reasonable maximum for UDP packets

    private DatagramChannel mChannel;
    private InetSocketAddress mServerSocketAddress;
    private Listener<IAudioWithMetadata> mAudioDataListener;
    private Thread mReceiveThread;
    private volatile boolean mRunning;

    private BlockingQueue<byte[]> mReceivedPcmQueue = new LinkedBlockingQueue<>();
    private final Map<Byte, CompletableFuture<byte[]>> pendingControlResponses = new ConcurrentHashMap<>();
    private final long CONTROL_RESPONSE_TIMEOUT_MS = 2000;
    private AtomicBoolean inUse = new AtomicBoolean(false);
    private final int ID;

    // Buffer pool for PCM data to reduce allocations
    private final BlockingQueue<byte[]> pcmBufferPool = new LinkedBlockingQueue<>(PCM_BUFFER_POOL_SIZE);
    private static final int PCM_BUFFER_POOL_SIZE = 200; // Increased pool size to 200 (from 50)

    public AmbeClient(String serverAddress, int serverPort, int id) throws IOException {
        mServerSocketAddress = new InetSocketAddress(serverAddress, serverPort);
        mChannel = DatagramChannel.open();
        mChannel.configureBlocking(false);
        mChannel.bind(null);
        mRunning = true;
        ID = id;

        // Initialize PCM buffer pool
        for (int i = 0; i < PCM_BUFFER_POOL_SIZE; i++) {
            pcmBufferPool.offer(new byte[PCM_BYTE_LENGTH]);
        }

        startReceiveThread();
    }

    private void startReceiveThread() {
        mReceiveThread = new Thread(() -> {
            ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(MAX_UDP_PACKET_SIZE);
            while (mRunning) {
                try {
                    receiveBuffer.clear();
                    InetSocketAddress sender = (InetSocketAddress) mChannel.receive(receiveBuffer);

                    if (sender != null) {
                        receiveBuffer.flip();
                        byte[] receivedData = new byte[receiveBuffer.remaining()];
                        receiveBuffer.get(receivedData);

                        if (sender.equals(mServerSocketAddress)) {
                            processReceivedPacket(receivedData);
                        } else {
                            mLog.warn("Received packet from unknown sender: " + sender.getAddress().getHostAddress() + ":" + sender.getPort());
                        }
                    } else {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            mLog.info("Receive thread interrupted during sleep.");
                        }
                    }
                } catch (IOException e) {
                    if (mRunning) {
                        mLog.warn("AMBE Client receive error on channel " + mChannel.socket().getLocalPort() + ": " + e.getMessage());
                    }
                }
            }
        }, "AmbeClient-ReceiveThread-" + mChannel.socket().getLocalPort());
        mReceiveThread.setDaemon(true);
        mReceiveThread.start();
    }

    public int getID() {
        return ID;
    }

    private void processReceivedPacket(byte[] receivedData) {
        if (receivedData.length < 320) {
            mLog.warn("Received invalid packet (length issue): " + bytesToHex(receivedData));
            return;
        }

        byte[] pcmDataBuffer = pcmBufferPool.poll();
        if (pcmDataBuffer == null) {
            pcmDataBuffer = new byte[PCM_BYTE_LENGTH];
            mLog.warn("PCM buffer pool exhausted, allocating new buffer. Consider increasing PCM_BUFFER_POOL_SIZE.");
        }

        System.arraycopy(receivedData, 0, pcmDataBuffer, 0, PCM_BYTE_LENGTH);
        try {
            mReceivedPcmQueue.put(pcmDataBuffer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mLog.error("Interrupted while adding PCM data to queue.", e);
            pcmBufferPool.offer(pcmDataBuffer); // Return buffer if not put into queue
        }


    }

    public void setAudioDataListener(Listener<IAudioWithMetadata> listener) {
        mAudioDataListener = listener;
    }

    /**
     * Retrieves a decoded PCM audio frame from the client's internal buffer.
     * This method will block for up to the specified timeout if no data is available.
     * After use, the returned byte array SHOULD be returned to the pool via returnPcmBufferToPool().
     *
     * @param timeout The maximum time to wait.
     * @param unit    The time unit of the timeout argument.
     * @return A byte array containing 16-bit signed PCM audio, or null if the timeout expires.
     * @throws InterruptedException If the current thread is interrupted while waiting.
     */
    public byte[] getDecodedAudioFrame(long timeout, TimeUnit unit) throws InterruptedException {
        return mReceivedPcmQueue.poll(timeout, unit);
    }

    /**
     * Returns a PCM buffer to the pool after it has been processed.
     * This is crucial for memory efficiency when using the buffer pool.
     *
     * @param buffer The byte array buffer to return.
     */
    public void returnPcmBufferToPool(byte[] buffer) {
        if (buffer != null && buffer.length == PCM_BYTE_LENGTH) {
            if (!pcmBufferPool.offer(buffer)) {
                mLog.debug("PCM buffer pool is full, discarding returned buffer.");
            }
        } else {
            mLog.warn("Attempted to return invalid PCM buffer to pool (null or incorrect size).");
        }
    }



    /**
     * Sends a 9-byte AMBE voice frame to the AMBE server for decoding.
     * The server is expected to send back 160 samples (320 bytes) of PCM audio.
     *
     * @param ambeFrame The 9-byte AMBE voice frame.
     * @param timestamp The timestamp associated with this frame (for logging/metadata if needed).
     * @throws IOException If an I/O error occurs during sending.
     */
    public void sendAmbeFrameForDecoding(byte[] ambeFrame, long timestamp) throws IOException {
        if (ambeFrame.length != AMBE_FRAME_LENGTH) {
            mLog.warn("AMBE frame is not " + AMBE_FRAME_LENGTH + " bytes long. Skipping.");
            return;
        }


        ByteBuffer buffer = ByteBuffer.wrap(ambeFrame);
        mChannel.send(buffer, mServerSocketAddress);
    }

    /**
     * Disposes of the AmbeClient, closing its channel and stopping its receive thread.
     */
    public void dispose() {
        mRunning = false;
        pendingControlResponses.forEach((key, future) -> future.completeExceptionally(new IOException("AmbeClient disposed")));
        pendingControlResponses.clear();
        pcmBufferPool.clear(); // Clear any buffers remaining in the pool

        if (mChannel != null) {
            try {
                mChannel.close();
            } catch (IOException e) {
                mLog.error("Error closing DatagramChannel.", e);
            }
        }
        if (mReceiveThread != null) {
            mReceiveThread.interrupt();
            try {
                mReceiveThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mLog.error("Interrupted while joining receive thread.", e);
            }
        }
        mLog.info("AMBE Client disposed (Port: " + (mChannel != null && mChannel.isOpen() ? mChannel.socket().getLocalPort() : "N/A") + ").");
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Returns the local port number to which this channel is bound.
     *
     * @return The local port number, or -1 if the channel is not bound.
     */
    public int getSocketLocalPort() {
        return mChannel != null && mChannel.isOpen() ? mChannel.socket().getLocalPort() : -1;
    }

    /**
     * Sets the in-use status of this client.
     *
     * @param inUse true if the client is currently in use, false otherwise.
     */
    public void setInUse(boolean inUse) {
        this.inUse.set(inUse);
    }

    /**
     * Checks if this client is currently in use by an audio module.
     *
     * @return true if in use, false otherwise.
     */
    public boolean isInUse() {
        return inUse.get();
    }
}