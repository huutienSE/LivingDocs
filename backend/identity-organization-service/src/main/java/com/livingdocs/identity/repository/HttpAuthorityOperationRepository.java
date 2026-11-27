package com.livingdocs.identity.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.livingdocs.identity.exception.GrantConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotency keys are tenant and actor bound; all calls require the writer transaction. */
@Repository
@RequiredArgsConstructor
public class HttpAuthorityOperationRepository {
    private final JdbcTemplate jdbc;

    public UUID claimOrResult(UUID operationId, UUID tenantId, UUID actorId, String payload) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO identity.authority_http_operations
                  (operation_id, tenant_id, actor_identity_id, request_sha256)
                VALUES (?, ?, ?, ?) ON CONFLICT (operation_id) DO NOTHING
                RETURNING operation_id
                """, UUID.class, operationId, tenantId, actorId, hash);
        if (!inserted.isEmpty()) return null;
        List<Operation> prior = jdbc.query("""
                SELECT actor_identity_id, request_sha256, result_grant_id
                FROM identity.authority_http_operations WHERE operation_id = ? AND tenant_id = ?
                """, (rs, row) -> new Operation((UUID) rs.getObject(1), rs.getString(2),
                        (UUID) rs.getObject(3)), operationId, tenantId);
        if (prior.size() != 1 || !actorId.equals(prior.getFirst().actor())
                || !hash.equals(prior.getFirst().hash()) || prior.getFirst().grantId() == null)
            throw new GrantConflictException("Idempotency key conflict");
        return prior.getFirst().grantId();
    }

    public void finish(UUID operationId, UUID grantId) {
        if (jdbc.update("UPDATE identity.authority_http_operations SET result_grant_id = ? WHERE operation_id = ?",
                grantId, operationId) != 1) throw new IllegalStateException("Idempotency result was not saved");
    }

    private record Operation(UUID actor, String hash, UUID grantId) {}
}
