import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { TestContext } from 'node:test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HostState } from '../src/state.ts';
import { NotificationFeed } from '../src/notifications.ts';
import type { NotificationSource, NotificationSources } from '../src/notifications.ts';

// Observe the durable journal via onChange: the same post-commit seam used by SSE.
// Gates, not a shared-machine wall-clock deadline, establish independence from
// the catalog and another session's proof. setImmediate is an event-loop barrier:
// all runnable promise continuations settle, but neither gate is released.
async function load(t: TestContext, slowProof = false) {
  const dir = mkdtempSync(join(tmpdir(), 'notification-load-')), state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  const sessions = Array.from({ length: 24 }, (_, i) => ({ id: 'chat-' + i, workspaceId: 'alpha', running: false }));
  let listener!: (id: string, event: NotificationSource) => void, expensive = false, lists = 0, heldProofs = 0;
  const catalogGate = Promise.withResolvers<void>(), proofGate = Promise.withResolvers<void>();
  const evidenceIds: string[] = [];
  const source: NotificationSources & { lookup(id: string, signal: AbortSignal): Promise<typeof sessions[number] | undefined> } = {
    subscribe(fn) { listener = fn; return () => {}; },
    async list() { lists++; if (expensive) await catalogGate.promise; return sessions; },
    async lookup(id) { return sessions.find(s => s.id === id); },
    async evidence(id) {
      evidenceIds.push(id);
      if (slowProof && id === 'chat-0') { heldProofs++; await proofGate.promise; }
      return { sessionId: id, workspaceId: 'alpha', cursor: 1000, pending: false, completed: [{ turn: 1, sourceSeq: 0 }] };
    },
  };
  const feed = new NotificationFeed(state, source);
  t.after(async () => { catalogGate.resolve(); proofGate.resolve(); await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] }); await feed.idle();
  let commitMs: number | undefined;
  const begin = performance.now();
  feed.onChange(() => { if (state.readNotificationState<any>('device:' + device.deviceId)?.items.some((e: any) => e.sessionId === 'chat-23')) commitMs ??= performance.now() - begin; });
  expensive = true; lists = 0;
  if (slowProof) { listener('chat-0', { seq: 0, type: 'tool/call', time: Date.now() }); await new Promise(resolve => setImmediate(resolve)); }
  for (let i = 0; i < 320; i++) listener('child-' + (i % 40), { seq: Math.floor(i / 40), type: 'tool/call', time: Date.now() });
  for (let i = 1; i < 23; i++) listener('chat-' + i, { seq: 0, type: 'tool/call', time: Date.now() });
  listener('chat-23', { seq: 0, type: 'turn/end', turn: 1, completed: true, time: Date.now() });
  await new Promise(resolve => setImmediate(resolve));
  // Capture before releasing either dependency, including on the old serialized
  // producer. Cleanup must not turn a late commit into a passing observation.
  const committedWhileBlocked = commitMs !== undefined;
  const proofIdsWhileBlocked = evidenceIds.slice();
  const listsWhileBlocked = lists;
  catalogGate.resolve(); proofGate.resolve(); await feed.idle();
  t.diagnostic(JSON.stringify({ workload: '24 ordinary + 40 child sessions, 343 relevant envelopes', slowProof, committedWhileBlocked, heldProofs, listsWhileBlocked, commitMs, drainMs: performance.now() - begin }));
  assert.equal(listsWhileBlocked, 0, 'point-capable producer must not scan the catalog');
  assert.equal(committedWhileBlocked, true, 'target must durably commit while catalog and unrelated proof are still held');
  assert.equal(heldProofs, slowProof ? 1 : 0, 'the unrelated proof must actually have entered its gate');
  assert.equal(proofIdsWhileBlocked.length, slowProof ? 24 : 23, 'all ordinary lanes progress before gates release');
  assert.equal(proofIdsWhileBlocked.some(id => id.startsWith('child-')), false, 'excluded child envelopes never prepare history');
  assert.equal(lists, 0, 'cleanup must not hide a deferred catalog scan');
}

test('load: a blocked catalog plus hundreds of child events cannot block a terminal commit', { timeout: 20000 }, async t => load(t));
test('load: held unrelated proof cannot block a newly arriving session', { timeout: 20000 }, async t => load(t, true));
