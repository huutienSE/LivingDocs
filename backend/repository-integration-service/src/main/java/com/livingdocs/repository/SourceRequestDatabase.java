package com.livingdocs.repository;

import com.livingdocs.model.SourceRequestDeadline;
import jakarta.persistence.EntityManager;

/** Áp dụng giới hạn cục bộ cho giao dịch, tự hết hiệu lực khi trả connection về pool. */
final class SourceRequestDatabase {
    private SourceRequestDatabase() {
    }

    static void constrain(EntityManager entityManager) {
        Long remaining = SourceRequestDeadline.remainingMillis();
        if (remaining != null) {
            entityManager.createNativeQuery("""
                    select set_config('statement_timeout', :budget, true),
                           set_config('lock_timeout', :budget, true)
                    """).setParameter("budget", remaining + "ms")
                    .setHint("jakarta.persistence.query.timeout", (int) Math.min(Integer.MAX_VALUE, remaining))
                    .getSingleResult();
            SourceRequestDeadline.check();
        }
    }
}
