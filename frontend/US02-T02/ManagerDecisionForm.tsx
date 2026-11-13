import { useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client/problem';
import { newIdempotencyKey } from '../../api/client/http';
import { submitManagerDecision } from './api';
import { describeManagerError } from './messages';
import {
  DECISION_LABEL,
  REASON_MAX_LENGTH,
  needsReason,
  type DocumentVersionView,
  type ManagerDecision,
  type ManagerDecisionRequest,
} from './types';

interface Props {
  versionId: string;
  etag: string;
  /** Evidence the Manager approves against (sent only with FINAL_APPROVE). Omitted when none exists. */
  sourceEvidenceRef?: string;
  onDecided: (view: DocumentVersionView, newEtag: string | null) => void;
  /**
   * Called after a failure that means our copy is stale or no longer authorised (403/404/409/412/428):
   * parent must re-read. `message` is the reason shown to the user — the parent keeps it visible even if the
   * re-read removes this form (e.g. the source changed and the version left Manager approval).
   */
  onStale: (message: string) => void;
}

/** Status codes after which the server state must be re-read rather than retried blindly. */
const RELOAD_STATUSES = new Set([403, 404, 409, 412, 428]);

export function validateReason(decision: ManagerDecision, reason: string): string | null {
  const trimmed = reason.trim();
  if (needsReason(decision) && trimmed.length === 0) {
    return 'Trả về hoặc từ chối cần có lý do.';
  }
  if (reason.length > REASON_MAX_LENGTH) {
    return `Lý do tối đa ${REASON_MAX_LENGTH} ký tự.`;
  }
  return null;
}

export function ManagerDecisionForm({ versionId, etag, sourceEvidenceRef, onDecided, onStale }: Props) {
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState<ManagerDecision | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingReject, setConfirmingReject] = useState(false);
  // Same intent (decision + payload) => same Idempotency-Key, so a retry after a lost
  // response (or a fail-closed 503) cannot apply the decision twice. Any change of intent gets a new key.
  const intentRef = useRef<{ fingerprint: string; key: string } | null>(null);

  async function send(decision: ManagerDecision) {
    const trimmed = reason.trim();
    const request: ManagerDecisionRequest = {
      decision,
      ...(trimmed ? { reason: trimmed } : {}),
      ...(decision === 'FINAL_APPROVE' && sourceEvidenceRef ? { sourceEvidenceRef } : {}),
    };
    const fingerprint = JSON.stringify([versionId, etag, request]);
    if (!intentRef.current || intentRef.current.fingerprint !== fingerprint) {
      intentRef.current = { fingerprint, key: newIdempotencyKey() };
    }

    setPending(decision);
    setError(null);
    try {
      const { data, etag: newEtag } = await submitManagerDecision(versionId, request, etag, intentRef.current.key);
      intentRef.current = null;
      onDecided(data, newEtag);
    } catch (err) {
      const message = describeManagerError(err);
      setError(message);
      if (err instanceof ApiError && RELOAD_STATUSES.has(err.status)) {
        intentRef.current = null;
        onStale(message);
      }
    } finally {
      setPending(null);
      setConfirmingReject(false);
    }
  }

  function decide(decision: ManagerDecision) {
    const validation = validateReason(decision, reason);
    if (validation) {
      setError(validation);
      setConfirmingReject(false);
      return;
    }
    setError(null);
    // REJECT is terminal: require an explicit second click before anything is sent.
    if (decision === 'REJECT' && !confirmingReject) {
      setConfirmingReject(true);
      return;
    }
    void send(decision);
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
  }

  const busy = pending !== null;

  return (
    <form className="decision" onSubmit={onSubmit} aria-label="Quyết định của Manager">
      <label htmlFor="manager-reason">Lý do (bắt buộc khi trả về hoặc từ chối)</label>
      <textarea
        id="manager-reason"
        value={reason}
        maxLength={REASON_MAX_LENGTH + 1}
        rows={4}
        onChange={(e) => {
          setReason(e.target.value);
          setConfirmingReject(false);
        }}
        disabled={busy}
        placeholder="Nhận xét/lý do quyết định…"
      />
      <div className="hint">
        {reason.length}/{REASON_MAX_LENGTH}
      </div>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {confirmingReject && !busy && (
        <p role="status" className="info">
          Từ chối là quyết định cuối cùng của phiên bản này. Bấm “Xác nhận từ chối” để tiếp tục.
        </p>
      )}

      <div className="actions">
        <button type="button" className="secondary" onClick={() => decide('RETURN_TO_STAFF')} disabled={busy}>
          {pending === 'RETURN_TO_STAFF' ? 'Đang gửi…' : DECISION_LABEL.RETURN_TO_STAFF}
        </button>
        <button type="button" className="secondary" onClick={() => decide('RETURN_TO_DEVELOPER')} disabled={busy}>
          {pending === 'RETURN_TO_DEVELOPER' ? 'Đang gửi…' : DECISION_LABEL.RETURN_TO_DEVELOPER}
        </button>
        <button type="button" className="danger" onClick={() => decide('REJECT')} disabled={busy}>
          {pending === 'REJECT' ? 'Đang gửi…' : confirmingReject ? 'Xác nhận từ chối' : DECISION_LABEL.REJECT}
        </button>
        <button type="button" className="primary" onClick={() => decide('FINAL_APPROVE')} disabled={busy}>
          {pending === 'FINAL_APPROVE' ? 'Đang gửi…' : DECISION_LABEL.FINAL_APPROVE}
        </button>
      </div>
    </form>
  );
}
