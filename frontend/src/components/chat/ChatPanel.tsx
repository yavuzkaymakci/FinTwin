import * as React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, History, MessageSquarePlus, Send, Sparkles, User } from 'lucide-react';
import { ApiError } from '@/api/client';
import { type AssistantKind, assistantApi } from '@/api/endpoints';
import { postEventStream } from '@/api/sse';
import type { ChatAnswer, ToolCall } from '@/api/types';
import { useIdentity } from '@/auth/identity';
import { Spinner } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Badge, Card, Textarea } from '@/components/ui/primitives';
import { formatDateTime } from '@/lib/format';
import { useSystemStatus } from '@/lib/queries';
import { cn } from '@/lib/utils';
import {
  ConfirmationCard, GoalCard, Markdown, pendingConfirmation, RunLinks, ToolTag, ValidationWarning,
} from './parts';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  toolCalls: ToolCall[];
  pendingTools: string[];
  runIds: number[];
  validation: ChatAnswer['validation'];
  status?: string;
  streaming?: boolean;
  error?: string;
}

let counter = 0;
const nextId = () => `m${++counter}`;

function fromAnswer(a: ChatAnswer): Partial<ChatMessage> {
  return { text: a.answer, toolCalls: a.toolCalls, pendingTools: [], runIds: a.runIds, validation: a.validation,
    status: a.status, streaming: false };
}

/** Explains how to enable a chat model when none is configured (the backend answers 503 then). */
export function ProviderNotice() {
  return (
    <div className="rounded-lg border border-info/30 bg-info-soft/60 p-5 text-sm" role="status">
      <div className="mb-1 flex items-center gap-2 font-semibold text-info"><Sparkles className="size-4" />
        Yapay zekâ sağlayıcısı yapılandırılmadı</div>
      <p className="mb-3 text-muted-foreground">Backend bir dil modeli olmadan çalışıyor; asistan cevap veremez. Açmak
        için backend'i bir sağlayıcı profiliyle başlatın:</p>
      <ul className="space-y-1.5 text-xs">
        <li><Badge tone="outline">Demo</Badge> <code className="rounded bg-muted px-1">scripts\start-all.bat</code>{' '}
          (varsayılan <code>ai-demo</code> profili: hazır yanıtlar, gerçek araçlar)</li>
        <li><Badge tone="outline">Anthropic</Badge> <code className="rounded bg-muted px-1">set ANTHROPIC_API_KEY=…</code>{' '}
          ve <code className="rounded bg-muted px-1">scripts\start-all.bat anthropic</code></li>
        <li><Badge tone="outline">Ollama</Badge> <code className="rounded bg-muted px-1">ollama pull qwen2.5:7b-instruct</code>
          {' '}ve <code className="rounded bg-muted px-1">scripts\start-all.bat ollama</code></li>
      </ul>
    </div>
  );
}

