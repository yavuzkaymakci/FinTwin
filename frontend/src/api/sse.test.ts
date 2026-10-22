import { describe, expect, it } from 'vitest';
import { SseParser } from './sse';

describe('SseParser', () => {
  it('assembles events split across chunks', () => {
    const parser = new SseParser();
    expect(parser.push('event:token\ndata:{"text":"Mer')).toEqual([]);
    expect(parser.push('haba"}\n\nevent:done\r\ndata:{}\r\n\r\n')).toEqual([
      { event: 'token', data: '{"text":"Merhaba"}' },
      { event: 'done', data: '{}' },
    ]);
  });

  it('joins multi-line data and defaults the event name', () => {
    expect(new SseParser().push('data: a\ndata: b\n\n')).toEqual([{ event: 'message', data: 'a\nb' }]);
  });
});
