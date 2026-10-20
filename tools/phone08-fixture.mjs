// Loopback-only phone 0.8 fixture. Never imports or contacts DSH/model providers.
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { deflateSync, crc32 } from 'node:zlib';
import { startHostServer } from '../host/src/server.ts';
const parent = process.env.DSH_MOBILE_TEST_CACHE;
if (!parent) throw new Error('Set DSH_MOBILE_TEST_CACHE to a private cache directory');
await mkdir(parent, { recursive: true }); const root = await mkdtemp(join(parent, 'phone08-'));
const chunk = (type, data) => { const body = Buffer.concat([Buffer.from(type), data]); const header = Buffer.alloc(4); header.writeUInt32BE(data.length); const checksum = Buffer.alloc(4); checksum.writeUInt32BE(crc32(body)); return Buffer.concat([header, body, checksum]); };
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(64); ihdr.writeUInt32BE(48, 4); ihdr[8] = 8; ihdr[9] = 2;
const pixels = Buffer.alloc((64 * 3 + 1) * 48, 180); for (let row = 0; row < 48; row++) pixels[row * 193] = 0;
const image = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
const ref = { id: 'synthetic-image', name: 'synthetic.png', size: image.length, mime: 'image/png', kind: 'image' };
let snapshot = { session: { id: 'chat', workspaceId: 'w', title: 'Synthetic phone 0.8', updatedAt: 1, running: true }, cursor: 1, hasMore: false, activity: 'waiting', messages: [{ id: 'image-message', role: 'assistant', text: 'Synthetic image only', createdAt: 1, attachments: [ref, { id: 'synthetic-file', name: 'synthetic.txt', size: 19, mime: 'text/plain', kind: 'file' }] }], pendingQueue: [{ id: 'queue-item', text: 'Synthetic queued message', delivery: 'queue' }], model: { provider: 'fixture', id: 'vision' }, resolvedQuestions: [], pendingQuestions: [{ callId: 'question', state: 'open', questions: [{ id: 'q', question: 'Which synthetic check?', options: [{ label: 'Unit tests' }, { label: 'Emulator' }], multiSelect: true, allowFreeText: true }] }] };
const listeners = new Set(); const wake = () => { snapshot.cursor++; snapshot.session.updatedAt++; for (const fn of listeners) fn(); };
const calls = [];
const notificationListeners = new Set();
const notificationSource = {
 subscribe(fn) { notificationListeners.add(fn); return () => notificationListeners.delete(fn); },
 async list() { return [{ id: 'chat', workspaceId: 'w', running: true }]; },
 async evidence() { return { sessionId: 'chat', workspaceId: 'w', cursor: snapshot.cursor, pending: snapshot.pendingQuestions.length > 0, valid: true, completed: [] }; }
};
const adapter = {
 notifications: () => notificationSource,
 upstreamVersion: 'fixture', phoneCapabilities: () => ({ steer: true, queue: true, rename: true, models: true, attachments: true, questions: true, questionSkip: true, fileDownload: true }),
 async listPresets() { return []; }, async listSessions() { return [snapshot.session]; }, async sessionScope() { return 'w'; }, async snapshot() { return structuredClone(snapshot); },
 async *watch(_, signal) { while (!signal.aborted) { yield structuredClone(snapshot); await new Promise(resolve => { let timer; const done = () => { clearTimeout(timer); listeners.delete(done); signal.removeEventListener('abort', done); resolve(); }; listeners.add(done); signal.addEventListener('abort', done, { once: true }); timer = setTimeout(done, 2000); }); } },
 async createSession() { return { sessionId: 'chat' }; }, async cancel() { snapshot.session.running = false; snapshot.activity = 'idle'; wake(); },
 async prompt(id, text, requestId, signal, workspace, options) { calls.push('prompt'); await writeFile(join(root, 'prompt-received.json'), JSON.stringify({ requestId, delivery: options?.delivery, attachments: options?.attachments?.map(a => a.ref) })); snapshot.messages.push({ id: requestId, requestId, text, role: 'user', createdAt: Date.now(), attachments: options?.attachments?.map(a => a.ref) ?? [] }); wake(); },
 async models() { return { items: [{ provider: 'fixture', id: 'vision', name: 'Synthetic vision', supportsImages: true }, { provider: 'fixture', id: 'text', name: 'Synthetic text', supportsImages: false }] }; },
 async mutateSession(_, mutation) { calls.push(mutation.kind); if (mutation.kind === 'rename') snapshot.session.title = mutation.title; if (mutation.kind === 'queue') snapshot.pendingQueue = mutation.action === 'remove' ? snapshot.pendingQueue.filter(q => q.id !== mutation.itemId) : snapshot.pendingQueue.map(q => q.id === mutation.itemId ? { ...q, delivery: 'steer' } : q); if (mutation.kind === 'model') snapshot.model = { provider: mutation.provider, id: mutation.model }; if (mutation.kind === 'answer') { snapshot.resolvedQuestions = [{ callId: mutation.callId, outcome: mutation.skip ? 'skipped' : 'answered', answers: mutation.answer?.answers ?? [] }]; snapshot.pendingQuestions = []; snapshot.activity = 'running'; await writeFile(join(root, 'answer-received.json'), JSON.stringify({ callId: mutation.callId, answer: mutation.answer })); } wake(); for (const listener of notificationListeners) listener('chat', { seq: snapshot.cursor, type: 'tool/result', time: Date.now() }); },
 async upload(_, input) { let size = 0; for await (const chunk of input.data) size += chunk.length; return { ref: { ...ref, id: 'uploaded-image', name: input.name, size, mime: input.mime, kind: input.mime.startsWith('image/') ? 'image' : 'file' }, upstream: {} }; },
 async file() { return { data: Buffer.from('Synthetic file only'), ref: { id: 'synthetic-file', name: 'synthetic.txt', size: 19, mime: 'text/plain', kind: 'file' } }; },
 async image() { return { data: image, ref }; }
};
await mkdir(join(root, 'workspace'));
const host = await startHostServer({ adapter, config: { bind: '127.0.0.1', port: 0, allowInsecureLoopback: true, hostName: 'Synthetic phone fixture', statePath: join(root, 'state.sqlite'), workspaces: [{ id: 'w', name: 'Synthetic workspace', path: join(root, 'workspace') }] } });
const { baseUrl } = await host.start();
const pair = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: ['w'] }).pairingToken, 'Synthetic Android test');
await writeFile(join(root, 'config.json'), JSON.stringify({ baseUrl, ...pair }), { flag: 'wx', mode: 0o600 });
console.log(JSON.stringify({ root, port: new URL(baseUrl).port }));
for (const event of ['SIGINT', 'SIGTERM']) process.once(event, async () => { await host.close(); await writeFile(join(root, 'calls.json'), JSON.stringify(calls)); });
