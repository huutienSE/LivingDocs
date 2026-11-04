import { useRef, useState, type FormEvent } from 'react';
import { ApiError, describeError } from '../../api/client/problem';
import { newIdempotencyKey } from '../../api/client/http';
import { submitStaffDecision } from './api';
import {
  FEEDBACK_MAX_LENGTH,
  type DocumentVersionView,
  type StaffDecision,
  type StaffDecisionRequest,
} from './types';

interface Props {
  versionId: string;
  etag: string;
  evidenceRefs: string[];
  onDecided: (view: DocumentVersionView, newEtag: string | null) => void;
  /** Called after a failure that means our copy is stale (409/412/403/404): parent must re-read. */
  onStale: () => void;
}

/** Status codes after which the server state must be re-read rather than retried blindly. */
const RELOAD_STATUSES = new Set([403, 404, 409, 412, 428]);

export function validateFeedback(decision: StaffDecision, feedback: string): string | null {
  const trimmed = feedback.trim();
  if (decision === 'RETURN_FOR_REVISION' && trimmed.length === 0) {
    return 'Trả về cần có lý do/feedback cho Developer.';
  }
  if (feedback.length > FEEDBACK_MAX_LENGTH) {
    return `Feedback tối đa ${FEEDBACK_MAX_LENGTH} ký tự.`;
  }
  return null;
}

export function StaffDecisionForm({ versionId, etag, evidenceRefs, onDecided, onStale }: Props) {
  const [feedback, setFeedback] = useState('');
  const [pending, setPending] = useState<StaffDecision | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Same intent (decision + payload) => same Idempotency-Key, so a retry after a lost
  // response cannot apply the decision twice. Any change of intent gets a new key.
  const intentRef = useRef<{ fingerprint: string; key: string } | null>(null);

  async function decide(decision: StaffDecision) {
    const validation = validateFeedback(decision, feedback);
    if (validation) {
      setError(validation);
      return;
    }

    const trimmed = feedback.trim();
    const request: StaffDecisionRequest = {
      decision,
      evidenceRefs,
      ...(trimmed ? { feedback: trimmed } : {}),
    };
    const fingerprint = JSON.stringify([versionId, etag, request]);
    if (!intentRef.current || intentRef.current.fingerprint !== fingerprint) {
      intentRef.current = { fingerprint, key: newIdempotencyKey() };
    }

    setPending(decision);
    setError(null);
    try {
      const { data, etag: newEtag } = await submitStaffDecision(versionId, request, etag, intentRef.current.key);
      intentRef.current = null;
      onDecided(data, newEtag);
    } catch (err) {
      setError(describeError(err));
      if (err instanceof ApiError && RELOAD_STATUSES.has(err.status)) {
        intentRef.current = null;
        onStale();
      }
    } finally {
      setPending(null);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
  }

  const busy = pending !== null;

  return (
    <form className="decision" onSubmit={onSubmit} aria-label="Quyết định Staff Review">
      <label htmlFor="staff-feedback">Feedback (bắt buộc khi trả về)</label>
      <textarea
        id="staff-feedback"
        value={feedback}
        maxLength={FEEDBACK_MAX_LENGTH + 1}
        rows={4}
        onChange={(e) => setFeedback(e.target.value)}
        disabled={busy}
        placeholder="Nhận xét cho Developer…"
      />
      <div className="hint">
        {feedback.length}/{FEEDBACK_MAX_LENGTH}
      </div>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <div className="actions">
        <button
          type="button"
          className="secondary"
          onClick={() => decide('RETURN_FOR_REVISION')}
          disabled={busy}
        >
          {pending === 'RETURN_FOR_REVISION' ? 'Đang gửi…' : 'Trả về Developer (RETURN)'}
        </button>
        <button type="button" className="primary" onClick={() => decide('PASS')} disabled={busy}>
          {pending === 'PASS' ? 'Đang gửi…' : 'Đạt (PASS)'}
        </button>
      </div>
    </form>
  );
}
