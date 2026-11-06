import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, mockFetch } from '../../test/fetchMock';
import { clearSession, login } from '../../api/client/session';
import { ReviewQueuePage } from './ReviewQueuePage';

const item = (n: number) => ({
  versionId: `ver-${n}`,
  documentId: `doc-${n}`,
  documentTitle: `Doc ${n}`,
  versionNo: 1,
  state: 'IN_REVIEW',
  reviewStage: 'STAFF_REVIEW',
  sourceSha: 'abcdef1234567890',
  submittedBy: 'dev.a',
  submittedAt: '2026-11-04T10:00:00Z',
});

function renderPage() {
  return render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <ReviewQueuePage />
    </MemoryRouter>,
  );
}

describe('ReviewQueuePage (API-115)', () => {
  beforeEach(async () => {
    clearSession();
    mockFetch(() => json(200, { accessToken: 'staff-a', tokenType: 'Bearer', expiresIn: 300 }));
    await login('staff.a@example.test', 'pw');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('lists queue items with links to detail and calls the queue with paging', async () => {
    const m = mockFetch(() => json(200, { items: [item(1), item(2)], page: 0, size: 20, totalElements: 2 }));
    renderPage();
    const link = await screen.findByRole('link', { name: 'Doc 1' });
    expect(link).toHaveAttribute('href', '/review/ver-1');
    expect(m.calls[0].url).toBe('/api/v1/review-queue?page=0&size=20');
    expect(m.calls[0].headers.authorization).toBe('Bearer staff-a');
  });

  it('shows empty state', async () => {
    mockFetch(() => json(200, { items: [], page: 0, size: 20, totalElements: 0 }));
    renderPage();
    expect(await screen.findByText(/Không có phiên bản nào/)).toBeInTheDocument();
  });

  it('shows loading, then an error, and retries the read into an empty state', async () => {
    let resolveFirst!: (response: Response) => void;
    const first = new Promise<Response>((resolve) => { resolveFirst = resolve; });
    const m = mockFetch(
      () => first,
      () => json(200, { items: [], page: 0, size: 20, totalElements: 0 }),
    );
    renderPage();
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');

    resolveFirst(json(503, { status: 503 }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/tạm thời không khả dụng/);
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText(/Không có phiên bản nào/)).toBeInTheDocument();
    expect(m.calls).toHaveLength(2);
  });

  it('shows a permission error for non-Staff users (403) without rendering items', async () => {
    mockFetch(() => json(403, { status: 403, code: 'FORBIDDEN_SCOPE' }));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent(/không có quyền/);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('paginates', async () => {
    const m = mockFetch(
      () => json(200, { items: [item(1)], page: 0, size: 20, totalElements: 25 }),
      () => json(200, { items: [item(21)], page: 1, size: 20, totalElements: 25 }),
    );
    renderPage();
    await screen.findByText('Trang 1/2');
    await userEvent.click(screen.getByRole('button', { name: 'Sau' }));
    await screen.findByRole('link', { name: 'Doc 21' });
    expect(m.calls[1].url).toBe('/api/v1/review-queue?page=1&size=20');
    expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled();
  });
});
