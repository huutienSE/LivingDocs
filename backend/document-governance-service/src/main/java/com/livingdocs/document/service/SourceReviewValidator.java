package com.livingdocs.document.service;

import com.livingdocs.document.domain.DocumentVersion;
import com.livingdocs.document.domain.SourceReviewValidation;

/**
 * S04 integration port; this is not a new S02 HTTP wire contract.
 * An adapter must authenticate to S02, resolve the exact target/config binding and perform a fresh
 * authoritative source check on every call, with bounded timeouts and an upstream-owned expiry.
 * It must not infer currentness from the version SHA, browser evidence or a cached GitHub head.
 * Until that adapter is supplied, repository-backed PASS fails closed.
 */
public interface SourceReviewValidator {
    SourceReviewValidation validate(DocumentVersion version);
}
