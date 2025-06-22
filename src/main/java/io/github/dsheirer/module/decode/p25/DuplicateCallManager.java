package io.github.dsheirer.module.decode.p25;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages active calls across multiple sites of the same system to prevent duplicate playback.
 * The site with the strongest control channel RSSI wins the right to play the call.
 * This class is a thread-safe singleton.
 */
public class DuplicateCallManager {
    private static final Logger mLog = LoggerFactory.getLogger(DuplicateCallManager.class);
    private static final DuplicateCallManager INSTANCE = new DuplicateCallManager();

    // Hysteresis to prevent rapid switching between sites with similar signal strengths.
    private static final float RSSI_HYSTERESIS_DB = 3.0f;

    // A map to store the currently active call for a given System/Talkgroup pair.
    // Key: "SystemID_TalkgroupID", Value: ActiveCall object with site and RSSI info.
    private final Map<String, ActiveCall> mActiveCalls = new ConcurrentHashMap<>();

    private DuplicateCallManager() {}

    public static DuplicateCallManager getInstance() {
        return INSTANCE;
    }

    private String createCallKey(String systemId, long talkgroupId) {
        return systemId + "_" + talkgroupId;
    }

    /**
     * Attempts to acquire the "lock" for a call on a specific site.
     * The site with the highest RSSI (plus hysteresis) will acquire the lock.
     *
     * @param systemId      The system identifier.
     * @param talkgroupId   The talkgroup identifier.
     * @param siteId        The site identifier attempting to acquire the call.
     * @param rssi          The control channel RSSI of the site.
     * @return true if this site has acquired the call and should process it, false otherwise.
     */
    public boolean acquireCall(String systemId, long talkgroupId, String siteId, double rssi) {
        String callKey = createCallKey(systemId, talkgroupId);
        ActiveCall newCall = new ActiveCall(siteId, rssi);

        ActiveCall winningCall = mActiveCalls.compute(callKey, (key, currentHolder) -> {
            // Case 1: No current holder or the holder is stale. The new call wins.
            if (currentHolder == null || currentHolder.isStale()) {
                mLog.trace("No active call for {}, new call from site {} wins.", key, siteId);
                return newCall;
            }

            // Case 2: This site is already the winner. Refresh its entry with the latest RSSI and timestamp.
            if (currentHolder.getSiteId().equals(siteId)) {
                return newCall;
            }

            // Case 3: A different site is the winner. Compare RSSI to see if we should take over.
            if (rssi > currentHolder.getRssi() + RSSI_HYSTERESIS_DB) {
                mLog.trace("New call for {} from site {} (RSSI: {}) is stronger than existing from site {} (RSSI: {}). Switching.",
                        key, siteId, rssi, currentHolder.getSiteId(), currentHolder.getRssi());
                return newCall;
            }

            // Case 4: A different site is stronger. It remains the winner. Do not update its timestamp.
            mLog.trace("Existing call for {} from site {} (RSSI: {}) is stronger. Ignoring new call from site {} (RSSI: {}).",
                    key, currentHolder.getSiteId(), currentHolder.getRssi(), siteId, rssi);
            return currentHolder;
        });

        // The acquisition is successful if the call that's now in the map is the one we just tried to add.
        return newCall.equals(winningCall);
    }
    /**
     * Checks if the specified site is the current holder of the call lock without attempting to acquire it.
     *
     * @param systemId      The system identifier.
     * @param talkgroupId   The talkgroup identifier.
     * @param siteId        The site identifier to check.
     * @return true if the site currently holds the winning lock for the call, false otherwise.
     */
    public boolean isWinningSite(String systemId, long talkgroupId, String siteId) {
        String callKey = createCallKey(systemId, talkgroupId);
        ActiveCall winningCall = mActiveCalls.get(callKey);

        if (winningCall != null && !winningCall.isStale() && winningCall.getSiteId().equals(siteId)) {
            // The site is currently winning. Refresh the timestamp to prevent it from becoming stale.
            winningCall.timestamp = System.currentTimeMillis();
            return true;
        }

        return false;
    }

    public void releaseCall(String systemId, long talkgroupId, String siteId) {
        String callKey = createCallKey(systemId, talkgroupId);
        mLog.debug("Releasing call for {} on site {}", callKey, siteId);
        mActiveCalls.computeIfPresent(callKey, (key, existingCall) -> {
            if (existingCall.getSiteId().equals(siteId)) {
                return null;
            } else {
                return existingCall;
            }
        });
    }


    private static class ActiveCall {
        private final String siteId;
        private final double rssi;
        private  long timestamp;

        ActiveCall(String siteId, double rssi) {
            this.siteId = siteId;
            this.rssi = rssi;
            this.timestamp = System.currentTimeMillis();
        }

        public String getSiteId() { return siteId; }
        public double getRssi() { return rssi; }

        public boolean isStale() {
            return (System.currentTimeMillis() - timestamp) > 5000;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActiveCall that = (ActiveCall) o;
            return siteId.equals(that.siteId) && Double.compare(that.rssi, rssi) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(siteId, rssi);
        }
    }
}
