package com.livingdocs.document.dto.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.livingdocs.document.model.SuccessCode;
import com.livingdocs.document.model.StaffReviewResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@JsonPropertyOrder({"code", "message", "data"})
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class SuccessResponse<T> {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String code;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String message;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final T data;

    // Deserialization must retain the original persisted envelope on idempotent replay.
    @JsonCreator
    private SuccessResponse(@JsonProperty("code") String code, @JsonProperty("message") String message,
                            @JsonProperty("data") T data) {
        this.code = Objects.requireNonNull(code);
        this.message = Objects.requireNonNull(message);
        this.data = Objects.requireNonNull(data);
    }

    public static <T> SuccessResponse<T> of(StaffReviewResult.Receipt receipt, T data) {
        return new SuccessResponse<>(receipt.outcomeCode(), receipt.description(), data);
    }

    public static <T> SuccessResponse<T> of(SuccessCode code, T data) {
        return new SuccessResponse<>(code.getWireCode(), code.getDefaultMessage(), data);
    }

    /** Retains the outcome stored in a durable receipt when an operation is replayed. */
    public static <T> SuccessResponse<T> of(String code, String message, T data) {
        return new SuccessResponse<>(code, message, data);
    }
}
