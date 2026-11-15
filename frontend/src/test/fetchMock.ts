import { vi } from 'vitest';

export interface RecordedCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

type Handler = (call: RecordedCall) => Response | Promise<Response>;

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  const isProblem = status >= 400;
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': isProblem ? 'application/problem+json' : 'application/json', ...headers },
  });
}

/** Installs a fetch mock that records every call and answers with queued handlers in order. */
export function mockFetch(...handlers: Handler[]) {
  const calls: RecordedCall[] = [];
  const queue = [...handlers];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call: RecordedCall = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers: Object.fromEntries(new Headers(init?.headers).entries()),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    };
    calls.push(call);
    const next = queue.shift();
    if (!next) throw new Error(`Unexpected fetch: ${call.method} ${call.url}`);
    return next(call);
  });
  vi.stubGlobal('fetch', fn);
  return { calls, fn, remaining: () => queue.length };
}