export function ChatPanel({ kind, title, intro, suggestions }: {
  kind: AssistantKind; title: string; intro: React.ReactNode; suggestions: string[];
}) {
  const identity = useIdentity();
  const status = useSystemStatus();
  const client = useQueryClient();
  const [messages, setMessages] = React.useState<ChatMessage[]>([]);
  const [conversationId, setConversationId] = React.useState<string | null>(null);
  const [input, setInput] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [notConfigured, setNotConfigured] = React.useState(false);
  const bottom = React.useRef<HTMLDivElement>(null);
  const abort = React.useRef<AbortController | null>(null);
  const conversations = useQuery({ queryKey: ['conversations', kind, identity.userId, identity.customerId],
    queryFn: () => assistantApi.conversations(kind) });

  React.useEffect(() => {  // a different user or customer starts from scratch
    abort.current?.abort();
    setMessages([]);
    setConversationId(null);
  }, [identity.userId, identity.customerId]);

  React.useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages]);

  const ai = status.data?.ai;
  const unavailable = notConfigured || (ai !== undefined && !ai.configured);

  const update = (id: string, change: Partial<ChatMessage> | ((m: ChatMessage) => Partial<ChatMessage>)) =>
    setMessages((all) => all.map((m) => (m.id === id ? { ...m, ...(typeof change === 'function' ? change(m) : change) } : m)));

  const send = async (text: string) => {
    const message = text.trim();
    if (!message || busy) return;
    setInput('');
    setBusy(true);
    const user: ChatMessage = { id: nextId(), role: 'user', text: message, toolCalls: [], pendingTools: [], runIds: [],
      validation: null };
    const reply: ChatMessage = { id: nextId(), role: 'assistant', text: '', toolCalls: [], pendingTools: [], runIds: [],
      validation: null, streaming: true };
    setMessages((all) => [...all, user, reply]);
    const controller = new AbortController();
    abort.current = controller;
    try {
      await postEventStream(`/api/assistants/${kind}/chat/stream`, { message, conversationId }, (e) => {
        const data = JSON.parse(e.data) as Record<string, unknown>;
        if (e.event === 'conversation') setConversationId(String(data.conversationId));
        else if (e.event === 'tool') update(reply.id, (m) => ({ text: '', pendingTools: [...m.pendingTools,
          ...(data.names as string[])] }));
        else if (e.event === 'token') update(reply.id, (m) => ({ text: m.text + String(data.text) }));
        else if (e.event === 'done') update(reply.id, fromAnswer(data as unknown as ChatAnswer));
        else if (e.event === 'error') update(reply.id, { streaming: false, error: String(data.detail ?? data.error) });
      }, controller.signal);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'ASSISTANT_NOT_CONFIGURED') setNotConfigured(true);
      update(reply.id, { streaming: false, error: error instanceof ApiError ? (error.status === 429
        ? 'Çok fazla istek gönderdiniz; birkaç dakika sonra tekrar deneyin.' : error.status === 503
          ? 'Yapay zekâ sağlayıcısı yapılandırılmadı.' : error.message)
        : 'Bağlantı kesildi.' });
    } finally {
      update(reply.id, (m) => (m.streaming ? { streaming: false } : {}));
      setBusy(false);
      client.invalidateQueries({ queryKey: ['conversations', kind] });
    }
  };

  const open = async (id: string) => {
    abort.current?.abort();
    const turns = await assistantApi.history(kind, id);
    setConversationId(id);
    setMessages(turns.flatMap((t) => [
      { id: nextId(), role: 'user' as const, text: t.question, toolCalls: [], pendingTools: [], runIds: [], validation: null },
      { id: nextId(), role: 'assistant' as const, text: t.answer ?? '', toolCalls: t.toolCalls ?? [], pendingTools: [],
        runIds: t.runIds, validation: t.validation, status: t.status },
    ]));
  };

  const fresh = () => {
    abort.current?.abort();
    setMessages([]);
    setConversationId(null);
  };

  const last = messages[messages.length - 1];
  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_260px]">
      <Card className="flex h-[calc(100vh-12rem)] min-h-[520px] flex-col overflow-hidden">
        <div className="flex items-center justify-between gap-2 border-b px-4 py-3">
          <div className="flex items-center gap-2">
            <div className="rounded-md bg-primary p-1.5 text-primary-foreground"><Bot className="size-4" /></div>
            <div className="font-semibold">{title}</div>
            {ai?.demo && <Badge tone="warning">Demo modu · yanıtlar önceden hazırlanmış</Badge>}
            {ai?.configured && !ai.demo && <Badge tone="primary">{ai.provider}{ai.model ? ` · ${ai.model}` : ''}</Badge>}
          </div>
          <Button size="sm" variant="ghost" onClick={fresh}><MessageSquarePlus /> Yeni konuşma</Button>
        </div>
        <div className="flex-1 space-y-5 overflow-y-auto px-4 py-5">
          {!messages.length && (
            <div className="mx-auto max-w-2xl space-y-5 py-6">
              <div className="text-center text-sm text-muted-foreground">{intro}</div>
              {unavailable ? <ProviderNotice /> : (
                <div className="grid gap-2 sm:grid-cols-2">
                  {suggestions.map((s) => (
                    <button key={s} onClick={() => send(s)} className="rounded-lg border bg-card p-3 text-left text-sm
                      transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-card">{s}</button>
                  ))}
                </div>
              )}
            </div>
          )}
          {messages.map((m) => (
            <div key={m.id} className={cn('flex animate-fade-up gap-3', m.role === 'user' && 'flex-row-reverse')}>
              <div className={cn('flex size-7 shrink-0 items-center justify-center rounded-full',
                m.role === 'user' ? 'bg-secondary text-secondary-foreground' : 'bg-accent text-accent-foreground')}>
                {m.role === 'user' ? <User className="size-4" /> : <Bot className="size-4" />}
              </div>
              <div className={cn('min-w-0 max-w-[85%] rounded-2xl px-4 py-3', m.role === 'user'
                ? 'rounded-tr-sm bg-primary text-primary-foreground' : 'rounded-tl-sm border bg-card')}>
                {m.role === 'user' ? <div className="whitespace-pre-wrap text-sm">{m.text}</div> : (
                  <>
                    {(m.toolCalls.length > 0 || m.pendingTools.length > 0) && (
                      <div className="mb-2 flex flex-wrap gap-1.5">
                        {m.toolCalls.length > 0 ? m.toolCalls.map((c, i) => <ToolTag key={`${c.name}${i}`} call={c} />)
                          : m.pendingTools.map((n, i) => <ToolTag key={`${n}${i}`} call={{ name: n }} pending={m.streaming} />)}
                      </div>
                    )}
                    {m.text ? <Markdown text={m.text} /> : m.streaming
                      ? <div className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner />
                        {m.pendingTools.length ? 'Araçlar çalışıyor…' : 'Düşünüyor…'}</div> : null}
                    {m.error && <div className="mt-2 text-sm text-negative" role="alert">{m.error}</div>}
                    {m.toolCalls.filter((c) => c.name === 'simulateSavingsGoal').map((c, i) => <GoalCard key={i} call={c} />)}
                    <RunLinks runIds={m.runIds} />
                    {m === last && !busy && (() => {
                      const pending = pendingConfirmation(m.toolCalls);
                      return pending ? <ConfirmationCard pending={pending} disabled={busy}
                        onConfirm={() => send('Evet, onaylıyorum, simülasyonu başlat.')}
                        onCancel={() => send('Hayır, vazgeçtim, başlatma.')} /> : null;
                    })()}
                    <div className="mt-1.5 flex items-center gap-3"><ValidationWarning validation={m.validation} />
                      {m.status === 'TOOL_LIMIT' && <span className="text-[11px] text-warning">Araç sınırına ulaşıldı</span>}
                    </div>
                  </>
                )}
              </div>
            </div>
          ))}
          <div ref={bottom} />
        </div>
        <form className="border-t p-3" onSubmit={(e) => { e.preventDefault(); void send(input); }}>
          <div className="flex items-end gap-2">
            <Textarea rows={1} value={input} disabled={unavailable} onChange={(e) => setInput(e.target.value)}
              placeholder={unavailable ? 'Yapay zekâ sağlayıcısı yapılandırılmadı' : 'Sorunuzu yazın… (Enter gönderir)'}
              className="max-h-40 min-h-[40px] resize-none" aria-label="Mesaj"
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void send(input); } }} />
            <Button type="submit" size="icon" disabled={busy || unavailable || !input.trim()} aria-label="Gönder">
              {busy ? <Spinner /> : <Send />}
            </Button>
          </div>
        </form>
      </Card>
      <Card className="hidden h-fit p-3 lg:block">
        <div className="mb-2 flex items-center gap-2 px-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          <History className="size-3.5" /> Konuşma geçmişi</div>
        {!conversations.data?.length ? <div className="px-1 py-2 text-xs text-muted-foreground">Henüz konuşma yok.</div> : (
          <ul className="space-y-1">
            {conversations.data.map((c) => (
              <li key={c.id}>
                <button onClick={() => void open(c.id)} className={cn('w-full rounded-md px-2 py-1.5 text-left text-xs '
                  + 'transition-colors hover:bg-muted', c.id === conversationId && 'bg-accent')}>
                  <div className="line-clamp-2 font-medium">{c.title ?? 'Konuşma'}</div>
                  <div className="text-[10px] text-muted-foreground">{formatDateTime(c.lastMessageAt)} · {c.turns} mesaj</div>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
