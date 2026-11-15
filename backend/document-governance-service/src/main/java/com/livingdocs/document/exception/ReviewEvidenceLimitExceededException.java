package com.livingdocs.document.exception;

public final class ReviewEvidenceLimitExceededException extends ReviewException {
    public ReviewEvidenceLimitExceededException() {
        super(ReviewErrorCode.EVIDENCE_LIMIT_EXCEEDED, "At most 100 evidence references are accepted.");
    }
}
