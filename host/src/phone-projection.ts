import { answerSyntax, identifier, record } from './phone.ts';
import type { ResolvedQuestion, SessionModel, SessionMutation, PendingQuestion, QuestionAnswer } from './phone.ts';

/** Strict wire whitelist; a new untouched session has no durable model yet. */
export function sessionModel(value: unknown): SessionModel | null {
  const m = record(value);
  if (!m || !identifier(m.provider) || !identifier(m.id ?? m.model) || (m.reasoningEffort !== undefined && !identifier(m.reasoningEffort))) return null;
  return { provider: m.provider, id: (m.id ?? m.model) as string, ...(m.reasoningEffort !== undefined ? { reasoningEffort: m.reasoningEffort as string } : {}) };
}
export function settledQuestions(value: unknown): ResolvedQuestion[] {
  if (!Array.isArray(value)) return [];
  return value.slice(-20).flatMap(raw => {
    const q = record(raw); if (!q || !identifier(q.callId)) return [];
    try {
      const { answers } = answerSyntax({ answers: q.answers });
      return [{ callId: q.callId, outcome: answers.every(a => !a.selected.length && !a.custom) ? 'skipped' as const : 'answered' as const, answers }];
    } catch { return []; }
  });
}
export function mutationAnswer(mutation: Extract<SessionMutation, { kind: 'answer' }>, pending: PendingQuestion): QuestionAnswer {
  return mutation.skip ? { answers: pending.questions.map(q => ({ id: q.id, selected: [] })) } : mutation.answer;
}
/** Kept separate from the surface/ancestry reducer: optional phone metadata only. */
export class PhoneProjection {
  private readonly images = new Map<string, { attachmentId: string; mediaType: 'image/png' | 'image/jpeg' | 'image/webp' | 'image/gif'; bytes: number; width: number; height: number; name?: string }>();
  imageRef(id: string) { return this.images.get(id); }
  private readonly deliveries = new Map<string, 'queue' | 'steer'>();
  delivery(id: string) { return this.deliveries.get(id); }
  model: SessionModel | null = null;
  resolvedQuestions: ResolvedQuestion[] = [];
  open(frame: Record<string, unknown>): void {
    const values = record(record(frame.projections)?.values);
    this.model = sessionModel(record(values?.modelSelection)?.next);
    this.resolvedQuestions = settledQuestions(record(values?.userQuestions)?.settled);
    if (Array.isArray(frame.records)) for (const raw of frame.records) this.event(record(raw)?.event);
    // Authoritative projection wins over bounded historical events.
    this.model = sessionModel(record(values?.modelSelection)?.next);
  }
  event(value: unknown): void {
    const e = record(value), data = record(e?.data);
    const message = e?.type === 'user/message' ? data : e?.type === 'assistant/message' ? record(data?.message) : undefined;
    if (Array.isArray(message?.content)) for (const part of message.content) {
      const p = record(part), a = record(p?.attachment);
      if (p?.type === 'image' && a && identifier(a.attachmentId) && ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(a.mediaType as string) && [a.bytes, a.width, a.height].every(n => Number.isSafeInteger(n) && (n as number) > 0)) {
        this.images.set(a.attachmentId, { attachmentId: a.attachmentId, mediaType: a.mediaType as 'image/png', bytes: a.bytes as number, width: a.width as number, height: a.height as number, ...(typeof a.name === 'string' ? { name: a.name } : {}) });
        if (this.images.size > 1000) this.images.delete(this.images.keys().next().value!);
      }
    }
    if (e?.type === 'agent/inbox/spliced' && (data?.target === 'next-turn' || data?.target === 'next-step') && Array.isArray(data.inserted)) {
      for (const raw of data.inserted) { const m = record(raw); if (!m || !identifier(m.id) || record(m.source)?.kind !== 'user') continue;
        this.deliveries.delete(m.id); this.deliveries.set(m.id, data.target === 'next-step' ? 'steer' : 'queue');
        if (this.deliveries.size > 1000) this.deliveries.delete(this.deliveries.keys().next().value!);
      }
    }
    if (e?.type === 'model/selection') this.model = sessionModel(data);
    // A pending selection may intentionally differ from the currently in-flight
    // request header. The next authoritative projection resolves consumption.
  }
}
