package com.livingdocs.identity.service;

import com.livingdocs.identity.model.AuthorityCommands.RequestRepositoryGrant;
import com.livingdocs.identity.model.AuthorityCommands.RevokeAuthority;
import java.util.UUID;

public interface RepositoryGrantOperations {
    UUID requestPending(RequestRepositoryGrant command, UUID actorIdentityId);
    void revoke(RevokeAuthority command);
}
