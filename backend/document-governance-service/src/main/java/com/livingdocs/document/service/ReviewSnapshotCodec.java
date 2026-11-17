package com.livingdocs.document.service;

import com.livingdocs.document.model.StaffReviewResult;

/** Use-case port for durable review receipts. The implementation owns snapshot representation. */
public interface ReviewSnapshotCodec {
    String encode(StaffReviewResult result);
    StaffReviewResult decode(String snapshot);
}
