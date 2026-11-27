package com.livingdocs.document.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Schema(requiredProperties = {"type", "title", "status", "detail", "instance", "code", "correlationId"})
public class AuthoringProblem {
    private String type;
    private String title;
    private int status;
    private String detail;
    private String instance;
    private String code;
    private String correlationId;
}
