package com.livingdocs.analysis.port;

import com.livingdocs.analysis.impact.ImpactClassifier.AssessmentContext;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupKey;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupResponse;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal port isolating S03 from S04 version-scoped reverse lookup contracts.
 */
public interface DocumentMappingPort {

	MappingLookupResult lookupBySourceKeys(MappingLookupRequest request);

	enum MappingLookupStatus {
		COMPLETE,
		INCOMPLETE,
		UNAVAILABLE,
		DENIED
	}

	record MappingLookupRequest(
			AssessmentContext context,
			List<DocumentLookupKey> lookupKeys) {

		public MappingLookupRequest {
			Objects.requireNonNull(context, "context");
			lookupKeys = List.copyOf(Objects.requireNonNull(lookupKeys, "lookupKeys"));
		}
	}

	record MappingLookupResult(
			MappingLookupStatus status,
			List<DocumentLookupResponse> responses,
			Optional<String> reasonCode) {

		public MappingLookupResult {
			Objects.requireNonNull(status, "status");
			responses = List.copyOf(Objects.requireNonNull(responses, "responses"));
			Objects.requireNonNull(reasonCode, "reasonCode");
		}
	}
}
