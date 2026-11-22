package com.livingdocs.identity.model;

import java.util.UUID;

/** Constructed only after binding an authenticated principal to an active Identity. */
public record VerifiedActor(UUID identityId, String authenticationSource, String principalName) {}
