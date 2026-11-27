package com.livingdocs.document.service.impl;

import com.livingdocs.document.client.S01FreshSubmitAuthorizationClient;
import com.livingdocs.document.model.AuthorizationDecision;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import com.livingdocs.document.entity.DocumentAuditEntity;
import com.livingdocs.document.entity.DocumentSubmissionEntity;
import com.livingdocs.document.exception.DocumentVersionNotFoundException;
import com.livingdocs.document.exception.ReviewIdempotencyConflictException;
import com.livingdocs.document.exception.ReviewRevisionConflictException;
import com.livingdocs.document.exception.SourceEvidenceInsufficientException;
import com.livingdocs.document.exception.SubmitException;
import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.DocumentVersion;
import com.livingdocs.document.model.ReviewStage;
import com.livingdocs.document.model.SubmitActorContext;
import com.livingdocs.document.model.SubmitCommand;
import com.livingdocs.document.model.SubmitResult;
import com.livingdocs.document.repository.DocumentAuditJpaRepository;
import com.livingdocs.document.repository.DocumentSubmissionJpaRepository;
import com.livingdocs.document.repository.DocumentSubmitRepository;
import com.livingdocs.document.repository.DocumentVersionRepository;
import com.livingdocs.document.service.DocumentSubmitService;
import com.livingdocs.document.service.SubmitSnapshotCodec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Điều phối API-113: tác giả gửi Draft (kể cả Draft được trả về để sửa) sang Staff Review.
 * Evidence và source binding được đọc từ dữ liệu S04 đã lưu, không lấy từ request submit.
 *
 * <p>Version, biên nhận submit và audit thuộc cùng một transaction: cùng commit hoặc rollback
 * khi có lỗi runtime. Biên nhận phục vụ retry; audit phục vụ truy vết lịch sử nghiệp vụ.
 */
@Service
@RequiredArgsConstructor
public class DocumentSubmitServiceImpl implements DocumentSubmitService {
    private final DocumentVersionRepository versions;
    private final DocumentSubmitRepository drafts;
    private final DocumentSubmissionJpaRepository submissions;
    private final DocumentAuditJpaRepository audits;
    private final SubmitSnapshotCodec snapshots;
    private final JsonMapper json;
    private final S01FreshSubmitAuthorizationClient freshAuthorization;
    private final PlatformTransactionManager transactions;

