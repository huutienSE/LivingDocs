package com.livingdocs.document.mapper;

import com.livingdocs.document.dto.response.ReviewResponse;
import com.livingdocs.document.dto.response.SuccessResponse;
import com.livingdocs.document.exception.ReviewSnapshotSerializationException;
import com.livingdocs.document.model.StaffReviewResult;
import com.livingdocs.document.service.ReviewSnapshotCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON adapter for current envelopes and immutable pre-envelope snapshots. */
@Component
@RequiredArgsConstructor
public class JacksonReviewSnapshotCodec implements ReviewSnapshotCodec {
    private final JsonMapper json;
    private final StaffDecisionMapper mapper;

    @Override
    public String encode(StaffReviewResult result) {
        try {
            return json.writeValueAsString(SuccessResponse.of(result.receipt(), mapper.toResponse(result)));
        } catch (RuntimeException e) {
            throw new ReviewSnapshotSerializationException(e);
        }
    }

    @Override
    public StaffReviewResult decode(String snapshot) {
        try {
            if (json.readTree(snapshot).has("data")) {
                var stored = json.readValue(snapshot, new TypeReference<SuccessResponse<ReviewResponse>>() {});
                return mapper.toResult(stored.getData(),
                        new StaffReviewResult.Receipt(stored.getCode(), stored.getMessage()));
            }
            return mapper.toResult(json.readValue(snapshot, ReviewResponse.class), StaffReviewResult.Receipt.recorded());
        } catch (RuntimeException e) {
            throw new ReviewSnapshotSerializationException(e);
        }
    }
}
