package com.livingdocs.document.controller;

import com.livingdocs.document.domain.ReviewException;
import java.util.UUID;
import java.util.regex.Pattern;

public final class VersionEtags {
    private static final Pattern ETAG = Pattern.compile("\"([0-9a-fA-F-]{36}):revision-(0|[1-9][0-9]*)\"");
    private VersionEtags() {}

    public static String format(UUID version, long revision) {
        return "\"" + version + ":revision-" + revision + "\"";
    }

    public static long require(UUID version, String header) {
        if (header == null || header.isBlank()) {
            throw new ReviewException(428, "PRECONDITION_REQUIRED", "If-Match is required.");
        }
        var match = ETAG.matcher(header.trim());
        if (!match.matches()) throw new ReviewException(400, "INVALID_IF_MATCH", "Use the exact strong ETag returned for this version.");
        try {
            if (!UUID.fromString(match.group(1)).equals(version)) throw ReviewException.revisionConflict();
            return Long.parseLong(match.group(2));
        } catch (IllegalArgumentException e) {
            throw new ReviewException(400, "INVALID_IF_MATCH", "Invalid version ETag.");
        }
    }
}