    /**
     * Kiểm tra quyền, xử lý retry, xác nhận điều kiện Draft rồi ghi nhận lần gửi review mới.
     *
     * @param versionId version cần gửi review
     * @param command ghi chú tùy chọn của tác giả
     * @param expectedRevision revision client đã đọc, lấy từ If-Match
     * @param idempotencyKey khóa nhận diện request trong phạm vi tenant/version/actor
     * @param actor danh tính và quyền đã được lớp gọi service phân giải
     * @param correlationId mã truy vết request để liên kết với audit
     * @return kết quả lần submit mới hoặc snapshot của lần submit trùng khớp trước đó
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubmitResult submit(UUID versionId, SubmitCommand command, long expectedRevision,
            String idempotencyKey, SubmitActorContext actor, String userAccessToken, String correlationId) {
        validateRequest(command, expectedRevision, idempotencyKey);
        var read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        var scope = read.execute(status -> {
            versions.setTenant(actor.tenantId());
            var version = versions.find(versionId, actor.tenantId(), false)
                    .orElseThrow(DocumentVersionNotFoundException::new);
            actor.requireAuthor(version);
            return version;
        });
        var authorization = freshAuthorization.requireAllowed(scope, userAccessToken, correlationId);
        return new TransactionTemplate(transactions).execute(status ->
                recordSubmission(versionId, command, expectedRevision, idempotencyKey, actor, correlationId, authorization));
    }

    private SubmitResult recordSubmission(UUID versionId, SubmitCommand command, long expectedRevision,
            String idempotencyKey, SubmitActorContext actor, String correlationId,
            AuthorizationDecision authorization) {

        // Đặt tenant cho RLS trước khi đọc DB; khóa version và scope tới cuối transaction.
        versions.setTenant(actor.tenantId());
        var before = versions.find(versionId, actor.tenantId(), true)
                .orElseThrow(DocumentVersionNotFoundException::new);
        if (!authorization.appliesTo(before, "DOCUMENT_AUTHOR")) throw new ReviewRevisionConflictException();
        // Retry cũng phải kiểm tra quyền hiện tại trước khi trả dữ liệu đã lưu.
        actor.requireAuthor(before);

        var existing = submissions.findByTenantIdAndVersionIdAndActorIdAndKey(
                actor.tenantId(), versionId, actor.identityId(), idempotencyKey);
        if (existing.isPresent()) {
            // Replay trước kiểm tra revision/state: lần submit đầu đã thay đổi cả hai giá trị.
            return replaySubmission(existing.get(), command, expectedRevision);
        }

        requireSubmittableDraft(before, expectedRevision);
        var evidence = requireEvidence(before);
        var binding = resolveSourceBinding(before);
        var after = new DocumentVersion(
                versionId, before.tenantId(), before.documentId(), before.workspaceId(),
                before.repositoryConnectionId(), before.sourceSha(),
                DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW, expectedRevision + 1);
        var result = SubmitResult.recorded(after, binding);
        var receipt = createSubmissionReceipt(before, result, command, idempotencyKey, actor);

        // Repository chuyển trạng thái và flush để xác nhận optimistic revision tăng đúng một.
        drafts.submit(before);

        // Ghi biên nhận cùng snapshot để retry trả kết quả gốc, không tạo thêm lần submit.
        // Flush phát hiện lỗi ghi DB tại đây; chưa commit và vẫn rollback nếu audit thất bại.
        submissions.saveAndFlush(receipt);
        appendSubmissionAudit(before, result, receipt, evidence, actor, correlationId, authorization);
        return result;
    }

    /** Giữ kiểm tra đầu vào tại service để các caller ngoài HTTP cũng tuân thủ cùng giới hạn. */
    private void validateRequest(SubmitCommand command, long expectedRevision, String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")
                || expectedRevision < 0 || command == null
                || (command.note() != null && command.note().length() > 2000)) {
            throw new SubmitException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Invalid submit request.");
        }
    }

    /** Cùng key chỉ được replay nếu revision và note khớp; không ghi lại state, receipt hay audit. */
    private SubmitResult replaySubmission(DocumentSubmissionEntity receipt, SubmitCommand command,
            long expectedRevision) {
        if (receipt.getExpectedRevision() != expectedRevision
                || !Objects.equals(receipt.getNote(), command.note())) {
            throw new ReviewIdempotencyConflictException();
        }
        return snapshots.decode(receipt.getResponseSnapshot());
    }

    /** Request mới phải dùng revision hiện tại và Draft chưa nằm trong một bước review nào. */
    private void requireSubmittableDraft(DocumentVersion version, long expectedRevision) {
        if (version.revision() != expectedRevision) {
            throw new ReviewRevisionConflictException();
        }
        if (version.state() != DocumentState.DRAFT || version.reviewStage() != null) {
            throw new SubmitException("STATE_TRANSITION_FORBIDDEN", HttpStatus.CONFLICT, "Only Draft can be submitted.");
        }
    }

    /** Chỉ chấp nhận evidence khả dụng đã lưu đúng tenant/version theo truy vấn của repository. */
    private List<UUID> requireEvidence(DocumentVersion version) {
        var evidence = drafts.evidence(version.tenantId(), version.id());
        if (evidence.isEmpty()) {
            throw new SubmitException("SUBMIT_EVIDENCE_REQUIRED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Draft evidence is required.");
        }
        return evidence;
    }

    /**
     * Draft gắn repository sử dụng provenance đã lưu và SHA đúng định dạng.
     * Draft không gắn repository có binding null; bước này không gọi dịch vụ kiểm tra SHA hiện tại.
     */
    private SubmitResult.Binding resolveSourceBinding(DocumentVersion version) {
        var binding = version.repositoryConnectionId() == null
                ? null : drafts.binding(version.tenantId(), version.id());
        if (binding != null && (!binding.complete() || version.sourceSha() == null
                || !version.sourceSha().matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) {
            throw new SourceEvidenceInsufficientException();
        }
        return binding;
    }

    /**
     * Tạo biên nhận với snapshot kết quả trước khi thay đổi DB; lỗi encode dừng trước mutation.
     * ID và thời điểm của biên nhận được dùng lại trong audit để liên kết cùng một lần submit.
     */
    private DocumentSubmissionEntity createSubmissionReceipt(DocumentVersion before, SubmitResult result,
            SubmitCommand command, String idempotencyKey, SubmitActorContext actor) {
        var snapshot = snapshots.encode(result);
        var now = Instant.now();
        return DocumentSubmissionEntity.builder()
                .id(UUID.randomUUID())
                .tenantId(actor.tenantId())
                .versionId(before.id())
                .actorId(actor.identityId())
                .key(idempotencyKey)
                .expectedRevision(before.revision())
                .resultingRevision(result.version().revision())
                .note(command.note())
                .responseSnapshot(snapshot)
                .submittedAt(now)
                .build();
    }

    /**
     * Ghi lịch sử ai submit, thay đổi gì và dùng bằng chứng nào tại thời điểm gửi.
     * Audit phục vụ truy vết; receipt ở submit_requests phục vụ idempotency/replay.
     * Flush audit vẫn thuộc transaction của submit, nên lỗi runtime rollback cả version và receipt.
     */
    private void appendSubmissionAudit(DocumentVersion before, SubmitResult result,
            DocumentSubmissionEntity receipt, List<UUID> evidence, SubmitActorContext actor,
            String correlationId, AuthorizationDecision authorization) {
        var after = result.version();
        // LinkedHashMap giữ thứ tự field và cho phép giá trị null (stage cũ, binding, note).
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("submissionId", receipt.getId());
        metadata.put("previousRevision", before.revision());
        metadata.put("newRevision", after.revision());
        metadata.put("previousReviewStage", before.reviewStage());
        metadata.put("newReviewStage", after.reviewStage());
        metadata.put("evidenceRefs", evidence);
        metadata.put("sourceBinding", result.binding());
        metadata.put("note", receipt.getNote());
        metadata.put("correlationId", correlationId);
        metadata.put("authorization", authorization);

        audits.saveAndFlush(DocumentAuditEntity.builder()
                .id(UUID.randomUUID())
                .tenantId(actor.tenantId())
                .documentId(before.documentId())
                .versionId(before.id())
                .workspaceId(before.workspaceId())
                .repositoryConnectionId(before.repositoryConnectionId())
                .actorRef(actor.identityId().toString())
                .action("DOCUMENT_SUBMITTED")
                .previousState(before.state())
                .newState(after.state())
                .sourceSha(before.sourceSha())
                .metadataJson(json.writeValueAsString(metadata))
                .occurredAt(receipt.getSubmittedAt())
                .build());
    }
}
