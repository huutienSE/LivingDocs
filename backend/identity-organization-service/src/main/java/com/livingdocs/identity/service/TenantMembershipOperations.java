package com.livingdocs.identity.service;

import com.livingdocs.identity.model.AuthorityCommands.AddTenantMember;
import com.livingdocs.identity.model.AuthorityCommands.AssignTenantOwner;
import com.livingdocs.identity.model.AuthorityCommands.RevokeAuthority;
import java.util.UUID;

public interface TenantMembershipOperations {
    UUID add(AddTenantMember command);
    UUID assignOwner(AssignTenantOwner command);
    void revokeMember(RevokeAuthority command, UUID actorIdentityId);
    void revokeOwner(RevokeAuthority command);
}
