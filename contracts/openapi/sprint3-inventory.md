# LIV-133 — API inventory Sprint 3

Nguồn: LD-API-001 v0.4; so sánh với Gateway LIV-115, S04 PR LIV-118, FE PR LIV-119 và S02 LIV-122 (ngày 06/11/2026).

Không đánh dấu `CANDIDATE` thành `IMPLEMENTED` chỉ vì Gateway có route.

| API | Service | Method | Path | Trạng thái | Gateway |
| --- | --- | --- | --- | --- | --- |
| API-001 | S01 | `POST` | `/api/v1/auth/register` | CANDIDATE | Allowlist (JWT) |
| API-002 | S01 | `POST` | `/api/v1/auth/login` | CANDIDATE | Allowlist (JWT) |
| API-003 | S01 | `POST` | `/api/v1/auth/logout` | BLOCKED_SECURITY | Không public |
| API-004 | S01 | `POST` | `/api/v1/auth/refresh` | BLOCKED_SECURITY | Không public |
| API-005 | S01 | `GET` | `/api/v1/auth/github/start` | BLOCKED_SECURITY | Không public |
| API-006 | S01 | `GET` | `/api/v1/auth/github/callback` | BLOCKED_SECURITY | Không public |
| API-007 | S01 | `GET` | `/api/v1/users/me` | CANDIDATE | Allowlist (JWT) |
| API-008 | S01 | `PATCH` | `/api/v1/users/me` | CANDIDATE | Allowlist (JWT) |
| API-016 | S01 | `GET` | `/api/v1/tenants/current` | CANDIDATE | Allowlist (JWT) |
| API-017 | S01 | `GET` | `/api/v1/tenants/{tenantId}` | CANDIDATE | Allowlist (JWT) |
| API-018 | S01 | `PATCH` | `/api/v1/tenants/{tenantId}` | CANDIDATE | Allowlist (JWT) |
| API-019 | S01 | `POST` | `/api/v1/tenants/{tenantId}/invitations` | CANDIDATE | Allowlist (JWT) |
| API-023 | S01 | `GET` | `/api/v1/tenants/{tenantId}/memberships` | CANDIDATE | Allowlist (JWT) |
| API-024 | S01 | `PATCH` | `/api/v1/tenants/{tenantId}/memberships/{membershipId}` | CANDIDATE | Allowlist (JWT) |
| API-028 | S01 | `GET` | `/api/v1/tenants/{tenantId}/workspaces` | CANDIDATE | Allowlist (JWT) |
| API-029 | S01 | `POST` | `/api/v1/tenants/{tenantId}/workspaces` | CANDIDATE | Allowlist (JWT) |
| API-030 | S01 | `GET` | `/api/v1/workspaces/{workspaceId}` | CANDIDATE | Allowlist (JWT) |
| API-031 | S01 | `PATCH` | `/api/v1/workspaces/{workspaceId}` | CANDIDATE | Allowlist (JWT) |
| API-033 | S01 | `GET` | `/api/v1/workspaces/{workspaceId}/memberships` | CANDIDATE | Allowlist (JWT) |
| API-034 | S01 | `POST` | `/api/v1/workspaces/{workspaceId}/memberships` | CANDIDATE | Allowlist (JWT) |
| API-035 | S01 | `DELETE` | `/api/v1/workspaces/{workspaceId}/memberships/{membershipId}` | CANDIDATE | Allowlist (JWT) |
| API-036 | S01 | `PUT` | `/api/v1/workspaces/{workspaceId}/memberships/{membershipId}/roles` | CANDIDATE | Allowlist (JWT) |
| API-037 | S01 | `GET` | `/api/v1/workspaces/{workspaceId}/roles` | CANDIDATE | Allowlist (JWT) |
| API-038 | S01 | `GET` | `/api/v1/users/me/access-context` | CANDIDATE | Allowlist (JWT) |
| API-046 | S02 | `GET` | `/api/v1/integrations/github/authorization` | CANDIDATE | Allowlist (JWT) |
| API-047 | S02 | `POST` | `/api/v1/workspaces/{workspaceId}/repository-connections` | CANDIDATE | Allowlist (JWT) |
| API-048 | S02 | `GET` | `/api/v1/workspaces/{workspaceId}/repository-connections` | CANDIDATE | Allowlist (JWT) |
| API-049 | S02 | `GET` | `/api/v1/repositories/{repositoryId}/connection` | CANDIDATE | Allowlist (JWT) |
| API-050 | S02 | `POST` | `/api/v1/repositories/{repositoryId}/disconnections` | CANDIDATE | Allowlist (JWT) |
| API-051 | S02 | `POST` | `/api/v1/repositories/{repositoryId}/reauthorizations` | CANDIDATE | Allowlist (JWT) |
| API-052 | S02 | `POST` | `/api/v1/repositories/{repositoryId}/resyncs` | CANDIDATE | Allowlist (JWT) |
| API-053 | S02 | `GET` | `/api/v1/repositories/{repositoryId}/pull-requests` | CANDIDATE | Allowlist (JWT) |
| API-054 | S02 | `GET` | `/api/v1/repositories/{repositoryId}/pull-requests/{prNumber}` | CANDIDATE | Allowlist (JWT) |
| API-055 | S02 | `GET` | `/api/v1/repositories/{repositoryId}/commits` | CANDIDATE | Allowlist (JWT) |
| API-056 | S02 | `GET` | `/api/v1/repositories/{repositoryId}/source-events` | CANDIDATE | Allowlist (JWT) |
| API-057 | S02 | `GET` | `/api/v1/repositories/{repositoryId}/tracked-branches` | CANDIDATE | Allowlist (JWT) |
| API-058 | S02 | `PUT` | `/api/v1/repositories/{repositoryId}/tracked-branches` | CANDIDATE | Allowlist (JWT) |
| API-059 | S02 | `POST` | `/api/v1/repositories/{repositoryId}/rechecks` | CANDIDATE | Allowlist (JWT) |
| API-068 | S02 | `POST` | `/integrations/github/webhooks` | BLOCKED_SECURITY | Không public |
| API-069 | S02 | `POST` | `/internal/v1/source/resolve` | INTERNAL_CANDIDATE | Không public |
| API-070 | S02 | `POST` | `/internal/v1/source/validate` | INTERNAL_CANDIDATE | Không public |
| API-071 | S02 | `POST` | `/internal/v1/source/content` | INTERNAL_CANDIDATE | Không public |
| API-077 | S03 | `POST` | `/api/v1/analysis-jobs` | CANDIDATE | Allowlist (JWT) |
| API-078 | S03 | `GET` | `/api/v1/analysis-jobs/{jobId}` | CANDIDATE | Allowlist (JWT) |
| API-079 | S03 | `GET` | `/api/v1/repositories/{repositoryId}/analysis-jobs` | CANDIDATE | Allowlist (JWT) |
| API-080 | S03 | `POST` | `/api/v1/analysis-jobs/{jobId}/replay` | CANDIDATE | Allowlist (JWT) |
| API-081 | S03 | `GET` | `/api/v1/analysis-jobs/{jobId}/evidence` | CANDIDATE | Allowlist (JWT) |
| API-082 | S03 | `GET` | `/api/v1/repositories/{repositoryId}/drift-issues` | CANDIDATE | Allowlist (JWT) |
| API-083 | S03 | `GET` | `/api/v1/drift-issues/{driftIssueId}` | CANDIDATE | Allowlist (JWT) |
| API-084 | S03 | `GET` | `/api/v1/drift-issues/{driftIssueId}/history` | CANDIDATE | Allowlist (JWT) |
| API-085 | S03 | `GET` | `/api/v1/drift-issues/{driftIssueId}/evidence` | CANDIDATE | Allowlist (JWT) |
| API-086 | S03 | `GET` | `/api/v1/drift-issues/{driftIssueId}/suggestions` | CANDIDATE | Allowlist (JWT) |
| API-087 | S03 | `POST` | `/api/v1/drift-issues/{driftIssueId}/dismiss` | CANDIDATE | Allowlist (JWT) |
| API-088 | S03 | `POST` | `/api/v1/drift-issues/{driftIssueId}/suggestions/{suggestionId}/decisions` | CANDIDATE | Allowlist (JWT) |
| API-089 | S03 | `GET` | `/api/v1/repositories/{repositoryId}/code-entities` | CANDIDATE | Allowlist (JWT) |
| API-090 | S03 | `GET` | `/api/v1/repositories/{repositoryId}/dependencies` | CANDIDATE | Allowlist (JWT) |
| API-091 | S03 | `GET` | `/api/v1/repositories/{repositoryId}/drift-metrics` | CANDIDATE | Allowlist (JWT) |
| API-092 | S03 | `GET` | `/api/v1/repositories/{repositoryId}/drift-policy` | CANDIDATE | Allowlist (JWT) |
| API-093 | S03 | `PUT` | `/api/v1/repositories/{repositoryId}/drift-policy` | CANDIDATE | Allowlist (JWT) |
| API-096 | S03 | `POST` | `/internal/v1/analysis-jobs` | INTERNAL_CANDIDATE | Không public |
| API-097 | S03 | `POST` | `/internal/v1/revalidation/impact` | INTERNAL_CANDIDATE | Không public |
| API-098 | S03 | `GET` | `/internal/v1/analysis-jobs/{jobId}/outcome` | INTERNAL_CANDIDATE | Không public |
| API-099 | S04 | `GET` | `/api/v1/workspaces/{workspaceId}/documents` | CANDIDATE | Allowlist (JWT) |
| API-100 | S04 | `POST` | `/api/v1/workspaces/{workspaceId}/documents` | CANDIDATE | Allowlist (JWT) |
| API-101 | S04 | `GET` | `/api/v1/documents/{documentId}` | CANDIDATE | Allowlist (JWT) |
| API-102 | S04 | `PATCH` | `/api/v1/documents/{documentId}/metadata` | CANDIDATE | Allowlist (JWT) |
| API-103 | S04 | `GET` | `/api/v1/documents/{documentId}/source-links` | CANDIDATE | Allowlist (JWT) |
| API-104 | S04 | `GET` | `/api/v1/documents/{documentId}/versions` | CANDIDATE | Allowlist (JWT) |
| API-105 | S04 | `POST` | `/api/v1/documents/{documentId}/versions` | CANDIDATE | Allowlist (JWT) |
| API-106 | S04 | `GET` | `/api/v1/document-versions/{versionId}` | CANDIDATE | Allowlist (JWT) |
| API-107 | S04 | `PATCH` | `/api/v1/document-versions/{versionId}/content` | CANDIDATE | Allowlist (JWT) |
| API-108 | S04 | `GET` | `/api/v1/document-versions/{versionId}/evidence` | CANDIDATE | Allowlist (JWT) |
| API-109 | S04 | `GET` | `/api/v1/document-versions/{versionId}/changes` | CANDIDATE | Allowlist (JWT) |
| API-110 | S04 | `GET` | `/api/v1/document-versions/compare` | CANDIDATE | Allowlist (JWT) |
| API-111 | S04 | `GET` | `/api/v1/document-versions/{versionId}/attribution` | CANDIDATE | Allowlist (JWT) |
| API-112 | S04 | `POST` | `/api/v1/document-versions/{versionId}/regenerate` | CANDIDATE | Allowlist (JWT) |
| API-113 | S04 | `POST` | `/api/v1/document-versions/{versionId}/submit` | CANDIDATE | Allowlist (JWT) |
| API-114 | S04 | `POST` | `/api/v1/document-versions/{versionId}/revalidations` | CANDIDATE | Allowlist (JWT) |
| API-115 | S04 | `GET` | `/api/v1/review-queue` | CANDIDATE | Allowlist (JWT) |
| API-116 | S04 | `GET` | `/api/v1/document-versions/{versionId}/review-context` | CANDIDATE | Allowlist (JWT) |
| API-117 | S04 | `GET` | `/api/v1/document-versions/{versionId}/review-comments` | CANDIDATE | Allowlist (JWT) |
| API-118 | S04 | `POST` | `/api/v1/document-versions/{versionId}/review-comments` | CANDIDATE | Allowlist (JWT) |
| API-119 | S04 | `PATCH` | `/api/v1/document-versions/{versionId}/staff-edits` | CANDIDATE | Allowlist (JWT) |
| API-120 | S04 | `POST` | `/api/v1/document-versions/{versionId}/staff-decisions` | IN_PROGRESS | Allowlist (JWT) |
| API-121 | S04 | `GET` | `/api/v1/document-versions/{versionId}/review-history` | CANDIDATE | Allowlist (JWT) |
| API-122 | S04 | `GET` | `/api/v1/approval-queue` | CANDIDATE | Allowlist (JWT) |
| API-123 | S04 | `GET` | `/api/v1/document-versions/{versionId}/approval-context` | CANDIDATE | Allowlist (JWT) |
| API-124 | S04 | `POST` | `/api/v1/document-versions/{versionId}/manager-decisions` | PLANNED | Allowlist (JWT) |
| API-125 | S04 | `GET` | `/api/v1/document-versions/{versionId}/approval-history` | CANDIDATE | Allowlist (JWT) |
| API-126 | S04 | `POST` | `/api/v1/document-versions/{versionId}/approval-assignments` | CANDIDATE | Allowlist (JWT) |
| API-127 | S04 | `POST` | `/api/v1/document-versions/{versionId}/publish` | PLANNED | Allowlist (JWT) |
| API-128 | S04 | `POST` | `/api/v1/document-versions/{versionId}/exports` | PLANNED | Allowlist (JWT) |
| API-129 | S04 | `GET` | `/api/v1/document-versions/{versionId}/exports` | CANDIDATE | Allowlist (JWT) |
| API-131 | S04 | `GET` | `/api/v1/workspaces/{workspaceId}/collections` | CANDIDATE | Allowlist (JWT) |
| API-132 | S04 | `POST` | `/api/v1/workspaces/{workspaceId}/collections` | CANDIDATE | Allowlist (JWT) |
| API-133 | S04 | `GET` | `/api/v1/collections/{collectionId}` | CANDIDATE | Allowlist (JWT) |
| API-134 | S04 | `PATCH` | `/api/v1/collections/{collectionId}` | CANDIDATE | Allowlist (JWT) |
| API-136 | S04 | `POST` | `/api/v1/knowledge/search` | CANDIDATE | Allowlist (JWT) |
| API-137 | S04 | `GET` | `/api/v1/knowledge/linked-code-entities` | CANDIDATE | Allowlist (JWT) |
| API-138 | S04 | `GET` | `/api/v1/workspaces/{workspaceId}/templates` | CANDIDATE | Allowlist (JWT) |
| API-139 | S04 | `POST` | `/api/v1/workspaces/{workspaceId}/templates` | CANDIDATE | Allowlist (JWT) |
| API-140 | S04 | `GET` | `/api/v1/templates/{templateId}` | CANDIDATE | Allowlist (JWT) |
| API-160 | S04 | `POST` | `/internal/v1/document-candidates` | INTERNAL_CANDIDATE | Không public |
| API-161 | S04 | `GET` | `/internal/v1/document-candidates/{operationId}` | INTERNAL_CANDIDATE | Không public |
| API-162 | S04 | `POST` | `/internal/v1/documents/context` | INTERNAL_CANDIDATE | Không public |
| API-163 | S04 | `POST` | `/internal/v1/document-versions/{versionId}/source-guard` | INTERNAL_CANDIDATE | Không public |
| API-164 | S05 | `POST` | `/internal/v1/ai/executions` | INTERNAL_CANDIDATE | Không public |
| API-165 | S05 | `GET` | `/internal/v1/ai/executions/{executionId}` | INTERNAL_CANDIDATE | Không public |
| API-166 | S05 | `GET` | `/internal/v1/ai/executions/{executionId}/result` | INTERNAL_CANDIDATE | Không public |
| API-167 | S05 | `POST` | `/internal/v1/ai/executions/{executionId}/cancel` | INTERNAL_CANDIDATE | Không public |
| API-168 | S05 | `POST` | `/internal/v1/ai/context/retrieval` | INTERNAL_CANDIDATE | Không public |
| API-169 | S05 | `POST` | `/internal/v1/ai/index-sync` | INTERNAL_CANDIDATE | Không public |

