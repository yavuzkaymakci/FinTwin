import { identityHeaders } from '@/auth/identity';
import { ApiError } from './client';

export interface SseEvent {
  event: string;
  data: string;
}

/**
 * Incremental parser of a text/event-stream: feed chunks, get complete events (blank-line separated blocks with
 * "event:" and one or more "data:" lines).
 */
export class SseParser {
  private buffer = '';

  push(chunk: string): SseEvent[] {
    this.buffer += chunk.replace(/\r\n/g, '\n');
    const events: SseEvent[] = [];
    let index: number;
    while ((index = this.buffer.indexOf('\n\n')) >= 0) {
      const block = this.buffer.slice(0, index);
      this.buffer = this.buffer.slice(index + 2);
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length > 0) events.push({ event, data: data.join('\n') });
    }
    return events;
  }
}

/** POSTs a JSON body and delivers server-sent events (EventSource cannot POST or send headers). */
export async function postEventStream(path: string, body: unknown, onEvent: (e: SseEvent) => void,
                                      signal?: AbortSignal): Promise<void> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8', Accept: 'text/event-stream', ...identityHeaders() },
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok || !response.body) {
    let parsed: Record<string, unknown> | null = null;
    try {
      parsed = (await response.json()) as Record<string, unknown>;
    } catch {
      parsed = null;
    }
    throw new ApiError(response.status, parsed, `${response.status}: ${String(parsed?.detail ?? parsed?.error
      ?? response.statusText)}`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  const parser = new SseParser();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    parser.push(decoder.decode(value, { stream: true })).forEach(onEvent);
  }
  parser.push('\n\n').forEach(onEvent);
}
