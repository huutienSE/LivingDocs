package com.livingdocs.identity.security;

import com.livingdocs.identity.exception.AuthorityException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
public class TenantContext {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;

    /** One short transaction exposes only the verified principal's active membership. */
    public UUID currentTenant(UUID verifiedIdentityId) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.identity_id', ?, true)", String.class,
                    verifiedIdentityId.toString());
            List<String> identities = jdbc.queryForList(
                    "SELECT status FROM identity.identities WHERE identity_id = ?", String.class,
                    verifiedIdentityId);
            if (identities.size() != 1 || !"ACTIVE".equals(identities.getFirst())) {
                throw new AuthorityException("IDENTITY_INACTIVE");
            }
            List<UUID> tenants = jdbc.queryForList("""
                    SELECT tenant_id FROM identity.tenant_memberships
                    WHERE identity_id = ? AND status = 'ACTIVE'
                    """, UUID.class, verifiedIdentityId);
            if (tenants.size() > 1) {
                throw new IllegalStateException("Active tenant membership uniqueness violated");
            }
            return tenants.isEmpty() ? null : tenants.getFirst();
        });
    }

    /** JPA joins the same transaction/connection after its tenant GUC is set. */
    public <T> T readInTenant(UUID tenantId, Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    tenantId.toString());
            return work.get();
        });
    }
}
