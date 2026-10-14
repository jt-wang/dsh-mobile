// Read-only copied-journal performance probe; receipts contain timings/counts, never transcripts.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cp, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { request } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';
import { zstdCompressSync, zstdDecompressSync } from 'node:zlib';
import { composeRuntime, officialLoader } from './composition.mjs';
import { installIsolation, sanitizeEnvironment } from './isolation.mjs';
import { assertOwnedPath } from './safety.mjs';

const installation = await realpath(path.join(os.homedir(), 'Documents', 'DeepSeekHarness'));
const args = process.argv.slice(2);
assert.equal(args.length, 3, 'Usage: perf.mjs <perf-cache-root> <host-build-directory> <receipt-label>');
const cacheRoot = path.resolve(args[0]), buildRoot = await realpath(args[1]), label = args[2];
assert.equal(path.dirname(cacheRoot), path.join(installation, 'cache'));
assert.match(path.basename(cacheRoot), /^dsh-mobile-host-perf-\d{8}$/);
assert.match(label, /^[a-z0-9-]+$/);
const copyRoot = await realpath(path.join(cacheRoot, 'copies', 'home', 'sessions'));
const runtimeRoot = await realpath(path.join(installation, 'runtime'));
const runRoot = await mkdtemp(path.join(cacheRoot, `run-${label}-`));
const directories = Object.fromEntries(['home', 'workspace', 'temp'].map(key => [key, assertOwnedPath(runRoot, path.join(runRoot, key))]));
directories.sessions = path.join(directories.home, 'sessions');
directories.storages = path.join(directories.home, 'storages');
for (const dir of [directories.home, directories.workspace, directories.temp, directories.storages]) await mkdir(dir, { recursive: true });
await cp(copyRoot, directories.sessions, { recursive: true, force: false, errorOnExist: true });
const samples = [];
for (const project of await readdir(directories.sessions)) for (const id of await readdir(path.join(directories.sessions, project))) {
  const journal = path.join(directories.sessions, project, id, 'session.v4.jsonl.zstd');
  const bytes = await readFile(journal), header = JSON.parse(zstdDecompressSync(bytes).toString('utf8').trimEnd());
  assert.equal(header.origin, undefined, 'Only ordinary copied chats are admitted');
  samples.push({ id: header.id, cwd: header.cwd, bytes: bytes.length });
}
samples.sort((a, b) => a.bytes - b.bytes);
assert.equal(samples.length, 3);
// Match the observed live corpus dimensions with inert synthetic headers, not copies of other chats.
const corpusSize = 1494, projectCount = 37;
function projectKey(cwd) {
  let readable = '', separators = false;
  for (let i = 0; i < cwd.length; i++) {
    const ch = cwd[i];
    if ('/\\:'.includes(ch)) { if (!separators) readable += '-'; separators = true; }
    else { readable += /^[A-Za-z0-9._-]$/.test(ch) ? ch : `~${cwd.charCodeAt(i).toString(16).toUpperCase().padStart(4, '0')}`; separators = false; }
  }
  return `--${(readable.replace(/^-+/, '') || 'root').slice(0, 251)}--`;
}
for (let i = 0; i < corpusSize - samples.length; i++) {
  const cwd = path.join(directories.workspace, `scope-${i % (projectCount - 1)}`), id = `session-perf-${i}`;
  const dir = path.join(directories.sessions, projectKey(cwd), id);
  await mkdir(cwd, { recursive: true }); await mkdir(dir, { recursive: true });
  const header = { version: 4, id, createdAt: 1700000000000, cwd, isSeeded: false, delegationDepth: 0 };
  await writeFile(path.join(dir, 'session.v4.jsonl.zstd'), zstdCompressSync(Buffer.from(JSON.stringify(header) + '\n')), { flag: 'wx' });
}
const receiptPath = path.join(runRoot, 'receipt.json');
sanitizeEnvironment(directories.home, directories.temp); process.chdir(directories.workspace);
const isolation = installIsolation(runRoot, { registryCheck: true, port: 19447 });
const loader = officialLoader(runtimeRoot);
const receipt = { kind: 'isolated-copied-journal-perf', node: process.version, label, success: false, corpusSize, projectCount, samples: samples.map(({ bytes }, index) => ({ sample: ['small', 'medium', 'large'][index], bytes })), measurements: [], isolation, installed: loader.evidence, cleanup: {} };
let runtime, host, adapter, current;
const originalStringify = JSON.stringify;
const restorers = [];
function timing(name, elapsed) {
  if (!current) return;
  const item = current.breakdown[name] ??= { calls: 0, ms: 0 };
  item.calls++; item.ms += elapsed;
}
function instrument(object, method, name) {
  if (typeof object?.[method] !== 'function') return;
  const original = object[method];
  object[method] = function (...values) {
    const started = performance.now();
    try {
      const value = Reflect.apply(original, this, values);
      if (value?.then) return value.then(result => { timing(name, performance.now() - started); return result; }, error => { timing(name, performance.now() - started); throw error; });
      timing(name, performance.now() - started); return value;
    } catch (error) { timing(name, performance.now() - started); throw error; }
  };
  restorers.push(() => { object[method] = original; });
}
const originalService = service => service?.[Symbol.for('cordis.original')] ?? service;
function httpRead(baseUrl, target, token, sse = false) {
  const began = performance.now();
  return new Promise((resolve, reject) => {
    const req = request(new URL(target, baseUrl), { headers: { Authorization: `Bearer ${token}` }, timeout: 30000, agent: false }, res => {
      const headersMs = performance.now() - began;
      let body = '', bytes = 0;
      res.setEncoding('utf8');
      function finish() {
        const json = sse ? body.split('\n').find(line => line.startsWith('data: '))?.slice(6) : body;
        try {
          const value = JSON.parse(json);
          assert.equal(res.statusCode, 200, 'Expected authenticated successful read');
          const stableSurface = value.messages ? { cursor: value.cursor, messages: value.messages, activity: value.activity, hasMore: value.hasMore } : { items: value.items?.map(({ workspaceId: _workspaceId, runningSubagents: _runningSubagents, ...item }) => item) };
          resolve({ headersMs, totalMs: performance.now() - began, responseBytes: bytes, surfaceDigest: createHash('sha256').update(originalStringify(stableSurface)).digest('hex'), ...(value.messages ? { cursor: value.cursor, messages: value.messages.length, activity: value.activity, hasMore: value.hasMore } : { items: value.items?.length }) });
        } catch { reject(new Error(`Performance HTTP failure: status ${res.statusCode}`)); }
      }
      res.on('data', chunk => { body += chunk; bytes += Buffer.byteLength(chunk); if (sse && body.includes('\n\n')) { finish(); req.destroy(); } });
      if (!sse) res.on('end', finish);
    });
    req.on('timeout', () => req.destroy(new Error('Performance request timeout'))); req.on('error', reject); req.end();
  });
}
try {
  receipt.version = await loader.version();
  runtime = await composeRuntime(loader, directories, { compression: 'zstd' });
  const { ctx } = runtime;
  // Registry membership can refer to a copied journal's unchanged header cwd; all writes remain guarded in the owned home.
  await ctx.workspaceRegistry.create(samples[0].cwd, 'Copied journal scope');
  const persistence = originalService(ctx.sessionPersistence), query = originalService(ctx.sessionQuery), projections = originalService(ctx.sessionProjections);
  for (let index = 0; index < samples.length; index++) {
    const stored = await persistence.requireStoredLog(samples[index].id);
    receipt.samples[index].events = stored.events.length;
    receipt.samples[index].logicalJsonBytes = Buffer.byteLength(originalStringify(stored.events));
  }
  persistence.coldLogMemo.clear();
  for (const [method, name] of [['list', 'persistence.list'], ['readGenerationHeader', 'journal.header-read'], ['stat', 'journal.stat'], ['open', 'journal.open'], ['readStoredLog', 'journal.read'], ['decodeStoredLog', 'journal.decode-validate'], ['readZstdPrefix', 'journal.decompress-parse']]) instrument(persistence, method, name);
  instrument(query._observations, 'loadSource', 'cold.load'); instrument(query._observations, 'read', 'cold.observe');
  instrument(originalService(ctx.sessions), 'prepare', 'cold.prepare'); instrument(projections, 'hydrate', 'runtime.projection');
  const adapterModule = await import(pathToFileURL(path.join(buildRoot, 'dsh-adapter.js')).href);
  const sourceModule = await import(pathToFileURL(path.join(buildRoot, 'workspace-source.js')).href);
  const serverModule = await import(pathToFileURL(path.join(buildRoot, 'server.js')).href);
  const source = await sourceModule.createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => ctx.get('workspaceRegistry'));
  instrument(source, 'list', 'scope.registry-list');
  let counts;
  try { counts = (await import(pathToFileURL(path.join(buildRoot, 'running-subagents.js')).href)).runningSubagentCounts; } catch (error) { if (error.code !== 'ERR_MODULE_NOT_FOUND') throw error; }
  adapter = await adapterModule.createDshAdapter({ dshVersion: runtime.version, sessionController: ctx.sessionController, workspaceSource: source, pollIntervalMs: 60000, ...(counts ? { runningSubagentCounts: () => counts(ctx.agents.list(), ctx.sessions.list()) } : {}),
    // Point summaries use only the inspected live services and exact current cold opening, never a catalog cache.
    sessionStatus: id => { const session = ctx.sessions.get(id); return { running: ctx.agents.get(id)?.status === 'running', updatedAt: session?.header.createdAt ?? 0 }; } });
  for (const [method, name] of [['list', 'controller.list'], ['projections', 'controller.projections']]) instrument(originalService(ctx.sessionController), method, name);
  for (const [method, name] of [['open', 'adapter.open'], ['recoverShared', 'adapter.history-recovery'], ['snapshot', 'adapter.snapshot'], ['listSessions', 'adapter.list']]) instrument(adapter, method, name);
  const follow = originalService(ctx.sessionController).follow.bind(ctx.sessionController);
  originalService(ctx.sessionController).follow = function (...values) {
    const iterator = follow(...values)[Symbol.asyncIterator]();
    const next = iterator.next.bind(iterator); iterator.next = async (...args) => { const start = performance.now(); try { return await next(...args); } finally { timing('controller.opening', performance.now() - start); } };
    return { [Symbol.asyncIterator]: () => iterator };
  };
  JSON.stringify = function (...values) { const started = performance.now(); const json = Reflect.apply(originalStringify, JSON, values); timing('json.serialize', performance.now() - started); return json; };
  host = await serverModule.startHostServer({ config: { hostName: 'Isolated copied-journal performance', bind: '127.0.0.1', port: 19447, statePath: path.join(directories.home, 'perf.sqlite'), allowInsecureLoopback: true, workspaceSource: 'dsh-registry' }, workspaceSource: source, adapter });
  for (const method of ['authenticate', 'getDevice']) instrument(host.state, method, 'grant.auth-check');
  const device = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['*'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic performance device');
  const { baseUrl } = await host.start();
  async function measure(name, target, sse = false) {
    current = { name, breakdown: {} };
    const value = await httpRead(baseUrl, target, device.deviceToken, sse);
    receipt.measurements.push({ ...current, ...value }); current = undefined;
    await new Promise(resolve => setImmediate(resolve));
  }
  await measure('catalog', '/v1/sessions');
  for (let index = 0; index < samples.length; index++) {
    const name = ['small', 'medium', 'large'][index], target = `/v1/sessions/${encodeURIComponent(samples[index].id)}`;
    await measure(`${name}-cold-get`, target);
    query._observations.cache.clear(); persistence.coldLogMemo.clear();
    await measure(`${name}-cold-sse`, target + '/events', true);
    await measure(`${name}-warm-sse`, target + '/events', true);
    await measure(`${name}-warm-get`, target);
    assert.equal(ctx.sessions.get(samples[index].id), undefined, 'No copied Session activation');
    assert.equal(ctx.agents.get(samples[index].id), undefined, 'No copied Agent activation');
  }
  query._observations.cache.clear(); persistence.coldLogMemo.clear();
  current = { name: 'medium-concurrent-cold-get-sse', breakdown: {} };
  const concurrentTarget = `/v1/sessions/${encodeURIComponent(samples[1].id)}`;
  const concurrent = await Promise.all([httpRead(baseUrl, concurrentTarget, device.deviceToken), httpRead(baseUrl, concurrentTarget + '/events', device.deviceToken, true)]);
  receipt.measurements.push({ ...current, headersMs: Math.max(...concurrent.map(item => item.headersMs)), totalMs: Math.max(...concurrent.map(item => item.totalMs)), surfaceDigest: concurrent[0].surfaceDigest, matchingSurface: concurrent[0].surfaceDigest === concurrent[1].surfaceDigest }); current = undefined;
  for (const sample of samples) { assert.equal(ctx.sessions.get(sample.id), undefined); assert.equal(ctx.agents.get(sample.id), undefined); }
  assert.equal(runtime.deterministic.calls, 0);
  receipt.modelCalls = 0; receipt.success = true;
} catch (error) { receipt.failure = { name: error.name, message: 'Perf failed; private upstream error withheld', stackLocation: error.stack?.split('\n').slice(1, 3).join('\n') }; process.exitCode = 1; }
finally {
  JSON.stringify = originalStringify; for (const restore of restorers.toReversed()) restore();
  try {
    await host?.close(); adapter?.dispose(); await runtime?.dispose();
    assert.equal(isolation.activeListeners, 0);
    assert.equal(isolation.networkAttempts + isolation.subprocessAttempts + isolation.outsideWrites, 0);
    process.chdir(runRoot);
    for (const target of [directories.home, directories.workspace, directories.temp]) { assertOwnedPath(runRoot, target); await rm(target, { recursive: true, force: true }); }
    receipt.cleanup = { effectsDisposed: true, ownedRuntimeRemoved: true };
  } catch (error) { receipt.cleanup = { effectsDisposed: false, error: error.name }; receipt.success = false; process.exitCode = 1; }
  await writeFile(receiptPath, originalStringify(receipt, null, 2), { flag: 'wx' });
  console.log(originalStringify({ success: receipt.success, measurements: receipt.measurements.map(({ name, headersMs, totalMs }) => ({ name, headersMs, totalMs })), isolation, cleanup: receipt.cleanup, receiptPath, ...(receipt.failure ? { failure: receipt.failure } : {}) }, null, 2));
}
