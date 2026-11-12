package com.livingdocs.document.application.service;

import com.livingdocs.document.domain.model.*;
import com.livingdocs.document.domain.ReviewException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SourceReviewGuard {
    private final Optional<SourceReviewValidator> validator;

    public SourceReviewGuard(Optional<SourceReviewValidator> validator) { this.validator = validator; }

    public SourceReviewValidation requireCurrent(DocumentVersion version) {
        if (version.repositoryConnectionId() == null) return null;
        var adapter = validator.orElseThrow(SourceReviewValidation::unavailable);
        SourceReviewValidation validation;
        try {
            validation = adapter.validate(version);
        } catch (ReviewException e) {
            throw e;
        } catch (RuntimeException e) {
            // Do not expose upstream URLs, credentials or response bodies in a Problem.
            throw SourceReviewValidation.unavailable();
        }
        if (validation == null) throw SourceReviewValidation.unavailable();
        validation.requireMatches(version, Instant.now());
        return validation;
    }
}
