package com.livingdocs.dto.response;

import com.livingdocs.model.SuccessCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class SuccessResponse<T> {
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
