// Keep credentials out of command arguments, shell output and tracked files.
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
const [root, serial] = process.argv.slice(2);
if (!root || !['emulator-5580', 'emulator-5582'].includes(serial)) throw new Error('Provide private fixture root and approved emulator serial');
const config = JSON.parse((await readFile(path.join(root, 'config.json'), 'utf8')).replace(/^\uFEFF/, ''));
const port = Number(new URL(config.baseUrl).port);
if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error('Invalid fixture port');
const adb = path.join(process.env.LOCALAPPDATA, 'Android/Sdk/platform-tools/adb.exe');
let result = spawnSync(adb, ['-s', serial, 'reverse', `tcp:${port}`, `tcp:${port}`], { stdio: 'inherit' });
if (result.status) process.exit(result.status);
result = spawnSync(adb, ['-s', serial, 'shell', 'run-as', 'dev.dshmobile.app', 'sh', '-c', "'cat > files/phone08-config.json'"], { input: JSON.stringify({ port, deviceId: config.deviceId, deviceToken: config.deviceToken }), stdio: ['pipe', 'inherit', 'inherit'] });
if (result.error || result.status) throw new Error('Private fixture staging failed');
