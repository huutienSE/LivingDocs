package com.livingdocs.identity.service;

import com.livingdocs.identity.model.AuthorityCommands.CreateTenantWithInitialOwner;

public interface TenantProvisioningOperations {
    void create(CreateTenantWithInitialOwner command);
}
