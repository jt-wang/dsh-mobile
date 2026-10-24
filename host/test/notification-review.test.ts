import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HostState } from '../src/state.ts';
import { NotificationFeed } from '../src/notifications.ts';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import { opening } from './fixtures/rc2.ts';
import type { NotificationSource } from '../src/notifications.ts';

function setup() {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'notification-review-')));
  const state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] }).pairingToken, 'Synthetic');
  return { dir, state, device };
}
function enable(feed: NotificationFeed, device: { deviceId: string }) {
  feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] });
}

// Review B1: exercise the real adapter's bounded opening pool, not fake evidence.
test('24 notification proofs wait for opening capacity without losing completions or a concurrent snapshot', { timeout: 10000 }, async t => {
  const { dir, state, device } = setup();
  const ids = Array.from({ length: 24 }, (_, i) => `s-${i}`);
  const gate = Promise.withResolvers<void>(), full = Promise.withResolvers<void>();
  let entered = 0, active = 0, peak = 0, listener!: (id: string, event: NotificationSource) => void;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }],
    sessionHeader: async id => ({ ...opening.header, id, cwd: dir }), sessionStatus: () => ({ running: false, updatedAt: 0 }),
    events: { subscribe() { return () => {}; }, subscribeNotifications(fn) { listener = fn; return () => {}; } },
    sessionController: {
      async list() { return { items: ids.map(sessionId => ({ sessionId, cwd: dir, running: false, updatedAt: 0, agentAvailable: false })) }; },
      async *follow(req) { entered++; peak = Math.max(peak, ++active); if (active === 16) full.resolve();
        try { await gate.promise; const frame = structuredClone(opening); frame.header.id = req.address.sessionId; frame.header.cwd = dir; yield frame; }
        finally { active--; }
      },
      async page() { throw Error('unused'); }, async projections() { return opening.projections; }, async prompt() {}, async create() {}, cancel() {},
    },
  });
  const feed = new NotificationFeed(state, adapter.notifications()!);
  t.after(async () => { gate.resolve(); await feed.close(); adapter.dispose(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); enable(feed, device); await feed.idle(); const head = (await feed.page(device.deviceId)).nextCursor;
  for (const id of ids) listener(id, { seq: 7, type: 'turn/end', turn: 1, completed: true, time: Date.now() });
  await full.promise;
  // All point lookups complete while the opening pool is saturated.
  await new Promise(resolve => setTimeout(resolve, 100));
  const snapshot = adapter.snapshot('concurrent-read', new AbortController().signal); void snapshot.catch(() => {});
  await new Promise(resolve => setImmediate(resolve)); gate.resolve();
  await Promise.all([feed.idle(), snapshot]);
  const page = await feed.page(device.deviceId, head);
  assert.equal(page.items.length, 24); assert.equal(entered, 25); assert.equal(peak, 16);
  assert.equal(state.readNotificationState<any>('diagnostics')?.counts.accept_failed ?? 0, 0);
});

