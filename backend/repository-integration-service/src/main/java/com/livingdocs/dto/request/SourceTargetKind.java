package com.livingdocs.dto.request;

import com.livingdocs.model.TargetKind;

/** Tên wire contract khác tên BRANCH ở mô hình nghiệp vụ. */
public enum SourceTargetKind {
    TRACKED_BRANCH, PR;

    public TargetKind toDomain() {
        return this == TRACKED_BRANCH ? TargetKind.BRANCH : TargetKind.PR;
    }
}
