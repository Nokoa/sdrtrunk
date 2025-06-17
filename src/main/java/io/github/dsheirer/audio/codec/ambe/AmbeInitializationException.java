package io.github.dsheirer.audio.codec.ambe;

import java.io.IOException;

/**
 * Custom exception for errors during AMBE chip initialization.
 */
public class AmbeInitializationException extends IOException {
    public AmbeInitializationException(String message) {
        super(message);
    }

    public AmbeInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