test('24 ancestry recoveries wait for the sixteen history slots', { timeout: 10000 }, async t => {
  const { dir, state } = setup(), full = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  let active = 0, peak = 0, entered = 0;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }], sessionStatus: () => ({ running: false, updatedAt: 0 }),
    sessionController: { async list() { return { items: [] }; },
      async *follow(req) { const f = structuredClone(opening); f.header.id = req.address.sessionId; f.header.cwd = dir; f.records = f.records.slice(6); f.hasMore = true; yield f; },
      async page(_req, signal) { entered++; peak = Math.max(peak, ++active); if (active === 16) full.resolve();
        try { await gate.promise; signal.throwIfAborted(); return { records: opening.records.slice(0, 6), hasMore: false }; } finally { active--; }
      }, async projections() {}, async prompt() {}, async create() {}, cancel() {},
    },
  });
  t.after(() => { gate.resolve(); adapter.dispose(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  const snapshots = Array.from({ length: 24 }, (_, i) => adapter.snapshot(`history-${i}`, new AbortController().signal));
  for (const snapshot of snapshots) void snapshot.catch(() => {});
  await full.promise; await new Promise(resolve => setTimeout(resolve, 100)); gate.resolve();
  assert.equal((await Promise.all(snapshots)).length, 24); assert.equal(entered, 24); assert.equal(peak, 16);
});

test('point lookup rejects disposal during its own async header read', async t => {
  const { dir, state } = setup(), entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  let disposed!: (id: string) => void;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }],
    sessionHeader: async id => { const captured = { ...opening.header, id, cwd: dir }; entered.resolve(); await gate.promise; return captured; },
    events: { subscribe() { return () => {}; }, subscribeDisposed(fn) { disposed = fn; return () => {}; }, subscribeNotifications() { return () => {}; } },
    sessionController: { async list() { return { items: [] }; }, async *follow() {}, async page() {}, async projections() {}, async prompt() {}, async create() {}, cancel() {} },
  });
  t.after(() => { gate.resolve(); adapter.dispose(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  const pending = adapter.notifications()!.lookup!('deleted', new AbortController().signal); await entered.promise;
  disposed('deleted'); gate.resolve(); assert.equal(await pending, undefined);
});

test('opening cleanup cannot publish evidence from a disposed lifecycle', async t => {
  const { dir, state } = setup(), entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  let disposed!: (id: string) => void;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }], sessionStatus: () => ({ running: false, updatedAt: 0 }),
    sessionHeader: async id => ({ ...opening.header, id, cwd: dir }),
    events: { subscribe() { return () => {}; }, subscribeDisposed(fn) { disposed = fn; return () => {}; }, subscribeNotifications() { return () => {}; } },
    sessionController: { async list() { return { items: [] }; }, async *follow(req) { try { const frame = structuredClone(opening); frame.header.id = req.address.sessionId; frame.header.cwd = dir; yield frame; } finally { entered.resolve(); await gate.promise; } }, async page() {}, async projections() {}, async prompt() {}, async create() {}, cancel() {} },
  });
  t.after(() => { gate.resolve(); adapter.dispose(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  const pending = adapter.notifications()!.evidence('disposed', new AbortController().signal); void pending.catch(() => {});
  await entered.promise; disposed('disposed'); gate.resolve(); await assert.rejects(pending);
});

test('parallel proof completions cannot overshoot the watermark limit', async t => {
  const { dir, state, device } = setup(), gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>();
  let reads = 0, listener!: (id: string, event: NotificationSource) => void;
  const feed = new NotificationFeed(state, { subscribe(fn) { listener = fn; return () => {}; }, async list() { return []; },
    async lookup(id) { return { id, workspaceId: 'alpha' }; },
    async evidence(id) { if (++reads === 2) entered.resolve(); await gate.promise; return { sessionId: id, workspaceId: 'alpha', cursor: 0, pending: false, completed: [] }; },
  });
  t.after(async () => { gate.resolve(); await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); enable(feed, device); await feed.idle();
  // Seed only the capacity boundary; observe the durable post-commit result.
  (feed as any).watermarks = Object.fromEntries(Array.from({ length: 9999 }, (_, i) => ['old-' + i, { seq: 0 }]));
  for (const id of ['new-a', 'new-b']) listener(id, { seq: 0, type: 'tool/call', time: Date.now() });
  await entered.promise; gate.resolve(); await feed.idle();
  assert.equal(Object.keys(state.readNotificationState<any>('producer')).length, 10000);
  assert.equal(state.readNotificationState<any>('diagnostics').lastReason, 'watermark_limit');
});

test('point pages bound fan-out and cancel queued reads on request abort', async t => {
  const { dir, state, device } = setup(); let hold = false, calls = 0, cancelled = 0;
  const full = Promise.withResolvers<void>();
  const feed = new NotificationFeed(state, { subscribe() { return () => {}; }, async list() { return []; },
    async lookup(id, signal) { if (hold) { if (++calls === 16) full.resolve(); await new Promise<void>((resolve, reject) => { signal.addEventListener('abort', () => { cancelled++; reject(signal.reason); }, { once: true }); }); } return { id, workspaceId: 'alpha' }; },
    async evidence(id) { return { sessionId: id, workspaceId: 'alpha', cursor: 0, pending: false, completed: [] }; },
  });
  t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); enable(feed, device); await feed.idle();
  (feed as any).watermarks = Object.fromEntries(Array.from({ length: 100 }, (_, i) => ['pending-' + i, { seq: 0, attentionId: 'attention-' + i }]));
  hold = true; const controller = new AbortController(), pending = feed.page(device.deviceId, undefined, 50, controller.signal);
  await full.promise; controller.abort(); await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(calls, 16); assert.equal(cancelled, 16);
});

// Review B2: deletion occurs after a's authority read while another read yields.
test('point page rejects disposed membership after another held lookup', async t => {
  const { dir, state, device } = setup(); let listener!: (id: string, event: NotificationSource) => void;
  let disposed!: (id: string) => void, hold = false, deleted = false;
  const entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }],
    sessionHeader: async id => { if (hold && id === 'b') { entered.resolve(); await gate.promise; } return deleted && id === 'a' ? undefined : { ...opening.header, id, cwd: dir }; },
    sessionStatus: () => ({ running: false, updatedAt: 0 }),
    events: { subscribe() { return () => {}; }, subscribeDisposed(fn) { disposed = fn; return () => {}; }, subscribeNotifications(fn) { listener = fn; return () => {}; } },
    sessionController: { async list() { return { items: [] }; }, async *follow(req) { const f = structuredClone(opening); f.header.id = req.address.sessionId; f.header.cwd = dir; yield f; }, async page() {}, async projections() {}, async prompt() {}, async create() {}, cancel() {} },
  });
  const source = adapter.notifications()!, feed = new NotificationFeed(state, source);
  t.after(async () => { gate.resolve(); await feed.close(); adapter.dispose(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); enable(feed, device); await feed.idle(); const head = (await feed.page(device.deviceId)).nextCursor;
  for (const id of ['a', 'b']) { listener(id, { seq: 7, type: 'turn/end', turn: 1, completed: true, time: Date.now() }); await feed.idle(); }
  const original = source.lookup!, gotA = Promise.withResolvers<void>();
  source.lookup = async (id, signal) => { if (hold && id === 'b') await gotA.promise; const value = await original(id, signal); if (hold && id === 'a') gotA.resolve(); return value; };
  hold = true; const pending = feed.page(device.deviceId, head); await entered.promise;
  deleted = true; disposed('a'); gate.resolve();
  await assert.rejects(pending, { code: 'unavailable' });
  hold = false; assert.equal((await feed.page(device.deviceId, head)).items.some(e => e.sessionId === 'a'), false);
});

