import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, mockFetch } from '../../test/fetchMock';
import { apiRequest } from './http';
import { ApiError, NetworkError, describeError } from './problem';
import { clearSession, getAccessToken, login, refreshAccessToken } from './session';
import {logout} from "./session";

const token = (t: string) => json(200, { accessToken: t, tokenType: 'Bearer', expiresIn: 300 });

async function signIn(t = 'access-1') {
  mockFetch(() => token(t));
  await login('staff.a@example.test', 'pw');
}

describe('session + http client', () => {
  beforeEach(() => clearSession());
  afterEach(() => vi.unstubAllGlobals());

  it('keeps the access token in memory only, never in web storage', async () => {
    await signIn('access-1');
    expect(getAccessToken()).toBe('access-1');
    expect(JSON.stringify({ ...localStorage })).not.toContain('access-1');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('access-1');
  });

  it('sends login with credentials so the refresh cookie can be set', async () => {
    const m = mockFetch(() => token('a'));
    await login('staff.a@example.test', 'pw');
    expect(m.calls[0].url).toBe('/api/v1/auth/login');
    expect((m.fn.mock.calls[0][1] as RequestInit).credentials).toBe('include');
  });

  it('attaches Bearer token and never sends role/tenant headers', async () => {
    await signIn('access-1');
    const m = mockFetch(() => json(200, { ok: true }, { ETag: '"r1"' }));
    const res = await apiRequest<{ ok: boolean }>('/api/v1/review-queue');
    expect(m.calls[0].headers.authorization).toBe('Bearer access-1');
    const sent = Object.keys(m.calls[0].headers).join(',');
    expect(sent).not.toMatch(/x-user-role|x-tenant/i);
    expect(res.etag).toBe('"r1"');
  });

  it('on 401 refreshes once and replays the same request with the same Idempotency-Key and If-Match', async () => {
    await signIn('old');
    const m = mockFetch(
      () => json(401, { status: 401, code: 'UNAUTHENTICATED' }),
      () => token('new'),
      () => json(200, { state: 'IN_REVIEW' }),
    );
    await apiRequest('/api/v1/document-versions/v1/staff-decisions', {
      method: 'POST',
      body: { decision: 'PASS', evidenceRefs: [] },
      ifMatch: '"rev-3"',
      idempotencyKey: 'key-1',
    });
    expect(m.calls.map((c) => c.url)).toEqual([
      '/api/v1/document-versions/v1/staff-decisions',
      '/api/v1/auth/refresh',
      '/api/v1/document-versions/v1/staff-decisions',
    ]);
    expect(m.calls[2].headers.authorization).toBe('Bearer new');
    expect(m.calls[2].headers['idempotency-key']).toBe('key-1');
    expect(m.calls[2].headers['if-match']).toBe('"rev-3"');
  });

  it('clears the session when refresh fails and surfaces 401', async () => {
    await signIn('old');
    mockFetch(
      () => json(401, { status: 401, code: 'UNAUTHENTICATED' }),
      () => json(401, { status: 401 }),
    );
    await expect(apiRequest('/api/v1/review-queue')).rejects.toMatchObject({ status: 401 });
    expect(getAccessToken()).toBeNull();
  });

  it('shares one refresh between concurrent callers (no refresh-token reuse)', async () => {
    let resolve!: (r: Response) => void;
    const m = mockFetch(() => new Promise<Response>((r) => (resolve = r)));
    const a = refreshAccessToken();
    const b = refreshAccessToken();
    resolve(token('shared'));
    expect(await a).toBe('shared');
    expect(await b).toBe('shared');
    expect(m.calls).toHaveLength(1);
  });

  it('parses problem+json into ApiError with code', async () => {
    await signIn();
    mockFetch(() => json(403, { status: 403, code: 'FORBIDDEN_SCOPE', title: 'Forbidden' }));
    const err = await apiRequest('/api/v1/review-queue').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('FORBIDDEN_SCOPE');
    expect(describeError(err)).toMatch(/không có quyền/);
  });

  it('maps network failures to NetworkError, not a business outcome', async () => {
    await signIn();
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(apiRequest('/api/v1/review-queue')).rejects.toBeInstanceOf(NetworkError);
  });

  it('does not leak whether a version exists in another tenant (403 and 404 read the same)', () => {
    const a = describeError(new ApiError({ status: 403 }));
    const b = describeError(new ApiError({ status: 404 }));
    expect(a).toBe(b);
  });

  it('does not restore a token when refresh finishes after logout', async () => {
    await signIn('old-access');

    let finishRefresh!: (response: Response) => void;

    mockFetch(
      // Request refresh: giữ pending đến khi test chủ động trả kết quả.
      () =>
        new Promise<Response>((resolve) => {
          finishRefresh = resolve;
        }),

      // Request logout.
      () => json(200, {}),
    );

    const refreshing = refreshAccessToken();

    // Logout xảy ra khi refresh còn pending.
    await logout();
    expect(getAccessToken()).toBeNull();

    // Backend trả refresh thành công sau logout.
    finishRefresh(
      json(200, {
        accessToken: 'new-access',
        tokenType: 'Bearer',
        expiresIn: 300,
      }),
    );

    expect(await refreshing).toBeNull();
    expect(getAccessToken()).toBeNull();
  });
});
