// Private loopback fixture config for tools/android-acceptance.ps1. No real DSH or provider.
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { runDemo } from '../host/src/demo.ts';
import { FIXTURE } from '../host/src/fixture-adapter.ts';
const parent = process.env.DSH_MOBILE_TEST_CACHE;
if (!parent) throw new Error('Private cache directory required');
await mkdir(parent, { recursive: true });
const root = await mkdtemp(join(parent, 'legacy08-'));
const demo = await runDemo({ stateDirectory: root, answerDelayMs: 10000 });
await writeFile(join(root, 'acceptance.json'), JSON.stringify({ version: 1, mode: 'fixture', invitation: demo.invitation,
 workspaceId: FIXTURE.workspaceId, existingSessionId: FIXTURE.existingSessionId, existingMessage: FIXTURE.existingMessage,
 createdSessionTitle: FIXTURE.createdSessionTitle, expectedAssistantText: FIXTURE.expectedAssistantText }), { mode: 0o600, flag: 'wx' });
console.log(JSON.stringify({ root, port: new URL(demo.invitation.baseUrl).port }));
for (const event of ['SIGINT', 'SIGTERM']) process.once(event, () => demo.close());
