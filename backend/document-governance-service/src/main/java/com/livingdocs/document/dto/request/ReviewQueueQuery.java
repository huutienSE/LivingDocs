package com.livingdocs.document.dto.request;

import com.livingdocs.document.model.DriftSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

/** All filters are ANDed. Defaults and resource budgets belong to server configuration. */
@Getter @Setter
public class ReviewQueueQuery {
    public enum SortField { REPOSITORY, TEMPLATE, DRIFT_SEVERITY, SUBMISSION_DATE }
    public enum Direction { ASC, DESC }
    @Min(0) @Schema(defaultValue = "0", minimum = "0") private int page;
    @Min(1) @Schema(minimum = "1", description = "Defaults and maximum come from server configuration") private Integer size;
    private UUID workspaceId;
    private UUID repositoryConnectionId;
    private UUID templateId;
    private DriftSeverity driftSeverity;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant submittedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant submittedTo;
    @Schema(defaultValue = "SUBMISSION_DATE") private SortField sort = SortField.SUBMISSION_DATE;
    @Schema(defaultValue = "DESC") private Direction direction = Direction.DESC;
}
