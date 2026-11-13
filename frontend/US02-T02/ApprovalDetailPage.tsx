import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchApprovalContext } from './api';
import { ManagerDecisionForm } from './ManagerDecisionForm';
import { describeManagerError } from './messages';
import {
  isAwaitingManagerApproval,
  type ApprovalContextView,
  type DocumentVersionView,
  type EvidenceRef,
} from './types';

const STAGE_LABEL: Record<string, string> = {
  STAFF_REVIEW: 'Staff Review',
  MANAGER_APPROVAL: 'Chờ Manager duyệt',
};

function stateLabel(v: Pick<DocumentVersionView, 'state' | 'reviewStage'>): string {
  if (v.state === 'IN_REVIEW' && v.reviewStage) return `IN_REVIEW / ${STAGE_LABEL[v.reviewStage] ?? v.reviewStage}`;
  return v.state;
}

/** Evidence the Manager approves against: prefer a source-kind item, else the first one. Candidate — see contract notes. */
export function pickSourceEvidence(evidence: EvidenceRef[]): string | undefined {
  return (evidence.find((e) => e.kind?.toUpperCase().startsWith('SOURCE')) ?? evidence[0])?.ref;
}

export function ApprovalDetailPage() {
  const { versionId = '' } = useParams();
  const [context, setContext] = useState<ApprovalContextView | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [outcome, setOutcome] = useState<DocumentVersionView | null>(null);
  // Why the last decision was refused. Kept at page level so the reason survives a re-read that removes the form.
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      setLoading(true);
      setLoadError(null);
      try {
        const { data, etag: tag } = await fetchApprovalContext(versionId, signal);
        setContext(data);
        setEtag(tag);
      } catch (err) {
        if (signal?.aborted) return;
        // Server state is authoritative: never keep showing actions on a version we can no longer read
        // (revoked role, other tenant, state changed) — even if we showed them a moment ago.
        setContext(null);
        setEtag(null);
        setLoadError(describeManagerError(err));
      } finally {
        if (!signal?.aborted) setLoading(false);
      }
    },
    [versionId],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  if (loading && !context) return <p aria-busy="true">Đang tải…</p>;

  if (loadError && !context) {
    return (
      <section>
        <p role="alert" className="error">
          {loadError}
        </p>
        <Link to="/approval">← Về hàng chờ</Link>
      </section>
    );
  }

  if (!context) return null;

  const version = outcome ?? context.version;
  const awaiting = isAwaitingManagerApproval(context.version);
  const canDecide = !outcome && awaiting && etag !== null;

  return (
    <section>
      <Link to="/approval">← Về hàng chờ</Link>
      <header className="page-header">
        <h1>
          {context.documentTitle} <small>v{context.versionNo}</small>
        </h1>
        <span className="badge" data-testid="version-state">
          {stateLabel(version)}
        </span>
      </header>

      {outcome && (
        <p role="status" className="success">
          Đã ghi nhận quyết định. Trạng thái hiện tại: <strong>{stateLabel(outcome)}</strong>.
        </p>
      )}

      {!outcome && !canDecide && staleNotice && (
        <p role="alert" className="error">
          {staleNotice}
        </p>
      )}

      {!outcome && !awaiting && (
        <p role="status" className="info">
          Phiên bản này không còn ở bước chờ Manager duyệt nên không thể ra quyết định.
        </p>
      )}

      {!outcome && awaiting && etag === null && (
        <p role="alert" className="error">
          Máy chủ không trả ETag cho phiên bản này nên không thể gửi quyết định an toàn.
        </p>
      )}

      <dl className="meta">
        <dt>Tác giả</dt>
        <dd>{context.createdBy ?? '—'}</dd>
        <dt>Người chỉnh sửa</dt>
        <dd>{context.editedBy && context.editedBy.length > 0 ? context.editedBy.join(', ') : '—'}</dd>
        <dt>Staff đã PASS</dt>
        <dd>{context.staffReview?.reviewer ?? '—'}</dd>
        <dt>Nhận xét Staff</dt>
        <dd>{context.staffReview?.feedback ?? '—'}</dd>
        <dt>Source SHA</dt>
        <dd>
          <code>{context.version.sourceBinding?.headSha ?? '—'}</code>
        </dd>
        <dt>Vòng review</dt>
        <dd>{context.reviewRound ?? '—'}</dd>
      </dl>

      <h2>Nội dung</h2>
      <pre className="content">{context.content}</pre>

      <h2>Bằng chứng (evidence)</h2>
      {context.evidence.length === 0 ? (
        <p className="empty">Không có evidence đính kèm.</p>
      ) : (
        <ul>
          {context.evidence.map((e) => (
            <li key={e.ref}>
              {e.label ?? e.ref} {e.kind && <small>({e.kind})</small>}
            </li>
          ))}
        </ul>
      )}

      {canDecide && (
        <ManagerDecisionForm
          versionId={context.version.versionId}
          etag={etag}
          sourceEvidenceRef={pickSourceEvidence(context.evidence)}
          onDecided={(view) => setOutcome(view)}
          onStale={(message) => {
            setStaleNotice(message);
            void load();
          }}
        />
      )}
    </section>
  );
}
