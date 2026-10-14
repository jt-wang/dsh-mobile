import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runningSubagentCounts } from '../src/running-subagents.ts';
import type { LiveAgent, LiveSession } from '../src/running-subagents.ts';

const session = (id: string, parentSession?: string, origin?: 'subagent', isSeeded = false): LiveSession => ({ id, header: { id, createdAt: 1, ...(parentSession ? { parentSession } : {}), ...(origin ? { origin } : {}), isSeeded } });
const agent = (session: LiveSession, status = 'running'): LiveAgent => ({ id: session.id, session, status });

test('an idle main agent retains one running child count, which clears at settlement', () => {
  const parent = session('parent'), child = session('child', parent.id, 'subagent');
  const live = [agent(parent, 'idle'), agent(child)];
  assert.equal(runningSubagentCounts(live, [parent, child]).get(parent.id), 1);
  assert.equal(live[0]!.status, 'idle', 'count does not reinterpret main status');
  live[1]!.status = 'idle';
  assert.equal(runningSubagentCounts(live, [parent, child]).get(parent.id) ?? 0, 0);
});

test('nested and subagent-fork descendants count once through idle intermediate sessions; ordinary forks stay independent', () => {
  const parent = session('parent'), child = session('child', parent.id, 'subagent');
  const nested = session('nested', child.id, 'subagent'), delegatedFork = session('delegated-fork', parent.id, 'subagent', true);
  const ordinaryFork = session('ordinary-fork', parent.id, undefined, true), forkWorker = session('fork-worker', ordinaryFork.id, 'subagent');
  const sessions = [parent, child, nested, delegatedFork, ordinaryFork, forkWorker];
  const counts = runningSubagentCounts([agent(nested), agent(delegatedFork), agent(ordinaryFork), agent(forkWorker), agent(nested)], sessions);
  assert.equal(counts.get(parent.id), 2, 'nested + delegated fork, not independent fork or its worker');
  assert.equal(counts.get(child.id), 1); assert.equal(counts.get(ordinaryFork.id), 1);
  assert.equal(counts.get(nested.id) ?? 0, 0, 'a child is not its own descendant');
});

test('unavailable ancestors and malformed lineage cycles remain finite without cold header reads', () => {
  const a = session('a', 'b', 'subagent'), b = session('b', 'a', 'subagent'), orphan = session('orphan', 'cold-parent', 'subagent');
  const counts = runningSubagentCounts([agent(a), agent(orphan)], [a, b, orphan]);
  assert.equal(counts.get('b'), 1); assert.equal(counts.get('a') ?? 0, 0);
  assert.equal(counts.get('cold-parent'), 1, 'direct durable identity needs no observation or promotion');
});
