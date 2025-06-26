// AmbeVocoderAudioModule.java
package io.github.dsheirer.audio.codec.ambe;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.squelch.ISquelchStateListener;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList; // Needed for mQueuedAmbeFrames
import java.util.List; // Needed for mQueuedAmbeFrames

/**
 * Abstract base class for Audio Modules that utilize an external AMBE vocoder chip via UDP.
 * This class manages the AmbeClient instance and handles the sending and receiving of AMBE frames.
 */
public abstract class AmbeVocoderAudioModule extends AbstractAudioModule implements Listener<IMessage>, IMessageListener, ISquelchStateListener {

    private static final Logger mLog = LoggerFactory.getLogger(AmbeVocoderAudioModule.class);

    // Define AMBE server parameters (these could be moved to UserPreferences if needed)
    private static final String AMBE_SERVER_ADDRESS = "127.0.0.1"; // Default localhost for the emulator
    private static final int AMBE_SERVER_PORT = 25565; // Default port used by md380-emu AMBEServer
    private static final int AMBE_CLIENT_POOL_SIZE = 40;
    protected final UserPreferences mUserPreferences;
    protected AmbeClient mAmbeClient;
    protected List<byte[]> mQueuedAmbeFrames = new ArrayList<>(); // To queue frames before encryption state is known

    // New logging counters
    private long framesSentToAmbeClient = 0;
    private long pcmFramesReceivedFromAmbeClient = 0;
    private long pcmSamplesConverted = 0;

    private static AmbeClientPool sAmbeClientPool;



    /**
     * Constructs an instance.
     *
     * @param userPreferences for AMBE client configuration.
     * @param aliasList for audio.
     * @param timeslot for this audio module.
     */
    public AmbeVocoderAudioModule(UserPreferences userPreferences, AliasList aliasList, int timeslot) {
        super(aliasList, timeslot, DEFAULT_SEGMENT_AUDIO_SAMPLE_LENGTH);
        mUserPreferences = userPreferences;
        MyEventBus.getGlobalEventBus().register(this);

        // Initialize the pool if it hasn't been already
        if (sAmbeClientPool == null) {
            try {
                sAmbeClientPool = AmbeClientPool.getInstance(AMBE_CLIENT_POOL_SIZE, AMBE_SERVER_ADDRESS, AMBE_SERVER_PORT);
            } catch (IOException | InterruptedException e) {
                mLog.error("Failed to initialize AMBE Client Pool. Vocoder functionality may be unavailable.", e);
            }
        }
    }