// Review B3, both point and legacy catalog compositions.
for (const point of [true, false]) test(`disabled last-device page resets pending with a live read signal (${point ? 'point' : 'legacy'})`, async t => {
  const { dir, state, device } = setup(); let listener!: (id: string, event: NotificationSource) => void;
  const listed = { id: 'pending', workspaceId: 'alpha' };
  const feed = new NotificationFeed(state, {
    subscribe(fn) { listener = fn; return () => {}; },
    async list(signal) { signal.throwIfAborted(); return [listed]; },
    ...(point ? { async lookup(_id: string, signal: AbortSignal) { signal.throwIfAborted(); return listed; } } : {}),
    async evidence(id, signal) { signal.throwIfAborted(); return { sessionId: id, workspaceId: 'alpha', cursor: 0, pending: true, completed: [] }; },
  });
  t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); enable(feed, device); await feed.idle();
  listener('pending', { seq: 0, type: 'tool/call', time: Date.now() }); await feed.idle(); const before = await feed.page(device.deviceId);
  feed.putSettings(device.deviceId, { expectedRevision: 1, enabled: false, projects: [], chats: [] });
  const page = await feed.page(device.deviceId, before.nextCursor);
  assert.equal(page.resetRequired, true); assert.deepEqual(page.pending, []); assert.deepEqual(page.items, []);
  assert.deepEqual((await feed.page(device.deviceId)).pending, []);
});
