import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, mockFetch } from '../../test/fetchMock';
import { clearSession, login } from '../../api/client/session';
import { ReviewDetailPage } from './ReviewDetailPage';
import { FEEDBACK_MAX_LENGTH, type ReviewContextView } from './types';
import { validateFeedback } from './StaffDecisionForm';

const VERSION_ID = 'ver-a-1';

function context(overrides: Partial<ReviewContextView['version']> = {}): ReviewContextView {
  return {
    version: {
      documentId: 'doc-a',
      versionId: VERSION_ID,
      state: 'IN_REVIEW',
      reviewStage: 'STAFF_REVIEW',
      revision: 3,
      sourceBinding: { headSha: 'abc1234def' },
      ...overrides,
    },
    documentTitle: 'Payment API Guide',
    versionNo: 2,
    content: '# Payment API',
    submittedBy: 'dev.a',
    evidence: [{ ref: 'ev-1', label: 'Diff abc1234' }],
    reviewRound: 1,
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/review/${VERSION_ID}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/review/:versionId" element={<ReviewDetailPage />} />
        <Route path="/review" element={<p>queue</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ReviewDetailPage + StaffDecisionForm', () => {
  beforeEach(async () => {
    clearSession();
    mockFetch(() => json(200, { accessToken: 'staff-a', tokenType: 'Bearer', expiresIn: 300 }));
    await login('staff.a@example.test', 'pw');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('PASS sends API-120 with If-Match, Idempotency-Key and shows MANAGER_APPROVAL', async () => {
    const m = mockFetch(
      () => json(200, context(), { ETag: '"rev-3"' }),
      () =>
        json(
          200,
          { documentId: 'doc-a', versionId: VERSION_ID, state: 'IN_REVIEW', reviewStage: 'MANAGER_APPROVAL', revision: 4 },
          { ETag: '"rev-4"' },
        ),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /PASS/ }));

    expect(await screen.findByRole('status')).toHaveTextContent('Chờ Manager duyệt');
    const post = m.calls[1];
    expect(post.method).toBe('POST');
    expect(post.url).toBe(`/api/v1/document-versions/${VERSION_ID}/staff-decisions`);
    expect(post.headers['if-match']).toBe('"rev-3"');
    expect(post.headers['idempotency-key']).toBeTruthy();
    expect(post.body).toEqual({ decision: 'PASS', evidenceRefs: ['ev-1'] });
    expect(screen.queryByRole('button', { name: /PASS/ })).not.toBeInTheDocument();
  });

  it('RETURN without feedback is blocked client-side and sends nothing', async () => {
    const m = mockFetch(() => json(200, context(), { ETag: '"rev-3"' }));
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /RETURN/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('lý do');
    expect(m.calls).toHaveLength(1);
  });

  it('RETURN with feedback moves the version back to DRAFT', async () => {
    const m = mockFetch(
      () => json(200, context(), { ETag: '"rev-3"' }),
      () => json(200, { documentId: 'doc-a', versionId: VERSION_ID, state: 'DRAFT', reviewStage: null, revision: 4 }),
    );
    renderPage();
    await userEvent.type(await screen.findByLabelText(/Feedback/), '  Thiếu ví dụ request  ');
    await userEvent.click(screen.getByRole('button', { name: /RETURN/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('DRAFT');
    expect(m.calls[1].body).toEqual({
      decision: 'RETURN_FOR_REVISION',
      feedback: 'Thiếu ví dụ request',
      evidenceRefs: ['ev-1'],
    });
  });

  it('cross-tenant / out-of-scope version: shows a neutral error and no action buttons', async () => {
    const m = mockFetch(() => json(404, { status: 404, code: 'FORBIDDEN_SCOPE' }));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent(/không có quyền/);
    expect(screen.queryByRole('button', { name: /PASS|RETURN/ })).not.toBeInTheDocument();
    expect(m.calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('retries loading review context after a read error', async () => {
    const m = mockFetch(
      () => json(503, { status: 503 }),
      () => json(200, context(), { ETag: '"rev-3"' }),
    );
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent(/tạm thời không khả dụng/);
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { name: /Payment API Guide/ })).toBeInTheDocument();
    expect(m.calls).toHaveLength(2);
  });

  it('403 on the decision: shows error, re-reads server state, does not show success', async () => {
    const m = mockFetch(
      () => json(200, context(), { ETag: '"rev-3"' }),
      () => json(403, { status: 403, code: 'FORBIDDEN_SCOPE' }),
      () => json(404, { status: 404 }),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /PASS/ }));
    await waitFor(() => expect(m.calls).toHaveLength(3));
    expect(m.calls[2].method).toBe('GET');
    expect(screen.queryByText(/Đã ghi nhận quyết định/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /PASS/ })).not.toBeInTheDocument();
  });

  it('412 stale ETag: reloads and uses the new ETag with a NEW idempotency key next time', async () => {
    const m = mockFetch(
      () => json(200, context(), { ETag: '"rev-3"' }),
      () => json(412, { status: 412, code: 'REVISION_CONFLICT' }),
      () => json(200, context({ revision: 5 }), { ETag: '"rev-5"' }),
      () => json(200, { documentId: 'doc-a', versionId: VERSION_ID, state: 'IN_REVIEW', reviewStage: 'MANAGER_APPROVAL', revision: 6 }),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /PASS/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/người khác cập nhật/);
    await waitFor(() => expect(m.calls).toHaveLength(3));

    await userEvent.click(screen.getByRole('button', { name: /PASS/ }));
    await screen.findByText(/Đã ghi nhận quyết định/);
    expect(m.calls[3].headers['if-match']).toBe('"rev-5"');
    expect(m.calls[3].headers['idempotency-key']).not.toBe(m.calls[1].headers['idempotency-key']);
  });

  it('409 STATE_TRANSITION_FORBIDDEN: reloads and hides actions when no longer in STAFF_REVIEW', async () => {
    mockFetch(
      () => json(200, context(), { ETag: '"rev-3"' }),
      () => json(409, { status: 409, code: 'STATE_TRANSITION_FORBIDDEN' }),
      () => json(200, context({ reviewStage: 'MANAGER_APPROVAL', revision: 4 }), { ETag: '"rev-4"' }),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /PASS/ }));
    expect(await screen.findByText(/không còn ở bước Staff Review/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /PASS/ })).not.toBeInTheDocument();
  });

  it('retry after a network failure reuses the same Idempotency-Key', async () => {
    let call = 0;
    const m = mockFetch(
      () => json(200, context(), { ETag: '"rev-3"' }),
      () => {
        call += 1;
        throw new TypeError('Failed to fetch');
      },
      () => json(200, { documentId: 'doc-a', versionId: VERSION_ID, state: 'IN_REVIEW', reviewStage: 'MANAGER_APPROVAL', revision: 4 }),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /PASS/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Gateway/);
    await userEvent.click(screen.getByRole('button', { name: /PASS/ }));
    await screen.findByText(/Đã ghi nhận quyết định/);
    expect(call).toBe(1);
    expect(m.calls[2].headers['idempotency-key']).toBe(m.calls[1].headers['idempotency-key']);
  });

  it('does not offer actions for versions that are not in STAFF_REVIEW', async () => {
    mockFetch(() => json(200, context({ state: 'DRAFT', reviewStage: null }), { ETag: '"rev-3"' }));
    renderPage();
    expect(await screen.findByText(/không còn ở bước Staff Review/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /PASS/ })).not.toBeInTheDocument();
  });

  it('refuses to act when the server sends no ETag (cannot send If-Match)', async () => {
    mockFetch(() => json(200, context()));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent(/ETag/);
    expect(screen.queryByRole('button', { name: /PASS/ })).not.toBeInTheDocument();
  });
});

describe('validateFeedback (equivalence classes + BVA)', () => {
  it.each([
    ['RETURN_FOR_REVISION', '', false],
    ['RETURN_FOR_REVISION', '   ', false],
    ['RETURN_FOR_REVISION', 'x', true],
    ['RETURN_FOR_REVISION', 'x'.repeat(FEEDBACK_MAX_LENGTH), true],
    ['RETURN_FOR_REVISION', 'x'.repeat(FEEDBACK_MAX_LENGTH + 1), false],
    ['PASS', '', true],
    ['PASS', 'x'.repeat(FEEDBACK_MAX_LENGTH + 1), false],
  ] as const)('%s with %j chars -> valid=%s', (decision, feedback, valid) => {
    expect(validateFeedback(decision, feedback) === null).toBe(valid);
  });
});
