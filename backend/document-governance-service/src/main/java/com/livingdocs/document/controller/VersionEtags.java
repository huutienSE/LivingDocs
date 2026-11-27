package com.livingdocs.document.controller;

import com.livingdocs.document.exception.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

public final class VersionEtags {
    private static final Pattern ETAG = Pattern.compile("\"([0-9a-fA-F-]{36}):revision-(0|[1-9][0-9]*)(?::context-[0-9a-f]{64})?\"");
    private VersionEtags() {}

    public static String format(UUID version, long revision) {
        return "\"" + version + ":revision-" + revision + "\"";
    }

    /** Strong validator for the complete JSON context response, including the success envelope. */
    public static String formatContext(UUID version, long revision, byte[] representation) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(representation));
            return "\"" + version + ":revision-" + revision + ":context-" + hash + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
        }
    }

    public static long require(UUID version, String header) {
        if (header == null || header.isBlank()) {
            throw new ReviewRequestException(ReviewErrorCode.PRECONDITION_REQUIRED, "If-Match is required.");
        }
        var match = ETAG.matcher(header.trim());
        if (!match.matches()) throw new ReviewRequestException(ReviewErrorCode.INVALID_IF_MATCH, "Use the exact strong ETag returned for this version.");
        try {
            if (!UUID.fromString(match.group(1)).equals(version)) throw new ReviewRevisionConflictException();
            // Context hashes validate GET representations. Mutations still guard the embedded
            // version revision; the digest is not a new template/baseline write precondition.
            return Long.parseLong(match.group(2));
        } catch (IllegalArgumentException e) {
            throw new ReviewRequestException(ReviewErrorCode.INVALID_IF_MATCH, "Invalid version ETag.");
        }
    }
}
