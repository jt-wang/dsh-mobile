import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { PROMPTS } from './deterministic.mts';
async function waitActive(deterministic, kind, run) {
  // The preceding controller checks have already used these prompt kinds;
  // started[] is historical, whereas controls identifies a currently held turn.
  if (deterministic.controls.has(kind)) return;
  let started;
  try {
    await Promise.race([
      new Promise(resolve => { started = value => { if (value === kind) resolve(); }; deterministic.events.on('started', started); }),
      run.result.then(result => { throw new Error(`Synthetic ${kind} child settled before model start: ${result.stopReason}`); }),
    ]);
  } finally { deterministic.events.off('started', started); }
}

/** Real in-process fork/spawn providers and deterministic turns, no external model. */
export async function subagentChecks(runtime, loader, projectRoot, cwd, observe = async () => {}) {
  const { ctx, deterministic } = runtime;
  const fibers = [], runs = [];
  let parent, adapter;
  const active = new AbortController();
  try {
    for (const [id, config] of [['dsh-subagent', { maxDepth: 4, maxActiveSubagents: 8 }], ['dsh-subagent-fork-in-process', {}], ['dsh-subagent-spawn-in-process', {}]]) {
      const module = await loader.load(id), fiber = ctx.plugin(module.default ?? module, config);
      fibers.push(fiber); await fiber.await(); assert.equal(fiber.state, 2);
    }
    const { runningSubagentCounts } = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'running-subagents.js')).href);
    const module = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'dsh-adapter.js')).href);
    adapter = await module.createDshAdapter({ dshVersion: runtime.version, sessionController: ctx.sessionController, workspaces: [{ id: 'subagent-canary', name: 'Synthetic child canary', path: cwd }], pollIntervalMs: 10, throttleMs: 1,
      sessionStatus: id => ({ running: ctx.agents.get(id)?.status === 'running', updatedAt: ctx.sessions.get(id)?.header.createdAt ?? 0 }),
      runningSubagentCounts: () => runningSubagentCounts(ctx.agents.list(), ctx.sessions.list()),
    });
    parent = await ctx.agents.create({ sessionId: `session-subagent-parent-${randomUUID()}`, meta: { cwd }, agentOptions: ctx.agentDefaultModel.currentSelection() });
    // No prompt on the main Agent: its status remains idle throughout child work.
    const first = await ctx.subagents.start('fork', { parent: parent.agent, signal: active.signal, prompt: [{ type: 'text', text: PROMPTS.cancel }] }); runs.push(first);
    await waitActive(deterministic, 'cancel', first);
    assert.equal(first.localAgent.session.header.origin, 'subagent');
    assert.equal(first.localAgent.session.header.parentSession, parent.agent.id);
    const watch = adapter.watch(parent.agent.id, active.signal)[Symbol.asyncIterator]();
    try {
      const initial = (await watch.next()).value;
      assert.equal(initial.session.running, false); assert.equal(initial.activity, 'idle'); assert.equal(initial.runningSubagents, 1);
      await assert.rejects(adapter.snapshot(first.id, new AbortController().signal), { code: 'not_found' }, 'real child address remains hidden');
      await observe(parent.agent.id, 1, first.id);
      const next = watch.next(); deterministic.release('cancel'); await first.result;
      const settled = (await next).value;
      assert.equal(settled.runningSubagents, 0); assert.equal(settled.session.running, false);
      await observe(parent.agent.id, 0);
    } finally { active.abort(); await watch.return(); }
    const signal = new AbortController().signal;
    const child = await ctx.subagents.start('spawn', { parent: parent.agent, signal, prompt: [{ type: 'text', text: PROMPTS.live }] }); runs.push(child);
    await waitActive(deterministic, 'live', child);
    const nested = await ctx.subagents.start('fork', { parent: child.localAgent, signal, prompt: [{ type: 'text', text: PROMPTS.reconnect }] }); runs.push(nested);
    await waitActive(deterministic, 'reconnect', nested);
    const summary = (await adapter.listSessions(signal)).find(item => item.id === parent.agent.id);
    assert.equal(summary.runningSubagents, 2); assert.equal(summary.running, false);
    await observe(parent.agent.id, 2, nested.id);
    assert.equal((await adapter.listSessions(signal)).some(item => item.id === child.id || item.id === nested.id), false, 'delegated children stay hidden');
    deterministic.release('reconnect'); await nested.result;
    assert.equal((await adapter.snapshot(parent.agent.id, signal)).runningSubagents, 1);
    await observe(parent.agent.id, 1);
    deterministic.release('live'); await child.result;
    assert.equal((await adapter.snapshot(parent.agent.id, signal)).runningSubagents, 0);
    await observe(parent.agent.id, 0);
    await ctx.sessionPersistence.flush();
    return { checks: ['real-fork-origin/parent-link', 'idle-main-with-child-one', 'settled-child-zero-via-watch', 'nested-fork-count-two', 'subagents-hidden-in-list', 'main-status/stop-semantics-unchanged'], deterministicCalls: 3 };
  } finally {
    active.abort(); adapter?.dispose();
    for (const run of runs.toReversed()) await run.dispose();
    await parent?.dispose();
    for (const fiber of fibers.toReversed()) await fiber.dispose();
  }
}
