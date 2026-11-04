import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { describeError } from '../../api/client/problem';
import { fetchReviewQueue } from './api';
import type { ReviewQueueView } from './types';

const PAGE_SIZE = 20;

export function ReviewQueuePage() {
  const [page, setPage] = useState(0);
  const [queue, setQueue] = useState<ReviewQueueView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [reloadTick, setReloadTick] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    fetchReviewQueue({ page, size: PAGE_SIZE }, controller.signal)
      .then(({ data }) => setQueue(data))
      .catch((err) => {
        if (controller.signal.aborted) return;
        setQueue(null);
        setError(describeError(err));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, reloadTick]);

  const totalPages = queue ? Math.max(1, Math.ceil(queue.totalElements / (queue.size || PAGE_SIZE))) : 1;

  return (
    <section>
      <header className="page-header">
        <h1>Hàng chờ Staff Review</h1>
        <button type="button" className="secondary" onClick={() => setReloadTick((t) => t + 1)} disabled={loading}>
          Tải lại
        </button>
      </header>

      {loading && <p aria-busy="true">Đang tải…</p>}

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {!loading && !error && queue && queue.items.length === 0 && (
        <p className="empty">Không có phiên bản nào đang chờ Staff Review.</p>
      )}

      {!loading && !error && queue && queue.items.length > 0 && (
        <>
          <table className="queue">
            <thead>
              <tr>
                <th>Tài liệu</th>
                <th>Phiên bản</th>
                <th>Người gửi</th>
                <th>Gửi lúc</th>
                <th>Source SHA</th>
              </tr>
            </thead>
            <tbody>
              {queue.items.map((item) => (
                <tr key={item.versionId}>
                  <td>
                    <Link to={`/review/${encodeURIComponent(item.versionId)}`}>{item.documentTitle}</Link>
                  </td>
                  <td>v{item.versionNo}</td>
                  <td>{item.submittedBy ?? '—'}</td>
                  <td>{item.submittedAt ? new Date(item.submittedAt).toLocaleString('vi-VN') : '—'}</td>
                  <td>
                    <code>{item.sourceSha ? item.sourceSha.slice(0, 10) : '—'}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <nav className="pager" aria-label="Phân trang">
            <button type="button" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
              Trước
            </button>
            <span>
              Trang {page + 1}/{totalPages}
            </span>
            <button type="button" onClick={() => setPage((p) => p + 1)} disabled={page + 1 >= totalPages}>
              Sau
            </button>
          </nav>
        </>
      )}
    </section>
  );
}
