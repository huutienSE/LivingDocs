package com.livingdocs.identity.service;

import com.livingdocs.identity.model.AuthorityCommands.AddWorkspaceMember;
import com.livingdocs.identity.model.AuthorityCommands.AssignWorkspaceRole;
import com.livingdocs.identity.model.AuthorityCommands.CreateWorkspace;
import com.livingdocs.identity.model.AuthorityCommands.RevokeAuthority;
import java.util.UUID;

public interface WorkspaceAuthorityOperations {
    void create(CreateWorkspace command);
    UUID addMember(AddWorkspaceMember command);
    UUID assignRole(AssignWorkspaceRole command);
    void revokeMember(RevokeAuthority command);
    void revokeRole(RevokeAuthority command);
}
