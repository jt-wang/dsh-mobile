import { mkdir, readdir, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
const [serial, destination] = process.argv.slice(2);
if (!['emulator-5580', 'emulator-5582'].includes(serial) || !destination) throw new Error('Approved emulator and destination required');
const adb = path.join(process.env.LOCALAPPDATA, 'Android/Sdk/platform-tools/adb.exe');
const dir = 'files/phone08';
const list = spawnSync(adb, ['-s', serial, 'shell', 'run-as', 'dev.dshmobile.app', 'ls', dir], { encoding: 'utf8' });
if (list.status) throw new Error('Cannot list test screenshots');
await mkdir(destination, { recursive: true });
for (const file of list.stdout.trim().split(/\s+/).filter(n => /^[a-zA-Z0-9_-]+\.png$/.test(n))) {
 const result = spawnSync(adb, ['-s', serial, 'exec-out', 'run-as', 'dev.dshmobile.app', 'cat', `${dir}/${file}`], { maxBuffer: 10 * 1024 * 1024 });
 if (result.status) throw new Error('Cannot retrieve screenshot');
 await writeFile(path.join(destination, file), result.stdout);
}
console.log(`Saved ${(await readdir(destination)).filter(n => n.endsWith('.png')).length} screenshots`);
