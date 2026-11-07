import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { describeError } from '../../api/client/problem';
import { fetchReviewContext } from './api';
import { StaffDecisionForm } from './StaffDecisionForm';
import { isAwaitingStaffReview, type DocumentVersionView, type ReviewContextView } from './types';

const STAGE_LABEL: Record<string, string> = {
  STAFF_REVIEW: 'Staff Review',
  MANAGER_APPROVAL: 'Chờ Manager duyệt',
};

function stateLabel(v: Pick<DocumentVersionView, 'state' | 'reviewStage'>): string {
  if (v.state === 'IN_REVIEW' && v.reviewStage) return `IN_REVIEW / ${STAGE_LABEL[v.reviewStage] ?? v.reviewStage}`;
  return v.state;
}

export function ReviewDetailPage() {
  const { versionId = '' } = useParams();
  const [context, setContext] = useState<ReviewContextView | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [outcome, setOutcome] = useState<DocumentVersionView | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      setLoading(true);
      setLoadError(null);
      try {
        const { data, etag: tag } = await fetchReviewContext(versionId, signal);
        setContext(data);
        setEtag(tag);
      } catch (err) {
        if (signal?.aborted) return;
        // Server state is authoritative: never keep showing actions on a version we can no longer read.
        setContext(null);
        setEtag(null);
        setLoadError(describeError(err));
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
        <Link to="/review">← Về hàng chờ</Link>
      </section>
    );
  }

  if (!context) return null;

  const version = outcome ?? context.version;
  const canDecide = !outcome && isAwaitingStaffReview(context.version) && etag !== null;

  return (
    <section>
      <Link to="/review">← Về hàng chờ</Link>
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

      {!outcome && !isAwaitingStaffReview(context.version) && (
        <p role="status" className="info">
          Phiên bản này không còn ở bước Staff Review nên không thể ra quyết định.
        </p>
      )}

      {!outcome && isAwaitingStaffReview(context.version) && etag === null && (
        <p role="alert" className="error">
          Máy chủ không trả ETag cho phiên bản này nên không thể gửi quyết định an toàn.
        </p>
      )}

      <dl className="meta">
        <dt>Người gửi</dt>
        <dd>{context.submittedBy ?? '—'}</dd>
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
        <StaffDecisionForm
          versionId={context.version.versionId}
          etag={etag}
          evidenceRefs={context.evidence.map((e) => e.ref)}
          onDecided={(view) => setOutcome(view)}
          onStale={() => void load()}
        />
      )}
    </section>
  );
}
