package com.livingdocs.identity.dto.response;

import com.livingdocs.identity.entity.RepositoryGrant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Getter
public class RepositoryGrantResponse {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) private final UUID grantId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) private final UUID tenantId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) private final UUID workspaceId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) private final UUID workspaceMembershipId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) private final UUID repositoryConnectionId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) private final String status;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) private final String verificationStatus;
    @Schema(nullable = true) private final String verificationSource;
    @Schema(nullable = true) private final String effectiveReason;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean effective;
    @Schema(nullable = true)
    private final Long verifiedBindingRevision;
    @Schema(nullable = true)
    private final Instant verifiedAt, endedAt;

    public RepositoryGrantResponse(RepositoryGrant grant, boolean effective, String reason) {
        grantId = grant.getGrantId(); tenantId = grant.getTenantId(); workspaceId = grant.getWorkspaceId();
        workspaceMembershipId = grant.getWorkspaceMembershipId(); // thành viên được cấp quyền
        repositoryConnectionId = grant.getRepositoryConnectionId(); // repo connect được yêu cầu
        status = grant.getStatus(); verificationStatus = grant.getVerificationStatus(); // ACGIVE/PEDING; ACTIVE/REJECT,.....
        verificationSource = grant.getVerificationSource(); verifiedBindingRevision = grant.getVerifiedBindingRevision();
        verifiedAt = grant.getVerifiedAt(); endedAt = grant.getEndedAt();
        this.effective = effective; effectiveReason = reason;
    }
}
