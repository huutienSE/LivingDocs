/**
 * DTOs for Staff Review (LD-API-001 §3.4, API-115/116/120).
 * Field names beyond what LD-API-001 §4 fixes are CANDIDATES — see
 * docs/implementation/LIV-119-staff-review-contract-notes.md and align with LIV-118 (S04).
 */

/** REQ-05 Document Version states (matches S04 ck_document_versions_state). */
export type DocumentVersionState =
  | 'DRAFT'
  | 'IN_REVIEW'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'NEEDS_REVALIDATION'
  | 'REJECTED'
  | 'SUPERSEDED';

export type ReviewStage = 'STAFF_REVIEW' | 'MANAGER_APPROVAL';

/** Staff outcomes only (S04 ck_review_decisions_decision). Staff never REJECTs. */
export type StaffDecision = 'PASS' | 'RETURN_FOR_REVISION';

/** LD-API-001 §4.1 DocumentVersionView. */
export interface DocumentVersionView {
  documentId: string;
  versionId: string;
  state: DocumentVersionState;
  reviewStage?: ReviewStage | null;
  revision: string | number;
  sourceBinding?: {
    repositoryConnectionId?: string;
    targetKind?: 'PR' | 'TRACKED_BRANCH';
    targetRef?: string;
    headSha?: string;
  } | null;
  auditReference?: string | null;
}

/** API-115 item (candidate). */
export interface ReviewQueueItem {
  versionId: string;
  documentId: string;
  documentTitle: string;
  versionNo: number;
  state: DocumentVersionState;
  reviewStage: ReviewStage | null;
  sourceSha?: string | null;
  submittedAt?: string | null;
  submittedBy?: string | null;
}

/** API-115 response (candidate; Page shape shared across services). */
export interface ReviewQueueView {
  items: ReviewQueueItem[];
  page: number;
  size: number;
  totalElements: number;
}

export interface EvidenceRef {
  ref: string;
  label?: string;
  kind?: string;
}

/** API-116 response (candidate). */
export interface ReviewContextView {
  version: DocumentVersionView;
  documentTitle: string;
  versionNo: number;
  content: string;
  contentFormat?: 'MARKDOWN' | 'TEXT' | string;
  submittedBy?: string | null;
  submittedAt?: string | null;
  evidence: EvidenceRef[];
  reviewRound?: number;
}

/** API-120 request — LD-API-001 §4.7 StaffDecisionRequest. */
export interface StaffDecisionRequest {
  decision: StaffDecision;
  feedback?: string;
  evidenceRefs: string[];
}

/** Feedback limit enforced in UI; server stays authoritative (BVA tested on both sides). */
export const FEEDBACK_MAX_LENGTH = 2000;

export function isAwaitingStaffReview(v: Pick<DocumentVersionView, 'state' | 'reviewStage'>): boolean {
  return v.state === 'IN_REVIEW' && v.reviewStage === 'STAFF_REVIEW';
}
