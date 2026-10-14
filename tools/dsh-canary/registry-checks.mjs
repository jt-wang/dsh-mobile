import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile, appendFile, readdir } from 'node:fs/promises';
import { request } from 'node:http';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { assertOwnedPath } from './safety.mjs';

/** Real installed Loader/Include patch, services and controller; no live profile. */
export async function registryChecks({ runtime, loader, directories, runRoot, projectRoot, options }) {
  const { ctx, deterministic } = runtime;
  const { Context } = await loader.load('cordis');
  const { default: Loader } = await loader.load('cordis-plugin-loader');
  await loader.load('cordis-plugin-include');
  const boot = await loader.load('dsh-app-boot');
  const officialEntries = await Promise.all(['dsh-api-workspace-controller', 'dsh-agent-preset-registry', 'dsh-agent-preset'].map(async id => {
    await loader.load(id); // Exact version and entry hash evidence before mounting.
    return [id, pathToFileURL(path.join(options.runtimeRoot, 'node_modules', '@deepseek-ai', id, 'lib', 'index.js')).href];
  }));
  const entries = Object.fromEntries(officialEntries);
  const profile = assertOwnedPath(runRoot, path.join(directories.home, 'profiles', 'registry-canary'));
  await mkdir(profile, { recursive: true });
  const rootFile = path.join(profile, 'root.json'), patchFile = path.join(profile, 'cordis.patch.yml');
  const configPath = path.join(profile, 'host.json');
  const pluginUrl = pathToFileURL(path.join(projectRoot, 'host', 'dist', 'plugin.js')).href;
  const config = { workspaceSource: 'dsh-registry', hostName: 'Synthetic registry profile canary', bind: '127.0.0.1', port: options.port, allowInsecureLoopback: true, statePath: path.join(directories.home, 'registry-mobile.sqlite') };
  await writeFile(rootFile, '[]', { flag: 'wx' });
  await writeFile(configPath, JSON.stringify(config), { flag: 'wx', mode: 0o600 });
  // The companion insert is appended last exactly like the owner's reported
  // profile patch. Every path and state belongs to this synthetic run.
  const patches = [
    { insert: [
      { id: 'workspace-controller', name: entries['dsh-api-workspace-controller'] },
      { id: 'agent-preset-registry', name: entries['dsh-agent-preset-registry'], config: { default: 'canary-empty' } },
      { id: 'canary-empty-preset', name: entries['dsh-agent-preset'], config: { id: 'canary-empty', name: 'Synthetic empty preset', plugins: [] } },
    ] },
    { insert: [{ id: 'dsh-mobile-companion', name: pluginUrl, config: { dshVersion: runtime.version, configPath } }] },
  ];
  // JSON is also valid YAML; use the installed profile patch reader, not an
  // invented patch algorithm or a direct invocation of companion.apply().
  await writeFile(patchFile, JSON.stringify(patches, null, 2), { flag: 'wx', mode: 0o600 });
  const profilePatches = boot.loadOptionalPatches('dsh-mobile-canary', patchFile);
  assert.equal(profilePatches.at(-1).insert[0].name, pluginUrl);
  const loaderFiber = ctx.plugin(Loader, { baseUrl: pathToFileURL(profile + path.sep).href });
  await loaderFiber;
  const tree = ctx.get('loader');
  let admin;
  try {
    await tree.create({ id: 'profile-root', name: pathToFileURL(path.join(options.runtimeRoot, 'node_modules', '@deepseek-ai', 'cordis-plugin-include', 'lib', 'index.js')).href, config: { path: pathToFileURL(rootFile).href, patches: profilePatches } });
    await tree.await();
    const companion = tree.resolve('profile-root:dsh-mobile-companion');
    await companion.fiber.await();
    assert.equal(companion.fiber.state, 2, 'Companion profile entry must activate');
    assert.equal(companion.options.isolate, undefined);
    assert.equal(companion.ctx[Context.isolate].workspaceRegistry, ctx[Context.isolate].workspaceRegistry);
    const first = companion.fiber.ctx.get('workspaceRegistry');
    const second = companion.fiber.ctx.get('workspaceRegistry', true);
    assert.ok(first && second, 'Registry is available without companion inject');
    assert.notEqual(first, second, 'Real Service reads return distinct Cordis tracing proxies');
    assert.equal(first[Symbol.for('cordis.original')], second[Symbol.for('cordis.original')]);
    const controller = ctx.workspaceController;
    const abort = new AbortController();
    const baseline = async () => {
      const observer = controller.follow(abort.signal)[Symbol.asyncIterator]();
      try { const frame = (await observer.next()).value; assert.equal(frame.type, 'baseline'); return frame.value; }
      finally { await observer.return(); }
    };
    const workspaces = [runtime.workspace];
    for (const name of ['beta', 'gamma']) {
      const directory = assertOwnedPath(runRoot, path.join(directories.workspace, name));
      await mkdir(directory);
      const created = await controller.create({ path: directory });
      assert.equal(created.created, true);
      await controller.rename({ workspaceId: created.workspace.workspaceId, title: `Synthetic ${name}` });
      workspaces.push(ctx.workspaceRegistry.get(created.workspace.workspaceId));
    }
    const sessions = [];
    for (const workspace of workspaces) {
      const created = await ctx.sessionController.create({ workspaceId: workspace.id, sessionId: `session-registry-canary-${randomUUID()}` });
      sessions.push({ sessionId: created.sessionId, workspaceId: workspace.id });
    }
    assert.equal((await baseline()).items.length, 3);
    const { HostState } = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'state.js')).href);
    admin = new HostState(config.statePath);
    const offer = admin.createPairing({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
    const call = (suffix, token, body) => new Promise((resolve, reject) => {
      const req = request(new URL(`http://127.0.0.1:${options.port}/v1${suffix}`), { method: body === undefined ? 'GET' : 'POST', agent: false, headers: { Connection: 'close', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) } }, res => {
        const chunks = [];
        res.on('data', chunk => chunks.push(chunk));
        res.once('end', () => { try { resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }); } catch (error) { reject(error); } });
      });
      req.once('error', reject);
      req.setTimeout(5000, () => req.destroy(new Error('Owned canary HTTP request timed out')));
      req.end(body === undefined ? undefined : JSON.stringify(body));
    });
    const paired = await call('/pairings', undefined, { pairingToken: offer.pairingToken, deviceName: 'Synthetic canary phone' });
    assert.equal(paired.status, 201);
    const token = paired.body.deviceToken;
    const projects = await call('/workspaces', token), listed = await call('/sessions', token);
    console.error(`REGISTRY_HTTP ${JSON.stringify({ workspaces: { status: projects.status, ...(projects.body.error ? { code: projects.body.error.code } : {}) }, sessions: { status: listed.status, ...(listed.body.error ? { code: listed.body.error.code } : {}) }, registryPresent: true, tracingProxyIdentityStable: false, sameRealm: true })}`);
    assert.equal(projects.status, 200, 'Real profile-insert registry GET /workspaces');
    assert.equal(listed.status, 200, 'Real profile-insert registry GET /sessions');
    const expectedProjects = (await baseline()).items.map(row => ({ id: row.workspaceId, name: row.title, canExecute: true }));
    assert.deepEqual(projects.body.items, expectedProjects);
    assert.deepEqual(listed.body.items.map(row => [row.id, row.workspaceId]).sort(), sessions.map(row => [row.sessionId, row.workspaceId]).sort());
    for (const workspace of workspaces) {
      const filtered = await call(`/sessions?workspaceId=${workspace.id}`, token);
      assert.equal(filtered.status, 200);
      assert.deepEqual(filtered.body.items.map(row => row.id), sessions.filter(row => row.workspaceId === workspace.id).map(row => row.sessionId));
    }
    const requestId = randomUUID();
    const initial = await call('/sessions', token, { requestId, workspaceId: workspaces[1].id });
    assert.ok(initial.status === 201 || initial.status === 202);
    let receipt = initial.body;
    const deadline = Date.now() + 5000;
    while (receipt.status === 'pending' && Date.now() < deadline) {
      await new Promise(resolve => setImmediate(resolve));
      const observed = await call(`/commands/${requestId}`, token);
      assert.equal(observed.status, 200);
      receipt = observed.body;
    }
    assert.equal(receipt.status, 'accepted');
    assert.equal(receipt.result.sessionId, `session-${requestId}`);
    const attachment = (await baseline()).items.find(row => row.workspaceId === workspaces[1].id);
    assert.ok(attachment.sessionIds.includes(receipt.result.sessionId), 'Real controller attaches companion-created session to registry workspace');
    assert.equal((await call(`/sessions?workspaceId=${workspaces[1].id}`, token)).body.items.length, 2);
    const duplicate = await call('/sessions', token, { requestId, workspaceId: workspaces[1].id });
    assert.equal(duplicate.status, 201);
    assert.deepEqual(duplicate.body, receipt);
    await controller.archiveSession({ sessionId: sessions[0].sessionId });
    assert.equal((await call('/sessions', token)).body.items.some(row => row.id === sessions[0].sessionId), false);
    await controller.rename({ workspaceId: workspaces[1].id, title: 'Synthetic beta renamed' });
    await controller.insertBefore({ workspaceId: workspaces[0].id });
    assert.deepEqual((await call('/workspaces', token)).body.items, (await baseline()).items.map(row => ({ id: row.workspaceId, name: row.title, canExecute: true })));
    assert.equal(deterministic.calls, 0, 'Registry list/create does not call a model');
    const { subagentChecks } = await import('./subagent-checks.mjs');
    const children = await subagentChecks(runtime, loader, projectRoot, directories.workspace, async (parentId, count, hiddenChildId) => {
      // Exercise the actual profile-insert plugin's fresh ctx.get live services.
      const listing = await call('/sessions', token), chat = await call(`/sessions/${parentId}`, token);
      assert.equal(listing.status, 200); assert.equal(chat.status, 200);
      const parentRow = listing.body.items.find(item => item.id === parentId);
      assert.equal(parentRow.runningSubagents, count); assert.equal(parentRow.running, false);
      assert.equal(chat.body.runningSubagents, count); assert.equal(chat.body.session.runningSubagents, count);
      assert.equal(chat.body.session.running, false); assert.equal(chat.body.activity, 'idle');
      if (hiddenChildId) {
        assert.equal(listing.body.items.some(item => item.id === hiddenChildId), false);
        assert.equal((await call(`/sessions/${hiddenChildId}`, token)).status, 404);
      }
    });
    // Real persistence with an intact header and corrupt event body, never a
    // controller throw stub. The profile plugin must use header-only stat on
    // failure and keep the same hiding envelope for GET and SSE.
    const brokenId = `session-broken-body-${randomUUID()}`;
    const brokenHandle = await ctx.sessionPersistence.create({ version: 4, id: brokenId, createdAt: Date.now(), cwd: workspaces[1].path, isSeeded: false });
    await brokenHandle.flush(); await brokenHandle.close();
    let journal;
    for (const project of await readdir(directories.sessions)) {
      const directory = path.join(directories.sessions, project, brokenId);
      let entries;
      try { entries = await readdir(directory); } catch (error) { if (error.code === 'ENOENT') continue; throw error; }
      assert.equal(journal, undefined, 'A synthetic session has one authoritative physical directory');
      assert.ok(entries.includes('session.v4.jsonl'));
      journal = assertOwnedPath(runRoot, path.join(directory, 'session.v4.jsonl'));
    }
    assert.ok(journal); await appendFile(journal, JSON.stringify({ type: 'turn/end', seq: 'PRIVATE invalid seq', time: Date.now(), data: { turn: 1, reason: { kind: 'completed' } } }) + '\n');
    await assert.rejects(async () => { const probe = await ctx.sessionPersistence.open(brokenId, 'read'); try { await probe.read(); } finally { await probe.close(); } }, 'A malformed committed terminal cannot be silently recovered as a torn tail');
    assert.equal((await ctx.sessionPersistence.stat(brokenId)).header.id, brokenId, 'Metadata remains readable without decoding the broken body');
    assert.equal(ctx.sessions.get(brokenId), undefined, 'Broken target is genuinely cold');
    const narrowOffer = admin.createPairing({ readWorkspaceIds: [workspaces[0].id], executeWorkspaceIds: [] });
    const narrow = await call('/pairings', undefined, { pairingToken: narrowOffer.pairingToken, deviceName: 'Synthetic denied phone' });
    const boundaries = [];
    for (const suffix of ['', '/events']) {
      const denied = await call(`/sessions/${brokenId}${suffix}`, narrow.body.deviceToken);
      assert.equal(denied.status, 404); assert.equal(denied.body.error.code, 'not_found');
      const broken = await call(`/sessions/${brokenId}${suffix}`, token);
      assert.equal(broken.status, 500); assert.equal(broken.body.error.code, 'internal_error');
      assert.doesNotMatch(JSON.stringify(broken.body), /PRIVATE|record|jsonl|stack/);
      boundaries.push({ lane: suffix || 'GET', denied: denied.status, permitted: broken.status });
    }
    await controller.archiveSession({ sessionId: brokenId });
    for (const suffix of ['', '/events']) {
      const hidden = await call(`/sessions/${brokenId}${suffix}`, token);
      assert.equal(hidden.status, 404); assert.equal(hidden.body.error.code, 'not_found');
    }
    assert.equal(ctx.sessions.get(brokenId), undefined); assert.equal(ctx.agents.get(brokenId), undefined);
    console.error(`BROKEN_BODY_HTTP ${JSON.stringify(boundaries)}`);
    // The catalog omits an unsupported generation, while stat() throws. Failure
    // admission must hide this unreadable header even for an allowed workspace.
    const futureJournal = assertOwnedPath(runRoot, path.join(path.dirname(journal), 'session.v999.jsonl'));
    await writeFile(futureJournal, JSON.stringify({ version: 999, id: brokenId, createdAt: Date.now(), cwd: workspaces[1].path, isSeeded: false }) + '\n', { flag: 'wx' });
    await controller.unarchiveSession({ sessionId: brokenId });
    await assert.rejects(ctx.sessionPersistence.stat(brokenId), 'Point stat rejects the unreadable future header');
    assert.equal((await ctx.sessionController.list({}, abort.signal)).items.some(row => row.sessionId === brokenId), false, 'Installed listing hides an unreadable header');
    const headerBoundaries = [];
    for (const suffix of ['', '/events']) for (const [scope, auth] of [['denied', narrow.body.deviceToken], ['permitted', token]]) {
      const hidden = await call(`/sessions/${brokenId}${suffix}`, auth);
      assert.equal(hidden.status, 404); assert.equal(hidden.body.error.code, 'not_found');
      assert.doesNotMatch(JSON.stringify(hidden.body), /999|generation|jsonl|PRIVATE|stack/);
      headerBoundaries.push({ lane: suffix || 'GET', scope, status: hidden.status });
    }
    assert.equal(ctx.sessions.get(brokenId), undefined); assert.equal(ctx.agents.get(brokenId), undefined);
    console.error(`BROKEN_HEADER_HTTP ${JSON.stringify(headerBoundaries)}`);
    // Loss remains HTTP 503, not a stale cached registry or explicit fallback.
    const registrySlot = ctx.reflect.store[ctx[Context.isolate].workspaceRegistry];
    // Clear the provided value through Cordis's provider-owned public set API:
    // full provider disposal also unloads the real SessionController (required
    // dependency) and therefore the companion listener. This fault injection
    // isolates fail-closed lookup loss from that separate lifecycle cascade.
    registrySlot.fiber.ctx.set('workspaceRegistry', undefined);
    for (const suffix of ['/workspaces', '/sessions']) {
      const lost = await call(suffix, token);
      assert.equal(lost.status, 503);
      assert.equal(lost.body.error.code, 'workspace_registry_unavailable');
    }
    await companion.fiber.dispose();
    assert.equal(companion.fiber.state, 4, 'Companion fiber fully disposed');
    assert.equal(options.isolation.activeListeners, 0, 'Owned listener closed on profile disposal');
    abort.abort();
    return { checks: ['real-loader-file-url-profile-insert', 'same-realm-registry-get-without-inject', 'three-real-registry-workspaces', 'registry-http-workspaces-and-sessions', 'per-workspace-session-filter', 'registry-create-attaches-session', 'registry-create-requestId-dedup', 'registry-live-rename-order-archive', 'registry-loss-fails-closed-http-503', 'profile-plugin-disposal-closes-listener', 'registry-zero-llm-calls-before-child-canary', 'real-profile-child-summary/snapshot', 'cold-broken-body-denied-get/sse-404', 'cold-broken-body-archived-get/sse-404', 'cold-broken-body-permitted-get/sse-safe-500', 'cold-unreadable-header-stat-throws-list-omits', 'cold-unreadable-header-denied/permitted-get/sse-404', ...children.checks], result: { workspaces: expectedProjects, sessions, createdSessionId: receipt.result.sessionId, http: { workspaces: projects.status, sessions: listed.status }, registryPresent: true, tracingProxyIdentityStable: false, sameRealm: true } };
  } finally {
    admin?.close();
    await loaderFiber.dispose();
  }
}
