// Disposable loopback notification acceptance fixture; never imports the installed runtime.
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { startHostServer } from '../host/src/server.ts';
const parent = process.env.DSH_MOBILE_TEST_CACHE;
if (!parent) throw new Error('Set DSH_MOBILE_TEST_CACHE');
await mkdir(parent, { recursive: true });
const root = await mkdtemp(join(parent, 'notification-'));
let cursor = -1, turn = 0;
const completed = [], listeners = new Set();
const source = {
 subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
 async list() { return [{ id: 'demo-session', workspaceId: 'w', running: true }]; },
 async evidence() { return { sessionId: 'demo-session', workspaceId: 'w', cursor, pending: false, valid: true, completed: [...completed] }; }
};
const session = { id: 'demo-session', title: 'Synthetic notification', workspaceId: 'w', updatedAt: 1, running: false };
const adapter = { upstreamVersion: 'fixture', notifications: () => source,
 async listSessions() { return [session]; }, async listPresets() { return []; }, async sessionScope() { return 'w'; },
 async snapshot() { return { session, cursor: Math.max(0, cursor), hasMore: false, activity: 'idle', messages: [] }; }
};
await mkdir(join(root, 'workspace'));
const host = await startHostServer({ adapter, config: { bind: '127.0.0.1', port: 0, allowInsecureLoopback: true, hostName: 'Synthetic notification fixture', statePath: join(root, 'state.sqlite'), workspaces: [{ id: 'w', name: 'Synthetic', path: join(root, 'workspace') }] } });
const { baseUrl } = await host.start();
const pair = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: ['w'] }).pairingToken, 'Synthetic emulator');
await writeFile(join(root, 'config.json'), JSON.stringify({ baseUrl, ...pair }), { flag: 'wx', mode: 0o600 });
const control = createServer((req, res) => {
 if (req.url === '/answer') { cursor++; turn++; completed.push({ turn, sourceSeq: cursor }); for (const fn of listeners) fn('demo-session', { seq: cursor, type: 'turn/end', time: Date.now(), turn, completed: true }); }
 else if (req.url === '/gap') { cursor += 2; for (const fn of listeners) fn('demo-session', { seq: cursor, type: 'tool/call', time: Date.now() }); }
 else { res.writeHead(404).end(); return; }
 res.end('ok');
});
await new Promise(resolve => control.listen(18981, '127.0.0.1', resolve));
console.log(JSON.stringify({ root, port: new URL(baseUrl).port }));
for (const event of ['SIGINT', 'SIGTERM']) process.once(event, async () => { control.close(); await host.close(); });
