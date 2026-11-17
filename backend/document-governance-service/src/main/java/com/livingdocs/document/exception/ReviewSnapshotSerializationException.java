package com.livingdocs.document.exception;

/** Snapshot encoding/decoding failed; never expose serialization details to the caller. */
public final class ReviewSnapshotSerializationException extends ReviewException {
    public ReviewSnapshotSerializationException(Throwable cause) {
        super(ReviewErrorCode.PERSISTENCE_ERROR,
                "Review could not be stored. No changes were committed.", cause);
    }
}
