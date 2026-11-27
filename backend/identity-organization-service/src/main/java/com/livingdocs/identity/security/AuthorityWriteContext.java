package com.livingdocs.identity.security;

import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

/** One tenant-scoped transaction. Policy checks run before role elevation. */
@Component
@RequiredArgsConstructor
public class AuthorityWriteContext {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;

    public <T> T asHuman(UUID tenantId, UUID actorId, String reason,
                         Runnable policyCheck, Supplier<T> mutation) {
        if (tenantId == null || actorId == null || policyCheck == null || mutation == null
                || reason == null || reason.length() < 8 || reason.length() > 500) {
            throw new IllegalArgumentException("Scoped human mutation requires tenant, actor and reason");
        }
        //Tenant và actor vào transaction
        //
        return newWriteTransaction().execute(status -> {
            // Đặt actor/tenant cho transaction để DB kiểm tra phạm vi.
            setLocal("app.identity_id", actorId.toString());
            setLocal("app.tenant_id", tenantId.toString());
            // Chạy Lambda 1: kiểm quyền; sai thì dừng, không ghi DB.
            policyCheck.run();

            // Gắn thông tin người thực hiện để DB audit.
            setLocal("app.actor_identity_id", actorId.toString());
            setLocal("app.actor_kind", "USER");
            setLocal("app.actor_ref", actorId.toString());
            setLocal("app.change_reason", reason);

            // Chuyển sang DB role có quyền ghi Grant giới hạn.
            jdbc.execute("SET LOCAL ROLE ld_s01_http_writer");
            // Chạy Lambda 2: kiểm trùng, tạo Grant, lưu idempotency.
            return mutation.get();

            // Callback xong: transaction manager commit;
            // có exception: rollback.
        });
    }

    private void setLocal(String key, String value) {
        jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class, key, value);
    }

    private TransactionTemplate newWriteTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }
}
