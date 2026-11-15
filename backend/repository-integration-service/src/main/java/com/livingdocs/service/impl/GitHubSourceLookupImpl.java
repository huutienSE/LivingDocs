package com.livingdocs.service.impl;

import com.livingdocs.client.GitHubInstallationTokenProvider;
import com.livingdocs.client.GitHubSourceClient;
import com.livingdocs.client.GitHubSourceClient.CommitObservation;
import com.livingdocs.exception.SourceUnavailableException;
import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.TargetKind;
import com.livingdocs.repository.JpaConnectionLookup;
import com.livingdocs.service.GitHubSourceLookup;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Kiểm điều kiện trong S02 trước khi đọc GitHub. Giao dịch ghi quan sát thực hiện sau bước này. */
@Service
@RequiredArgsConstructor
public class GitHubSourceLookupImpl implements GitHubSourceLookup {
    private final JpaConnectionLookup connections;
    private final GitHubInstallationTokenProvider tokens;
    private final GitHubSourceClient github;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result readCurrent(SourceScope scope, UUID connectionId, TargetKind kind, String ref,
                              String expectedConfigRevision, boolean includeTree) {
        if (scope == null || connectionId == null || kind == null || !kind.isValidRef(ref)
                || (expectedConfigRevision == null && !includeTree)
                || (expectedConfigRevision != null && (expectedConfigRevision.isBlank() || expectedConfigRevision.length() > 255))) {
            return failure(Outcome.INVALID_REQUEST);
        }
        try {
            var found = connections.findTarget(scope, connectionId, kind, ref);
            if (found.isEmpty()) {
                return failure(Outcome.UNKNOWN_CONNECTION);
            }
            var target = found.get();
            SourceConnection connection = target.getConnection();
            if (!connection.active()) {
                return failure(Outcome.CONNECTION_INACTIVE);
            }
            if (connection.configRevision() == null) {
                return failure(Outcome.SOURCE_UNAVAILABLE);
            }
            if (expectedConfigRevision != null && !expectedConfigRevision.equals(connection.configRevision())) {
                return failure(Outcome.CONFIG_CHANGED);
            }
            if (!target.isAllowed()) {
                return failure(Outcome.UNKNOWN_REF);
            }
            var access = tokens.openRepository(scope, target.getInstallationRef(), target.getExternalRepoId(),
                    target.getCredentialRef(), connection.configRevision());
            Optional<CommitObservation> observation = switch (kind) {
                case BRANCH -> github.readBranch(access, ref, includeTree);
                case PR -> github.readPullRequest(access, Integer.parseInt(ref), includeTree);
            };
            return observation.map(commit -> new Result(Outcome.OBSERVED, connection, commit))
                    .orElseGet(() -> new Result(Outcome.REF_ABSENT, connection, null));
        } catch (SourceUnavailableException | PersistenceException | DataAccessException | TransactionException failure) {
            return failure(Outcome.SOURCE_UNAVAILABLE);
        }
    }

    private static Result failure(Outcome outcome) {
        return new Result(outcome, null, null);
    }
}
