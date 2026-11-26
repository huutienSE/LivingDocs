package com.livingdocs.analysis.security;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sets transaction-local {@code app.tenant_id} on the PostgreSQL connection
 * before executing JPA queries so PostgreSQL RLS enforces fail-closed tenant isolation.
 */
@Component
public class S03TenantContext {

	private final JdbcTemplate jdbc;
	private final PlatformTransactionManager transactions;

	public S03TenantContext(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		this.transactions = Objects.requireNonNull(transactions, "transactions");
	}

	public <T> T writeInTenant(UUID tenantId, Supplier<T> work) {
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(work, "work");
		TransactionTemplate tx = new TransactionTemplate(transactions);
		return tx.execute(status -> {
			bindTransactionTenant(tenantId);
			return work.get();
		});
	}

	public <T> T readInTenant(UUID tenantId, Supplier<T> work) {
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(work, "work");
		TransactionTemplate tx = new TransactionTemplate(transactions);
		tx.setReadOnly(true);
		return tx.execute(status -> {
			bindTransactionTenant(tenantId);
			return work.get();
		});
	}

	private void bindTransactionTenant(UUID tenantId) {
		jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
	}
}
