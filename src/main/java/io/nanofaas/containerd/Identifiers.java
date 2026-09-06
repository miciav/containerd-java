package io.nanofaas.containerd;

import java.util.regex.Pattern;

/**
 * Validation of containerd identifiers (container ids, snapshot keys, exec ids).
 *
 * <p>Mirrors containerd's own rule: alphanumeric runs joined by single {@code .}, {@code _} or
 * {@code -} separators, at most 76 characters. An identifier may neither start nor end with a
 * separator, so {@code my-container} is valid while {@code -x}, {@code x-} and {@code x..y} are
 * not — rejecting them here rather than letting containerd reject them a round trip later.
 */
public final class Identifiers {

    private static final int MAX_LENGTH = 76;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*");

    private Identifiers() {
    }

    /** Returns {@code id} if it is a valid containerd identifier, else throws. */
    public static String requireValid(String id) {
        if (id == null || id.length() > MAX_LENGTH || !IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid containerd identifier: " + id);
        }
        return id;
    }
}
