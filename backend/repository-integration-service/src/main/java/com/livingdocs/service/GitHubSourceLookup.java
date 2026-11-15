package com.livingdocs.service;

import com.livingdocs.client.GitHubSourceClient.CommitObservation;
import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.TargetKind;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Kiểm tra phạm vi kết nối và đọc dữ liệu nguồn mới từ GitHub. */
public interface GitHubSourceLookup {
    /** expectedConfigRevision chỉ được bỏ trống khi đọc cho luồng ảnh chụp. */
    Result readCurrent(SourceScope scope, UUID connectionId, TargetKind kind, String ref,
                       String expectedConfigRevision, boolean includeTree);

    enum Outcome {
        OBSERVED, INVALID_REQUEST, UNKNOWN_CONNECTION, CONNECTION_INACTIVE, CONFIG_CHANGED,
        UNKNOWN_REF, REF_ABSENT, SOURCE_UNAVAILABLE
    }

    /** Chỉ kết quả OBSERVED có commit đã đọc và đối chiếu. */
    @Getter
    @RequiredArgsConstructor
    final class Result {
        private final Outcome outcome;
        private final SourceConnection connection;
        private final CommitObservation commit;
    }
}