    /**
     * Acquires an AmbeClient from the pool for this module's use.
     */
    private void acquireAmbeClient() {
        if (sAmbeClientPool != null) {
            try {
                // Try to get a client from the pool with a timeout
                mAmbeClient = sAmbeClientPool.getClient(100, TimeUnit.MILLISECONDS);
                if (mAmbeClient != null) {
//                    mLog.info("Acquired AmbeClient for timeslot: " + getTimeslot() + " from pool (Local Port: " + mAmbeClient.getSocketLocalPort() + ").");
                    // Reset counters for the new session
                    framesSentToAmbeClient = 0;
                    pcmFramesReceivedFromAmbeClient = 0;
                    pcmSamplesConverted = 0;
                } else {
                    mLog.warn("Failed to acquire AmbeClient from pool for timeslot: " + getTimeslot() + ". Vocoder functionality will be unavailable for this session.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mLog.error("Interrupted while acquiring AmbeClient from pool for timeslot: " + getTimeslot(), e);
                mAmbeClient = null;
            }
        } else {
            mLog.warn("AMBE Client Pool is not initialized. Cannot acquire client for timeslot: " + getTimeslot());
            mAmbeClient = null;
        }
    }

    /**
     * Releases the AmbeClient back to the pool.
     */
    private void releaseAmbeClient() {
        if (mAmbeClient != null && sAmbeClientPool != null) {
            sAmbeClientPool.returnClient(mAmbeClient);
//            mLog.info("Released AmbeClient for timeslot: " + getTimeslot() + " back to pool (Local Port: " + mAmbeClient.getSocketLocalPort() + "). Sent: " + framesSentToAmbeClient + ", Received PCM: " + pcmFramesReceivedFromAmbeClient + ", Samples Converted: " + pcmSamplesConverted);
            mAmbeClient = null; // Clear local reference
        }
    }




    @Override
    public void dispose() {
        super.dispose();
        MyEventBus.getGlobalEventBus().unregister(this);
        releaseAmbeClient(); // Release client when module is disposed
//        mLog.info("AmbeVocoderAudioModule disposed for timeslot: " + getTimeslot());
    }

    @Override
    public void reset() {
        mQueuedAmbeFrames.clear(); // Clear any queued frames on reset

        // The AmbeClient is typically released and re-acquired on start,
        // so no explicit reset on the client itself here.
        // It will be re-initialized when it's acquired from the pool for a new session.
        framesSentToAmbeClient = 0;
        pcmFramesReceivedFromAmbeClient = 0;
        pcmSamplesConverted = 0;
    }

    @Override
    public void start() {
        acquireAmbeClient(); // Acquire client when the module starts a new session
    }

    @Override
    public Listener<IMessage> getMessageListener() {
        return this;
    }

    /**
     * Processes AMBE audio frames by sending them to the AmbeClient for decoding.
     * This method also attempts to retrieve decoded PCM audio from the client.
     *
     * @param ambeFrame The AMBE voice frame (9 bytes).
     * @param timestamp The timestamp of the frame.
     */
    protected void processAmbeFrame(byte[] ambeFrame, long timestamp) {
        if (mAmbeClient != null && mAmbeClient.isInUse()) { // Ensure client is acquired and in use
            try {
                mAmbeClient.sendAmbeFrameForDecoding(ambeFrame, timestamp);
                framesSentToAmbeClient++;
                if (mLog.isTraceEnabled()) {
//                    mLog.trace("Sent AMBE frame to client (ID {} Timeslot: {}, Sent Count: {}, Timestamp: {}) - Frame: {}",mAmbeClient.getID(),
//                            getTimeslot(), framesSentToAmbeClient, timestamp, HexFormat.of().formatHex(ambeFrame));
                }


                // Attempt to retrieve decoded PCM audio from AmbeClient and add to audio segment
                // Poll with a short timeout to avoid blocking indefinitely, typically 20ms or slightly more than frame duration
                byte[] pcmData = mAmbeClient.getDecodedAudioFrame(100, TimeUnit.MILLISECONDS); // Increased poll timeout slightly
                if (pcmData != null) {
                    pcmFramesReceivedFromAmbeClient++;
                    float[] floatPcmData = convertFromSigned16BitSamples(pcmData);
                    pcmSamplesConverted += floatPcmData.length;
                    addAudio(floatPcmData);
                    if (mLog.isTraceEnabled()) {
//                        mLog.trace("Received PCM from client (Timeslot: {}, Received Count: {}, Samples: {}, Timestamp: {}) - PCM (first 10 bytes): {}",
//                                getTimeslot(), pcmFramesReceivedFromAmbeClient, floatPcmData.length, System.currentTimeMillis(), bytesToHex(Arrays.copyOf(pcmData, Math.min(pcmData.length, 10))));
                    }
                    mAmbeClient.returnPcmBufferToPool(pcmData); // <--- ADD OR ENSURE THIS LINE IS CALLED

                } else {
                    if (mLog.isTraceEnabled()) {
                        mLog.trace("No PCM received from client for last AMBE frame (ID: {}, ClientsAvialable: {}, Timeslot: {}, Sent Count: {}, Timestamp: {}, AmbeClient: {})",mAmbeClient.getID(), sAmbeClientPool.getAvailableClients(),
                                getTimeslot(), framesSentToAmbeClient, System.currentTimeMillis(), mAmbeClient!= null ? mAmbeClient.isInUse() : false);
                    }
                }
            } catch (IOException e) {
                mLog.error("Failed to send AMBE frame or receive PCM from client for timeslot " + getTimeslot() + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mLog.error("Interrupted while waiting for decoded PCM audio from AmbeClient.", e);
            }
        } else {
            mLog.warn("AmbeClient not acquired or not in use for timeslot: " + getTimeslot() + ". Cannot process AMBE frame. Frame skipped.");
        }
    }




    public static float[] convertFromSigned16BitSamples(byte[] bytes)
    {
        return convertFromSigned16BitSamples(ByteBuffer.wrap(bytes));
    }


    public static float[] convertFromSigned16BitSamples(ByteBuffer buffer)
    {
        ShortBuffer byteBuffer = buffer.order(ByteOrder.BIG_ENDIAN).asShortBuffer();

        float[] samples = new float[buffer.limit() / 2];

        for(int x = 0; x < samples.length; x++)
        {
            samples[x] = (float)byteBuffer.get() / (float)Short.MAX_VALUE;
        }

        return samples;
    }

}
