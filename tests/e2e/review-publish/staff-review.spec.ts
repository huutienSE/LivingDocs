/**
 * LIV-119 · S3-US01-T02 — UI E2E over the real stack (React → Gateway → S01/S04 → PostgreSQL).
 * No network mocking here: per LD-SPR3-GUIDE-001 §6.1, integration/E2E must not mock the
 * services being proven. Seed data and env vars: see README.md in this folder.
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

const env = (name: string): string => {
  const v = process.env[name];
  if (!v) throw new Error(`Missing env ${name} (see tests/e2e/review-publish/README.md)`);
  return v;
};

const GATEWAY = process.env.LD_GATEWAY_URL ?? 'http://127.0.0.1:8081';

async function uiLogin(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Mật khẩu').fill(password);
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  await expect(page.getByRole('heading', { name: 'Hàng chờ Staff Review' })).toBeVisible();
}

async function apiToken(request: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await request.post(`${GATEWAY}/api/v1/auth/login`, { data: { email, password } });
  expect(res.status()).toBe(200);
  return (await res.json()).accessToken as string;
}

async function readVersion(request: APIRequestContext, token: string, versionId: string) {
  const res = await request.get(`${GATEWAY}/api/v1/document-versions/${versionId}/review-context`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.status()).toBe(200);
  return { version: (await res.json()).version, etag: res.headers()['etag'] };
}

test.describe('Staff Review — LIV-119', () => {
  test('S3-UI-01 Staff A PASSes an own-tenant version and the new state is visible + persisted', async ({ page, request }) => {
    const versionId = env('LD_VERSION_A_UI_PASS');
    await uiLogin(page, env('LD_STAFF_A_EMAIL'), env('LD_STAFF_A_PASSWORD'));

    await page.goto(`/review/${versionId}`);
    await expect(page.getByTestId('version-state')).toContainText('Staff Review');
    await page.getByRole('button', { name: /PASS/ }).click();

    await expect(page.getByRole('status')).toContainText('Chờ Manager duyệt');

    // Readback through the Gateway: the decision is persisted, not just shown.
    const token = await apiToken(request, env('LD_STAFF_A_EMAIL'), env('LD_STAFF_A_PASSWORD'));
    const { version } = await readVersion(request, token, versionId);
    expect(version.state).toBe('IN_REVIEW');
    expect(version.reviewStage).toBe('MANAGER_APPROVAL');

    // And it has left the Staff queue.
    await page.goto('/review');
    await expect(page.getByRole('heading', { name: 'Hàng chờ Staff Review' })).toBeVisible();
    await expect(page.locator(`a[href="/review/${versionId}"]`)).toHaveCount(0);
  });

  test('S3-UI-02 RETURN requires feedback in the UI, then returns the version to DRAFT', async ({ page, request }) => {
    const versionId = env('LD_VERSION_A_UI_RETURN');
    await uiLogin(page, env('LD_STAFF_A_EMAIL'), env('LD_STAFF_A_PASSWORD'));
    await page.goto(`/review/${versionId}`);

    await page.getByRole('button', { name: /RETURN/ }).click();
    await expect(page.getByRole('alert')).toContainText('lý do');

    await page.getByLabel(/Feedback/).fill('Thiếu mô tả lỗi 409 trong phần API.');
    await page.getByRole('button', { name: /RETURN/ }).click();
    await expect(page.getByRole('status')).toContainText('DRAFT');

    const token = await apiToken(request, env('LD_STAFF_A_EMAIL'), env('LD_STAFF_A_PASSWORD'));
    expect((await readVersion(request, token, versionId)).version.state).toBe('DRAFT');
  });

  test('S3-UI-10 cross-tenant: Staff A opening a tenant B version sees no data/actions and B is NOT mutated', async ({ page, request }) => {
    const versionB = env('LD_VERSION_B');
    const tokenB = await apiToken(request, env('LD_STAFF_B_EMAIL'), env('LD_STAFF_B_PASSWORD'));
    const before = await readVersion(request, tokenB, versionB);

    await uiLogin(page, env('LD_STAFF_A_EMAIL'), env('LD_STAFF_A_PASSWORD'));
    // Tenant B's version must not even appear in A's queue.
    await expect(page.locator(`a[href="/review/${versionB}"]`)).toHaveCount(0);

    const decisionCalls: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/staff-decisions')) decisionCalls.push(r.url());
    });

    await page.goto(`/review/${versionB}`);
    await expect(page.getByRole('alert')).toContainText(/không có quyền|Không tìm thấy/);
    await expect(page.getByRole('button', { name: /PASS|RETURN/ })).toHaveCount(0);
    expect(decisionCalls).toHaveLength(0);

    const after = await readVersion(request, tokenB, versionB);
    expect(after.version.state).toBe(before.version.state);
    expect(after.version.reviewStage).toBe(before.version.reviewStage);
    expect(after.etag).toBe(before.etag);
  });

  test('S3-UI-11 cross-tenant direct API call with a Staff A browser session is denied and B is unchanged', async ({ request }) => {
    const versionB = env('LD_VERSION_B');
    const tokenA = await apiToken(request, env('LD_STAFF_A_EMAIL'), env('LD_STAFF_A_PASSWORD'));
    const tokenB = await apiToken(request, env('LD_STAFF_B_EMAIL'), env('LD_STAFF_B_PASSWORD'));
    const before = await readVersion(request, tokenB, versionB);

    const res = await request.post(`${GATEWAY}/api/v1/document-versions/${versionB}/staff-decisions`, {
      headers: {
        Authorization: `Bearer ${tokenA}`,
        'If-Match': before.etag,
        'Idempotency-Key': `e2e-xtenant-${Date.now()}`,
      },
      data: { decision: 'PASS', evidenceRefs: [] },
    });
    expect([403, 404]).toContain(res.status());

    const after = await readVersion(request, tokenB, versionB);
    expect(after.version.reviewStage).toBe('STAFF_REVIEW');
    expect(after.etag).toBe(before.etag);
  });

  test('S3-UI-12 non-Staff (Developer) cannot use the Staff queue', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Email').fill(env('LD_DEVELOPER_A_EMAIL'));
    await page.getByLabel('Mật khẩu').fill(env('LD_DEVELOPER_A_PASSWORD'));
    await page.getByRole('button', { name: 'Đăng nhập' }).click();
    await expect(page.getByRole('alert')).toContainText(/không có quyền/);
    await expect(page.getByRole('table')).toHaveCount(0);
  });

  test('S3-UI-13 unauthenticated user is sent to login; token is not in web storage or URL', async ({ page }) => {
    await page.goto('/review');
    await expect(page).toHaveURL(/\/login$/);

    await uiLogin(page, env('LD_STAFF_A_EMAIL'), env('LD_STAFF_A_PASSWORD'));
    const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(storage).not.toMatch(/eyJ[A-Za-z0-9_-]+\./); // no JWT persisted
    expect(page.url()).not.toMatch(/token=/i);
  });
});
