package com.livingdocs.identity.dto.response;

import com.livingdocs.identity.model.SuccessCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class SuccessResponse<T> {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String code;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String message;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final T data;

    public static <T> SuccessResponse<T> of(SuccessCode code, T data) {
        return new SuccessResponse<>(code.getWireCode(), code.getDefaultMessage(), data);
    }
}
