import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HostState } from '../src/state.ts';
import { NotificationFeed } from '../src/notifications.ts';
import type { NotificationSource, NotificationSources } from '../src/notifications.ts';

// Observe the durable journal via onChange: the same post-commit seam used by SSE.
async function load(t: any, slowProof = false) {
  const dir = mkdtempSync(join(tmpdir(), 'notification-load-')), state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  const sessions = Array.from({ length: 24 }, (_, i) => ({ id: 'chat-' + i, workspaceId: 'alpha', running: false }));
  let listener!: (id: string, event: NotificationSource) => void, expensive = false, lists = 0;
  const gate = Promise.withResolvers<void>();
  const source: NotificationSources & { lookup(id: string, signal: AbortSignal): Promise<typeof sessions[number] | undefined> } = {
    subscribe(fn) { listener = fn; return () => {}; },
    async list(signal) { lists++; if (expensive) await new Promise<void>((resolve, reject) => { const timer = setTimeout(resolve, 25); signal.addEventListener('abort', () => { clearTimeout(timer); reject(signal.reason); }, { once: true }); }); return sessions; },
    async lookup(id) { return sessions.find(s => s.id === id); },
    async evidence(id) { if (slowProof && id === 'chat-0') await gate.promise; return { sessionId: id, workspaceId: 'alpha', cursor: 1000, pending: false, completed: [{ turn: 1, sourceSeq: 0 }] }; },
  };
  const feed = new NotificationFeed(state, source);
  t.after(async () => { gate.resolve(); await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] }); await feed.idle();
  const committed = Promise.withResolvers<number>(); let begin = 0;
  feed.onChange(() => { if (state.readNotificationState<any>('device:' + device.deviceId)?.items.some((e: any) => e.sessionId === 'chat-23')) committed.resolve(performance.now() - begin); });
  expensive = true; lists = 0; begin = performance.now();
  if (slowProof) { listener('chat-0', { seq: 0, type: 'tool/call', time: Date.now() }); await new Promise(r => setImmediate(r)); }
  for (let i = 0; i < 320; i++) listener('child-' + (i % 40), { seq: Math.floor(i / 40), type: 'tool/call', time: Date.now() });
  for (let i = 1; i < 23; i++) listener('chat-' + i, { seq: 0, type: 'tool/call', time: Date.now() });
  listener('chat-23', { seq: 0, type: 'turn/end', turn: 1, completed: true, time: Date.now() });
  const deadline = Promise.withResolvers<number>(); const timer = setTimeout(() => deadline.resolve(Infinity), 1900);
  const ms = await Promise.race([committed.promise, deadline.promise]); clearTimeout(timer); gate.resolve();
  // Wait for cleanup before asserting, including on the base implementation.
  await feed.idle();
  t.diagnostic(JSON.stringify({ workload: '24 ordinary + 40 child sessions, 343 relevant envelopes', slowProof, commitMs: Number.isFinite(ms) ? ms : '>1900', lists, drainMs: performance.now() - begin }));
  assert.ok(ms < 1900, 'target journal commit must be under 2 seconds despite foreign activity');
  assert.equal(lists, 0, 'point-capable producer must not scan the catalog');
}

test('load: slow catalog plus hundreds of child events cannot delay a terminal commit', { timeout: 20000 }, async t => load(t));
test('load: held unrelated proof cannot delay a newly arriving session', { timeout: 20000 }, async t => load(t, true));