## Các vấn đề chặn tích hợp

1. **M01 API-115:** S04 chưa có `GET /api/v1/review-queue` (FE PR #15 đã gọi); Gateway route được chuẩn bị nhưng chưa chứng minh API.
2. **M02 API-116:** S04 chưa có `GET /api/v1/document-versions/{versionId}/review-context`; phải trả `ReviewContextView` và strong `ETag` dạng `"{versionId}:revision-{revision}"` tương thích API-120.
3. **M03:** FE xử lý `FORBIDDEN_SCOPE`, S04 PR #20 trả `REVIEW_FORBIDDEN`; Thịnh cần bổ sung mã mới vào `describeError()` hoặc thống nhất lỗi với Hải.
4. **M04:** S04 API-120 đã có `documentId` và `sourceBinding`; không còn coi đó là thiếu DTO. FE cần xác thực evidence `ref` là `document_evidence_id` UUID, giới hạn tối đa 100 phần tử không trùng, và semantics retry nguyên `If-Match`/key.
5. **M05:** S01 issuer/JWKS/audience/claims/token refresh thật chưa E2E. Gateway vẫn fail closed, không sử dụng mock issuer làm runtime.
6. **M06:** S02 LIV-122 dùng contract authoritative SHA internal; `source/resolve`, `source/validate` không được route public. Chốt `SourceValidationReceipt` cùng Tiến trước khi S04 publish.
7. **M07:** Cookie-backed auth refresh/logout và GitHub OAuth callback chưa đạt chính sách CSRF/session. Webhook chưa có HMAC/replay evidence nên không public.
8. **M08:** Manager approval LIVE task LIV-120 cần no-self-approval, fresh authorization; Publish LIVE task LIV-123 cần source validation; có route không thay thế business guards.

## Quy tắc đóng mismatch

- Owner S04 Hải chốt API-115/116/120; owner FE Thịnh chốt DTO, 401/403/409/412/428, ETag và UUID evidence.
- Owner S02 Tiến chốt receipt nội bộ; owner S01 chốt JWT trust; Gateway chỉ mở đúng public method/path.
- Cần PR, CI, review và integration evidence trước khi chuyển từ Candidate sang Implemented.
