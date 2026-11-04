import { apiRequest, type ApiResponse } from '../../api/client/http';
import type {
  DocumentVersionView,
  ReviewContextView,
  ReviewQueueView,
  StaffDecisionRequest,
} from './types';

const enc = encodeURIComponent;

export const REVIEW_PATHS = {
  queue: '/api/v1/review-queue', // API-115
  context: (versionId: string) => `/api/v1/document-versions/${enc(versionId)}/review-context`, // API-116
  staffDecision: (versionId: string) => `/api/v1/document-versions/${enc(versionId)}/staff-decisions`, // API-120
} as const;

export interface QueueQuery {
  page?: number;
  size?: number;
  sort?: string;
}

export function fetchReviewQueue(query: QueueQuery = {}, signal?: AbortSignal): Promise<ApiResponse<ReviewQueueView>> {
  return apiRequest<ReviewQueueView>(REVIEW_PATHS.queue, {
    query: { page: query.page ?? 0, size: query.size ?? 20, sort: query.sort },
    signal,
  });
}

export function fetchReviewContext(versionId: string, signal?: AbortSignal): Promise<ApiResponse<ReviewContextView>> {
  return apiRequest<ReviewContextView>(REVIEW_PATHS.context(versionId), { signal });
}

/**
 * API-120. `etag` must be the strong ETag read with the version (If-Match);
 * `idempotencyKey` must stay the same if the user retries the same decision.
 */
export function submitStaffDecision(
  versionId: string,
  request: StaffDecisionRequest,
  etag: string,
  idempotencyKey: string,
): Promise<ApiResponse<DocumentVersionView>> {
  return apiRequest<DocumentVersionView>(REVIEW_PATHS.staffDecision(versionId), {
    method: 'POST',
    body: request,
    ifMatch: etag,
    idempotencyKey,
  });
}
