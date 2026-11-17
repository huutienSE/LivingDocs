package com.livingdocs.document.exception;

public final class ReviewReplaySnapshotUnavailableException extends ReviewException {
    public ReviewReplaySnapshotUnavailableException() {
        super(ReviewErrorCode.REPLAY_SNAPSHOT_UNAVAILABLE, "Historical response cannot be reconstructed from immutable audit.");
    }
}
