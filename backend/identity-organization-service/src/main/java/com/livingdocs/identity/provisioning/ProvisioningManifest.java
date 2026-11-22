package com.livingdocs.identity.provisioning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

// nó là một DTO để chuyển thành một obj
// đầu vào của một provisioning operation

/** One explicit, auditable operator operation; absent fields are rejected by use case. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = false)
public class ProvisioningManifest {
    private UUID operationId;
    private UUID tenantId;
    private UUID operatorIdentityId;
    private String type; // REATE_TENANT, ASSIGN_WORKSPACE_ROLE, REVOKE_TENANT_MEMBER
    private String reason;
    private UUID identityId;
    private UUID workspaceId;
    private UUID workspaceMembershipId;
    private UUID targetId;
    private UUID repositoryConnectionId;
    private String name;
    private String roleCode;
    private String provenance;
}
