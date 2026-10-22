import { identityHeaders } from '@/auth/identity';

/** An HTTP error from the backend, with the parsed JSON body when there is one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: Record<string, unknown> | null,
    message: string,
  ) {
    super(message);
  }

  /** Machine-readable error code of the backend (e.g. ML_SERVICE_UNAVAILABLE), if present. */
  get code(): string | null {
    const value = this.body?.error;
    return typeof value === 'string' ? value : null;
  }
}

type Query = Record<string, string | number | boolean | null | undefined | (string | number)[]>;

export function withQuery(path: string, query?: Query): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue;
    params.set(key, Array.isArray(value) ? value.join(',') : String(value));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

/** JSON request to the backend with the current identity headers. */
export async function api<T>(path: string, init: { method?: string; body?: unknown; query?: Query } = {}): Promise<T> {
  const response = await fetch(withQuery(path, init.query), {
    method: init.method ?? 'GET',
    headers: {
      Accept: 'application/json',
      ...(init.body !== undefined ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      ...identityHeaders(),
    },
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
  const text = await response.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }
  if (!response.ok) {
    const record = (body && typeof body === 'object' ? body : null) as Record<string, unknown> | null;
    const detail = record?.detail ?? record?.error ?? record?.message ?? response.statusText;
    throw new ApiError(response.status, record, `${response.status}: ${String(detail)}`);
  }
  return body as T;
}
