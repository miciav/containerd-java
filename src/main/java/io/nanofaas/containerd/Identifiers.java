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
    // requireValid checks the length first and || short-circuits, so this never sees an input
    // longer than MAX_LENGTH and cannot be driven into deep backtracking.
    @SuppressWarnings("java:S5998")
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*");

    private Identifiers() {
    }

    /**
     * Checks an identifier against containerd's rule.
     *
     * @param id identifier to validate
     * @return {@code id} unchanged when it is valid
     * @throws IllegalArgumentException if it is null, empty, too long, or malformed
     */
    public static String requireValid(String id) {
        // Length first, deliberately: || short-circuits, so the pattern's nested quantifiers
        // never see an input longer than MAX_LENGTH and cannot be driven into deep backtracking.
        if (id == null || id.length() > MAX_LENGTH || !IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid containerd identifier: " + id);
        }
        return id;
    }
}
