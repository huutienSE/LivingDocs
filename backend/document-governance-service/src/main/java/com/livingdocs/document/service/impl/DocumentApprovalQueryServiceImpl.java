package com.livingdocs.document.service.impl;

import com.livingdocs.document.exception.DocumentVersionNotFoundException;
import com.livingdocs.document.model.ApprovalContext;
import com.livingdocs.document.model.ApprovalQueueItem;
import com.livingdocs.document.model.ApprovalQueuePage;
import com.livingdocs.document.model.ApprovalScope;
import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import com.livingdocs.document.repository.ApprovalQueryRepository;
import com.livingdocs.document.security.ActorContext;
import com.livingdocs.document.service.DocumentApprovalQueryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentApprovalQueryServiceImpl implements DocumentApprovalQueryService {
    private final ApprovalQueryRepository approvals;

    @Override
    @Transactional(readOnly = true)
    public ApprovalQueuePage queue(ActorContext actor, int page, int size) {
        // No Manager grant at all → 403 before touching the database.
        var scope = ApprovalScope.of(actor);
        approvals.setTenant(scope.tenantId());
        long total = approvals.countQueue(scope);
        var items = total == 0 || (long) page * size >= total ? List.<ApprovalQueueItem>of()
                : approvals.findQueue(scope, page, size);
        return ApprovalQueuePage.of(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalContext context(UUID versionId, ActorContext actor) {
        var scope = ApprovalScope.of(actor);
        approvals.setTenant(scope.tenantId());
        var header = approvals.findHeader(scope.tenantId(), versionId)
                .orElseThrow(DocumentVersionNotFoundException::new);
        scope.require(scope.tenantId(), header.workspaceId(), header.repositoryConnectionId());
        var version = header.version();
        boolean awaiting = version.state() == DocumentState.IN_REVIEW
                && version.reviewStage() == ReviewStage.MANAGER_APPROVAL;
        return new ApprovalContext(version, header.documentTitle(), header.versionNo(), header.workspaceId(), awaiting,
                approvals.findCurrentContent(scope.tenantId(), versionId).orElse(null),
                approvals.findStaffDecisions(scope.tenantId(), versionId),
                approvals.findApprovalDecisions(scope.tenantId(), versionId),
                approvals.findEvidence(scope.tenantId(), versionId));
    }
}
