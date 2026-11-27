package com.livingdocs.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class RepositoryGrantRequest {
    @NotNull private UUID workspaceMembershipId;
    @NotNull private UUID repositoryConnectionId;
    @NotBlank @Size(min = 10, max = 2000) private String provenance; // MÔ TẢ LÝ DO, NGUỒN GỐC YÊU CẦUD
}
