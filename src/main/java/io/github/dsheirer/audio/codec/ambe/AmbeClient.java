// AmbeClient.java
package io.github.dsheirer.audio.codec.ambe;

import io.github.dsheirer.sample.Listener;
import jmbe.iface.IAudioWithMetadata; // This dependency needs to be re-evaluated if JMBE is fully removed. For now, keep it to match existing structure.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private DatagramSocket mSocket;
    private InetAddress mServerAddress;
    private int mServerPort;
    private Listener<IAudioWithMetadata> mAudioDataListener; // Callback for decoded PCM audio
    private Thread mReceiveThread;
    private volatile boolean mRunning;

    // Queue for holding received PCM data before processing by the audio module
    private BlockingQueue<byte[]> mReceivedPcmQueue = new LinkedBlockingQueue<>();
    // Map to hold CompletableFuture for control responses, keyed by the expected field identifier
    private final Map<Byte, CompletableFuture<byte[]>> pendingControlResponses = new ConcurrentHashMap<>();
    private final long CONTROL_RESPONSE_TIMEOUT_MS = 2000; // 2 seconds timeout for control responses
    private AtomicBoolean inUse = new AtomicBoolean(false); // Flag to indicate if this client is currently in use
    private final int ID;
    public AmbeClient(String serverAddress, int serverPort, int id) throws IOException {
        mServerAddress = InetAddress.getByName(serverAddress);
        mServerPort = serverPort;
        mSocket = new DatagramSocket();
        // Set a receive buffer size, though the default is usually sufficient
        // mSocket.setReceiveBufferSize(65535); // Example: Larger buffer if needed
        // No general socket timeout here, as specific receives use CompletableFuture with timeout
        mRunning = true;
        ID = id;
        startReceiveThread();
    }

    private void startReceiveThread() {
        mReceiveThread = new Thread(() -> {
            byte[] receiveBuffer = new byte[512]; // Max expected packet size (e.g., control packet + voice data)
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            while (mRunning) {
                try {
                    mSocket.receive(receivePacket);
                    byte[] receivedData = Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength());
                    processReceivedPacket(receivedData);
                } catch (IOException e) {
                    if (mRunning) { // Only log if not intentionally stopped
                        mLog.warn("AMBE Client receive error on timeslot " + mSocket.getLocalPort() + ": " + e.getMessage());
                    }
                }
            }
        }, "AmbeClient-ReceiveThread-" + mSocket.getLocalPort());
        mReceiveThread.start();
    }

    public int getID() {
        return ID;
    }

    private void processReceivedPacket(byte[] receivedData) {
        if (receivedData.length < 4 || receivedData[0] != START_BYTE) {
            mLog.warn("Received invalid packet (start byte or length issue): " + bytesToHex(receivedData));
            return;
        }

        byte packetType = receivedData[3];
        byte fieldIdentifier = receivedData.length > 4 ? receivedData[4] : 0; // Get field identifier if packet is long enough

        if (packetType == CONTROL_TYPE) {
            // Check if there's a pending future for this control response
            CompletableFuture<byte[]> future = pendingControlResponses.remove(fieldIdentifier); // Remove immediately to prevent duplicate completion
            if (future != null) {
                future.complete(receivedData);
//                mLog.debug("Completed future for control response (Field ID: 0x" + String.format("%02X", fieldIdentifier) + "): " + bytesToHex(receivedData));
            } else {
                mLog.debug("Received unrequested control packet (Field ID: 0x" + String.format("%02X", fieldIdentifier) + "): " + bytesToHex(receivedData));
            }
        } else if (packetType == SPEECH_TYPE) {
            // Ensure the speech packet has the expected structure and length
            if (receivedData.length >= 6 + PCM_BYTE_LENGTH &&
                    receivedData[4] == (byte) 0x00 && receivedData[5] == (byte) 0xA0) { // SPEECHD ID 0x00, NumSamples 0xA0 (160)
                byte[] pcmData = Arrays.copyOfRange(receivedData, 6, 6 + PCM_BYTE_LENGTH);
                try {
                    mReceivedPcmQueue.put(pcmData);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mLog.error("Interrupted while adding PCM data to queue.", e);
                }
            } else {
                mLog.warn("Received malformed Speech Packet (Length: " + receivedData.length + ", Expected: " + (6 + PCM_BYTE_LENGTH) + ", Data: " + bytesToHex(receivedData) + ")");
            }
        } else {
            mLog.warn("Received unknown packet type: 0x" + String.format("%02X", packetType) + " Data: " + bytesToHex(receivedData));
        }
    }

    public void setAudioDataListener(Listener<IAudioWithMetadata> listener) {
        mAudioDataListener = listener;
    }

    /**
     * Retrieves a decoded PCM audio frame from the client's internal buffer.
     * This method will block for up to the specified timeout if no data is available.
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout argument.
     * @return A byte array containing 16-bit signed PCM audio, or null if the timeout expires.
     * @throws InterruptedException If the current thread is interrupted while waiting.
     */
    public byte[] getDecodedAudioFrame(long timeout, TimeUnit unit) throws InterruptedException {
        return mReceivedPcmQueue.poll(timeout, unit);
    }

    /**
     * Initializes the AMBE chip by sending a sequence of configuration packets.
     * This method waits for a response after each packet.
     * @throws IOException If an I/O error occurs during communication.
     * @throws InterruptedException If the current thread is interrupted while waiting for a response.
     */
    public void initializeAmbeChip() throws IOException, InterruptedException {
        // Step 1: Send PKT_PRODID request
        byte[] prodIdRequest = {
                START_BYTE,
                (byte) 0x00, (byte) 0x01, // LENGTH MSB, LSB (length of field data = 1 byte for PRODID)
                CONTROL_TYPE,
                PKT_PRODID
        };
        sendPacketAndReceiveResponse(prodIdRequest, "Product ID Request");

        // Step 2: Send PKT_INIT for initialization (Encoder, Decoder, and Echo Canceller)
        // Control Field Data: 0x07 (bit 0=Encoder Initialized, bit 1=Decoder Initialized, bit 2=Echo Canceller Initialized)
        byte[] initPacket = {
                START_BYTE,
                (byte) 0x00, (byte) 0x02, // LENGTH MSB, LSB (length of field data = 2 bytes: PKT_INIT + 1 byte data)
                CONTROL_TYPE,
                PKT_INIT,
                (byte) 0x07 // Control Field Data (Encoder Initialized, Decoder Initialized, Echo Canceller Initialized)
        };
        sendPacketAndReceiveResponse(initPacket, "Initialization (PKT_INIT)");

        // Step 3: Configure Vocoder Rate (DMR/NXDN/P25 Phase 2: Rate Index #33)
        // Control Field Data: 0x21 (Rate Index #33)
        byte[] rateConfigPacket = {
                START_BYTE,
                (byte) 0x00, (byte) 0x02, // LENGTH MSB, LSB (length of field data = 2 bytes: PKT_RATET + 1 byte data)
                CONTROL_TYPE,
                PKT_RATET,
                (byte) 0x21 // Control Field Data (Rate Index #33)
        };
        sendPacketAndReceiveResponse(rateConfigPacket, "Vocoder Rate Configuration (PKT_RATET)");

        // Step 4: Set Output Gain (e.g., +10 dB)
        // Control Field Data: Input Gain (0 dB), Output Gain (+10 dB = 0x0A)
        byte[] gainConfigPacket = {
                START_BYTE,
                (byte) 0x00, (byte) 0x03, // LENGTH MSB, LSB (length of field data = 3 bytes: PKT_GAIN + 2 bytes data)
                CONTROL_TYPE,
                PKT_GAIN,
                (byte) 0x00, // Input Gain (0 dB)
                (byte) 0x0a  // Output Gain (+10 dB)
        };
        sendPacketAndReceiveResponse(gainConfigPacket, "Output Gain Configuration (PKT_GAIN)");
        mLog.info("AMBE chip initialization complete for port: " + mSocket.getLocalPort());
    }

    /**
     * Sends a UDP packet to the AMBE server and waits for a corresponding control response.
     * @param packet The byte array representing the packet to send.
     * @param description A description of the packet for logging.
     * @throws IOException If an I/O error occurs.
     * @throws InterruptedException If the current thread is interrupted.
     * @throws TimeoutException If no response is received within the timeout period.
     * @throws ExecutionException If the computation threw an exception.
     */
    private void sendPacketAndReceiveResponse(byte[] packet, String description) throws IOException, InterruptedException {
        // The field identifier is typically the 5th byte (index 4) for control packets like PRODID, INIT, RATET, GAIN.
        // This acts as a correlation ID for the response.
        if (packet.length < 5) {
            throw new IllegalArgumentException("Packet too short to extract field identifier for response correlation.");
        }
        byte fieldIdentifier = packet[4];

        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        pendingControlResponses.put(fieldIdentifier, responseFuture); // Store the future keyed by field ID

//        mLog.debug("Sending " + description + ": " + bytesToHex(packet));
        DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, mServerAddress, mServerPort);
        mSocket.send(sendPacket);

        try {
            // Wait for the response for up to the defined timeout
            byte[] receivedData = responseFuture.get(CONTROL_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
//            mLog.debug("Received response for " + description + ": " + bytesToHex(receivedData));

            // Basic validation of the received control response
            if (receivedData.length < 4 || receivedData[0] != START_BYTE || receivedData[3] != CONTROL_TYPE) {
                mLog.error("Unexpected or malformed control response for " + description + ": " + bytesToHex(receivedData));
                throw new IOException("Unexpected response from AMBE chip for " + description);
            }
        } catch (TimeoutException e) {
            pendingControlResponses.remove(fieldIdentifier); // Clean up the future if timed out
            mLog.error("No response received for " + description + " within " + CONTROL_RESPONSE_TIMEOUT_MS + "ms.", e);
            throw new IOException("Timeout waiting for response for " + description, e);
        } catch (ExecutionException e) {
            // The exception thrown by the CompletableFuture's computation (e.g., if processReceivedPacket completes exceptionally)
            pendingControlResponses.remove(fieldIdentifier); // Clean up the future
            mLog.error("Error during response retrieval for " + description + ": " + e.getCause().getMessage(), e.getCause());
            throw new IOException("Error during response retrieval for " + description, e.getCause());
        } finally {
            // Ensure the future is removed in case of other unexpected errors before .get() is called
            pendingControlResponses.remove(fieldIdentifier);
        }
    }

    /**
     * Sends a 9-byte AMBE voice frame to the AMBE server for decoding.
     * The server is expected to send back 160 samples (320 bytes) of PCM audio.
     * @param ambeFrame The 9-byte AMBE voice frame.
     * @param timestamp The timestamp associated with this frame (for logging/metadata if needed).
     * @throws IOException If an I/O error occurs during sending.
     */
    public void sendAmbeFrameForDecoding(byte[] ambeFrame, long timestamp) throws IOException {
        if (ambeFrame.length != AMBE_FRAME_LENGTH) {
            mLog.warn("AMBE frame is not " + AMBE_FRAME_LENGTH + " bytes long. Skipping.");
            return;
        }

        // Channel Packet structure based on AMBE-3000F manual:
        // START_BYTE (1) | LENGTH (2) | TYPE (1) | PKT_CHANNEL0 (1) | CHAND (1) | NumBits (1) | AMBE_DATA (9) | SAMPLES (1) | NumSamples (1)
        // Total bytes = 1 + 2 + 1 + 1 + 1 + 1 + 9 + 1 + 1 = 18 bytes.
        byte[] ambeVoiceFramePacket = new byte[18];

        // Packet Header
        ambeVoiceFramePacket[0] = START_BYTE;     // START_BYTE (0x61)
        ambeVoiceFramePacket[1] = (byte) 0x00;    // LENGTH MSB (total fields length = 14 bytes)
        ambeVoiceFramePacket[2] = (byte) 0x0E;    // LENGTH LSB (0x0E = 14)
        ambeVoiceFramePacket[3] = CHANNEL_TYPE;   // TYPE (0x01 for Channel Packet)

        // Field 1: PKT_CHANNEL0 (0x40) - indicates first field in channel packet.
        ambeVoiceFramePacket[4] = PKT_CHANNEL0;   // FIELD_IDENTIFIER (0x40)

        // Field 2: CHAND (0x01) - Channel Data field.
        ambeVoiceFramePacket[5] = CHAND;          // FIELD_IDENTIFIER (0x01)
        ambeVoiceFramePacket[6] = (byte) 0x48;    // Number of Bits (72 bits, 0x48 in hex) for the AMBE frame

        // Copy the 9 bytes of actual AMBE data into the packet
        System.arraycopy(ambeFrame, 0, ambeVoiceFramePacket, 7, AMBE_FRAME_LENGTH);

        // Field 3: SAMPLES (0x03) - Number of PCM Samples to be returned.
        ambeVoiceFramePacket[16] = SAMPLES;       // FIELD_IDENTIFIER (0x03)
        ambeVoiceFramePacket[17] = (byte) 0xA0;    // Number of Samples (160 samples, 0xA0 in hex)

        DatagramPacket sendPacket = new DatagramPacket(ambeVoiceFramePacket, ambeVoiceFramePacket.length, mServerAddress, mServerPort);
        mSocket.send(sendPacket);
    }

    /**
     * Disposes of the AmbeClient, closing its socket and stopping its receive thread.
     */
    public void dispose() {
        mRunning = false;
        // Complete any pending futures exceptionally to unblock waiting threads
        pendingControlResponses.forEach((key, future) -> future.completeExceptionally(new IOException("AmbeClient disposed")));
        pendingControlResponses.clear();

        if (mSocket != null) {
            mSocket.close();
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
        mLog.info("AMBE Client disposed (Port: " + (mSocket != null ? mSocket.getLocalPort() : "N/A") + ").");
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Returns the local port number to which this socket is bound.
     * @return The local port number, or -1 if the socket is not bound.
     */
    public int getSocketLocalPort() {
        return mSocket != null ? mSocket.getLocalPort() : -1;
    }

    /**
     * Sets the in-use status of this client.
     * @param inUse true if the client is currently in use, false otherwise.
     */
    public void setInUse(boolean inUse) {
        this.inUse.set(inUse);
    }

    /**
     * Checks if this client is currently in use by an audio module.
     * @return true if in use, false otherwise.
     */
    public boolean isInUse() {
        return inUse.get();
    }
}
