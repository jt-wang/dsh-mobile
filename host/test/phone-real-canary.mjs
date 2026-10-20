// Opt-in installed-package acceptance. No live profile, sockets, provider calls or subprocesses.
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, realpath, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL } from 'node:url';
import { composeRuntime, officialLoader } from '../../tools/dsh-canary/composition.mjs';
import { installIsolation, sanitizeEnvironment } from '../../tools/dsh-canary/isolation.mjs';
import { PROMPTS, PROVIDER, MODEL } from '../../tools/dsh-canary/deterministic.mts';
import { waitStarted } from '../../tools/dsh-canary/checks.mjs';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import { FileStaging } from '../src/file-staging.ts';
import { createQuestionBridge } from '../src/question-bridge.ts';

const installation = await realpath(path.join(os.homedir(), 'Documents', 'DeepSeekHarness'));
const runtimeRoot = path.join(installation, 'runtime');
const cache = path.join(installation, 'cache', 'dsh-mobile', 'canary'); await mkdir(cache, { recursive: true });
const runRoot = await mkdtemp(path.join(cache, 'phone-'));
const directories = Object.fromEntries(['home', 'workspace', 'temp'].map(name => [name, path.join(runRoot, name)]));
directories.sessions = path.join(directories.home, 'sessions'); directories.storages = path.join(directories.home, 'storages');
for (const dir of Object.values(directories)) await mkdir(dir, { recursive: true });
sanitizeEnvironment(directories.home, directories.temp);
// Prepare the native private ACL before the strict no-subprocess fence; the
// real data flow below remains fully fenced and never alters that ACL.
const staging = new FileStaging(path.join(directories.home, 'host.sqlite'));
const seed = await staging.save((async function* () { yield Buffer.from('x'); })(), 1, new AbortController().signal); await staging.remove(seed);
const isolation = installIsolation(runRoot, {});
const loader = officialLoader(runtimeRoot); await loader.version();
let runtime, adapter, bridge;
const receipt = { success: false, version: loader.installedVersion, checks: [], isolation };
let timer;
try {
  await Promise.race([(async () => {
    runtime = await composeRuntime(loader, directories);
    const { ctx, deterministic } = runtime;
    const require = createRequire(path.join(runtimeRoot, 'node_modules', '@deepseek-ai', 'dsh', 'package.json'));
    const manifest = JSON.parse(await readFile(path.join(runtimeRoot, 'node_modules', '@deepseek-ai', 'dsh-user-questions', 'package.json'), 'utf8'));
    assert.equal(manifest.version, runtime.version);
    const module = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-user-questions')).href);
    await ctx.plugin(module.default).await();
    const title = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-title')).href);
    await ctx.plugin(title.default, { fallbackMaxWords: 8, fallbackMaxBytes: 100, maxTitleBytes: 256 }).await();
    bridge = createQuestionBridge(listener => ctx.on('user-questions/request', listener, { global: true, prepend: true }));
    const resolveModel = deterministic.resolveModel.bind(deterministic);
    deterministic.resolveModel = async (...args) => ({ ...await resolveModel(...args), inputModalities: ['text', 'image'] });
    adapter = await createDshAdapter({ dshVersion: runtime.version, sessionController: ctx.sessionController, workspaces: [{ id: 'w', name: 'Synthetic canary', path: directories.workspace }], fileUploads: ctx.fileUploads, attachments: ctx.attachments, modelInfo: (p, m, signal) => ctx.llm.resolveModelInfo(p, m, signal), questionBridge: bridge, answerContinued: async (id, callId, answer) => { const result = await ctx.sessionController.resolveAgent(id); if ('error' in result) throw result.error; return ctx.userQuestions.answer(result.agent, callId, answer); } });
    const signal = AbortSignal.timeout(20000);
    const { sessionId } = await adapter.createSession({ workspaceId: 'w', requestId: randomUUID() }, signal, 'w');
    await adapter.mutateSession(sessionId, { kind: 'rename', title: 'Synthetic phone canary' }, signal, 'w');
    assert.equal((await adapter.snapshot(sessionId, signal)).session.title, 'Synthetic phone canary'); receipt.checks.push('real rename');
    const catalog = await adapter.models(signal); assert.equal(catalog.items.find(m => m.id === MODEL).supportsImages, true);
    await adapter.mutateSession(sessionId, { kind: 'model', provider: PROVIDER, model: MODEL }, signal, 'w'); assert.deepEqual((await adapter.snapshot(sessionId, signal)).model, { provider: PROVIDER, id: MODEL }); receipt.checks.push('real model catalog/select + snapshot current next model + deterministic image modality');
    await adapter.prompt(sessionId, PROMPTS.cancel, randomUUID(), signal, 'w'); await waitStarted(deterministic, 'cancel');
    await adapter.prompt(sessionId, PROMPTS.live, randomUUID(), signal, 'w');
    let snapshot = await adapter.snapshot(sessionId, signal); const queued = snapshot.pendingQueue.find(q => q.text === PROMPTS.live); assert.ok(queued);
    await adapter.mutateSession(sessionId, { kind: 'queue', itemId: queued.id, action: 'steer' }, signal, 'w');
    snapshot = await adapter.snapshot(sessionId, signal); assert.equal(snapshot.pendingQueue.find(q => q.id === queued.id).delivery, 'steer');
    const promptId = randomUUID(); await adapter.prompt(sessionId, PROMPTS.reconnect, promptId, signal, 'w', { delivery: 'steer' });
    snapshot = await adapter.snapshot(sessionId, signal); assert.equal(snapshot.pendingQueue.find(q => q.requestId === promptId).delivery, 'steer');
    for (const item of snapshot.pendingQueue) await adapter.mutateSession(sessionId, { kind: 'queue', itemId: item.id, action: 'remove' }, signal, 'w');
    const admittedSteer = snapshot.pendingQueue.find(q => q.requestId === promptId);
    const historyAgent = ctx.agents.get(sessionId);
    historyAgent.session.append('user/message', { id: admittedSteer.id, role: 'user', source: { kind: 'user', rpcId: promptId }, content: [{ type: 'text', text: PROMPTS.reconnect }] }, { surfaceOp: 'append' });
    assert.equal((await adapter.snapshot(sessionId, signal)).messages.find(m => m.id === admittedSteer.id).delivery, 'steer');
    receipt.checks.push('real steer admission + inbox projection + updateQueue steer/remove + delivery correlation on synthetic journal admission');
    const agent = ctx.agents.get(sessionId), callId = 'synthetic-phone-question';
    const answerPromise = ctx.userQuestions.askTimed({ agent, signal, questions: [{ id: 'q', question: 'Synthetic choice?', options: [{ label: 'Yes' }] }] }, callId, 10000);
    assert.ok(bridge.pending(sessionId).length);
    const answer = { answers: [{ id: 'q', selected: ['Yes'] }] };
    await adapter.mutateSession(sessionId, { kind: 'answer', callId, answer }, signal, 'w'); assert.deepEqual(await answerPromise, answer);
    receipt.checks.push('real userQuestions.askTimed/open answer via Cordis waterfall');
    const skipPromise = ctx.userQuestions.askTimed({ agent, signal, questions: [{ id: 'q', question: 'Synthetic skip?', options: [{ label: 'Yes (Recommended)' }] }] }, 'synthetic-skip', 10000);
    assert.equal((await adapter.snapshot(sessionId, signal)).pendingQuestions.find(q => q.callId === 'synthetic-skip').questions[0].options[0].recommended, true);
    await adapter.mutateSession(sessionId, { kind: 'answer', callId: 'synthetic-skip', skip: true }, signal, 'w'); assert.deepEqual(await skipPromise, { answers: [{ id: 'q', selected: [] }] });
    receipt.checks.push('real open question skip + conventional recommended label');
    ctx.sessionController.cancel({ sessionId }); await agent.whenIdle();
    const sharpRequire = createRequire(path.join(runtimeRoot, 'node_modules', '@deepseek-ai', 'dsh-attachment-local', 'package.json'));
    const png = await sharpRequire('sharp')({ create: { width: 1, height: 1, channels: 3, background: { r: 0, g: 0, b: 0 } } }).png().toBuffer();
    const image = await adapter.upload(sessionId, { data: (async function* () { yield png; })(), size: png.length, mime: 'image/png', name: 'synthetic.png' }, signal, 'w');
    await adapter.prompt(sessionId, PROMPTS.mobile, randomUUID(), signal, 'w', { delivery: 'queue', attachments: [image] }); await waitStarted(deterministic, 'mobile');
    const withImage = await adapter.snapshot(sessionId, signal), ref = withImage.messages.flatMap(m => m.attachments ?? []).find(a => a.kind === 'image'); assert.ok(ref);
    const read = await adapter.image(sessionId, ref.id, signal, 'w'); assert.equal(read.ref.mime, 'image/png'); assert.ok(read.data.length > 0);
    await assert.rejects(adapter.image(sessionId, '0'.repeat(64), signal, 'w'), { code: 'not_found' });
    receipt.checks.push('real attachment validation + image prompt admission + journal reference + authorized binary image read-back');
    const hiddenPng = await sharpRequire('sharp')({ create: { width: 1, height: 1, channels: 3, background: { r: 255, g: 0, b: 0 } } }).png().toBuffer();
    const [hiddenRef] = await ctx.attachments.saveImages([{ data: hiddenPng, mediaType: 'image/png', name: 'synthetic-hidden.png' }]);
    agent.session.append('tool/result', { turn: 1, step: 1, message: { id: 'hidden-image-tool', role: 'tool', toolCallId: 'synthetic-hidden', content: [{ type: 'image', attachment: hiddenRef }] } }, { surfaceOp: 'append' });
    await assert.rejects(adapter.image(sessionId, hiddenRef.attachmentId, signal, 'w'), { code: 'not_found' });
    const queuePng = await sharpRequire('sharp')({ create: { width: 1, height: 1, channels: 3, background: { r: 0, g: 255, b: 0 } } }).png().toBuffer();
    const [queueRef] = await ctx.attachments.saveImages([{ data: queuePng, mediaType: 'image/png', name: 'synthetic-queue.png' }]);
    agent.inbox.append('next-turn', { id: 'synthetic-hidden-inbox', role: 'user', source: { kind: 'user' }, content: [{ type: 'image', attachment: queueRef }] });
    await assert.rejects(adapter.image(sessionId, queueRef.attachmentId, signal, 'w'), { code: 'not_found' });
    agent.inbox.clear(); receipt.checks.push('real hidden tool-result and inbox-only images denied');
    const beforeReceipts = ctx.fileUploads.stagedFiles.get(agent.session)?.size ?? 0;
    for (let i = 0; i < 24; i++) { const abandoned = await staging.save((async function* () { yield Buffer.from('synthetic'); })(), 9, signal); await staging.remove(abandoned); }
    assert.equal(ctx.fileUploads.stagedFiles.get(agent.session)?.size ?? 0, beforeReceipts);
    const stagedFile = await staging.save((async function* () { yield Buffer.from('synthetic file'); })(), 14, signal);
    assert.equal(ctx.fileUploads.stagedFiles.get(agent.session)?.size ?? 0, beforeReceipts);
    const file = await adapter.upload(sessionId, { data: staging.read(stagedFile, signal), size: 14, mime: 'text/plain', name: 'synthetic.txt' }, signal, 'w');
    await adapter.prompt(sessionId, PROMPTS.live, randomUUID(), signal, 'w', { delivery: 'queue', attachments: [file] });
    assert.ok((await adapter.snapshot(sessionId, signal)).pendingQueue.some(q => q.attachments?.some(a => a.kind === 'file')));
    await staging.remove(stagedFile);
    receipt.checks.push('24 abandoned private disk picks create zero upstream receipts; send-time real uploadStream + receipt prompt');
    await assert.rejects(adapter.file(sessionId, file.ref.id, signal, 'w'), { code: 'not_found' });
    const fileItem = (await adapter.snapshot(sessionId, signal)).pendingQueue.find(q => q.attachments?.some(a => a.kind === 'file'));
    await adapter.mutateSession(sessionId, { kind: 'queue', itemId: fileItem.id, action: 'remove' }, signal, 'w');
    agent.session.append('user/message', { id: 'synthetic-file-journal', role: 'user', source: { kind: 'user' }, content: [{ type: 'file', attachment: { attachmentId: file.ref.id, name: file.ref.name, bytes: file.ref.size } }] }, { surfaceOp: 'append' });
    assert.equal(Buffer.from((await adapter.file(sessionId, file.ref.id, signal, 'w')).data).toString(), 'synthetic file');
    await assert.rejects(adapter.file(sessionId, 'sha256:' + '0'.repeat(64), signal, 'w'), { code: 'not_found' });
    receipt.checks.push('real streamed file store read-back, synthetic journal reference, queue-only/unreferenced denial');
    for (const item of (await adapter.snapshot(sessionId, signal)).pendingQueue) await adapter.mutateSession(sessionId, { kind: 'queue', itemId: item.id, action: 'remove' }, signal, 'w');
    const continuedCall = 'synthetic-continued-call';
    const inspection = await ctx.sessionController.inspect(sessionId, signal);
    const previousHeader = inspection.events.findLast(e => e.type === 'request/header').data;
    agent.session.append('request/header', { ...previousHeader, header: { ...previousHeader.header, tools: [{ name: 'ask_user_question', description: 'Synthetic only', parameters: { type: 'object', properties: { timeout: { type: 'number' } } } }] } });
    agent.session.append('tool/call', { turn: 2, step: 1, callId: continuedCall, name: 'ask_user_question', arguments: JSON.stringify({ questions: [{ id: 'q', question: 'Synthetic continued?', options: [{ label: 'Yes' }] }] }) });
    agent.session.append('tool/result', { turn: 2, step: 1, message: { id: 'synthetic-result', role: 'tool', toolCallId: continuedCall, content: [{ type: 'text', text: JSON.stringify({ pending: true, callId: continuedCall }) }] } }, { surfaceOp: 'append' });
    assert.ok((await adapter.snapshot(sessionId, signal)).pendingQuestions.some(q => q.callId === continuedCall && q.state === 'continued'));
    await adapter.mutateSession(sessionId, { kind: 'answer', callId: continuedCall, answer }, signal, 'w');
    assert.ok(agent.inbox.nextStep.some(m => m.source.kind === 'user-question-reply'));
    receipt.checks.push('real continued projection + userQuestions.answer (synthetic append-only question events)');
    agent.inbox.clear();
    agent.session.append('user/message', { id: 'synthetic-admitted-answer', role: 'user', source: { kind: 'user-question-reply', callId: continuedCall, outcome: 'answered' }, content: [{ type: 'text', text: JSON.stringify({ answers: answer.answers }) }] }, { surfaceOp: 'append' });
    assert.ok((await adapter.snapshot(sessionId, signal)).resolvedQuestions.some(q => q.callId === continuedCall && q.outcome === 'answered'));
    receipt.checks.push('real settled projection survives snapshot reopen (synthetic admitted reply event)');
    const dedupId = randomUUID(); await adapter.prompt(sessionId, 'Synthetic dedup seed', dedupId, signal, 'w');
    const clock = Date.now; let now = clock();
    try {
      Date.now = () => now;
      for (let i = 0; i < 20; i++) {
        const unbound = await adapter.upload(sessionId, { data: (async function* () { yield Buffer.from('synthetic'); })(), size: 9, mime: 'text/plain', name: 'synthetic.txt' }, signal, 'w');
        await adapter.prompt(sessionId, 'Synthetic duplicate ignored upstream', dedupId, signal, 'w', { attachments: [unbound] });
        if (i % 5 === 4) now += 11 * 60 * 1000;
      }
      await assert.rejects(adapter.upload(sessionId, { data: (async function* () { yield Buffer.from('synthetic'); })(), size: 9, mime: 'text/plain', name: 'synthetic.txt' }, signal, 'w'), { code: 'rate_limited' });
      assert.equal(ctx.fileUploads.stagedFiles.get(agent.session).size, 20);
      receipt.checks.push('real dedup ACK leaves 20 unbound receipts reserved across TTL intervals; 21st upload denied');
    } finally { Date.now = clock; }
    // Fill the remaining global budget with bound inbox receipts, then retire
    // them outside the adapter (the real queue-remove service path).
    for (const count of [20, 20, 4]) {
      const created = await adapter.createSession({ workspaceId: 'w', requestId: randomUUID() }, signal, 'w');
      await adapter.prompt(created.sessionId, PROMPTS.cancel, randomUUID(), signal, 'w');
      for (let i = 0; i < count; i++) {
        const item = await adapter.upload(created.sessionId, { data: (async function* () { yield Buffer.from('synthetic'); })(), size: 9, mime: 'text/plain', name: 'synthetic.txt' }, signal, 'w');
        await adapter.prompt(created.sessionId, 'Synthetic queued file', randomUUID(), signal, 'w', { attachments: [item] });
      }
      for (const item of (await adapter.snapshot(created.sessionId, signal)).pendingQueue) await adapter.mutateSession(created.sessionId, { kind: 'queue', itemId: item.id, action: 'remove' }, signal, 'w');
      assert.equal(ctx.fileUploads.stagedFiles.get(ctx.agents.get(created.sessionId).session)?.size ?? 0, 0);
    }
    const fresh = await adapter.createSession({ workspaceId: 'w', requestId: randomUUID() }, signal, 'w');
    const slot = await adapter.reserveFileUploads(fresh.sessionId, 1, signal, 'w');
    await adapter.upload(fresh.sessionId, { reservation: slot, data: (async function* () { yield Buffer.from('synthetic'); })(), size: 9, mime: 'text/plain', name: 'synthetic.txt' }, signal, 'w'); slot.release();
    receipt.checks.push('real cross-session retired receipts reclaimed before new-session quota reservation; unbound receipts stay capped');
    receipt.success = true;
  })(), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Bounded phone canary timeout')), 30000); })]);
} catch (error) { receipt.error = { message: error.message, stack: error.stack }; process.exitCode = 1; }
finally {
  clearTimeout(timer); await staging.close(); bridge?.close(); adapter?.dispose(); await runtime?.dispose();
  assert.equal(isolation.networkAttempts, 0); assert.equal(isolation.subprocessAttempts, 0); assert.equal(isolation.outsideWrites, 0);
  await writeFile(path.join(runRoot, 'receipt.json'), JSON.stringify(receipt, null, 2));
  console.log(JSON.stringify({ ...receipt, receipt: path.join(runRoot, 'receipt.json') }, null, 2));
}
