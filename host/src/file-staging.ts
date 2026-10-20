import { lstat, open, readdir, unlink } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { randomBytes } from 'node:crypto';
import { privateDirectory, privatePath } from './private-files.ts';
import { HostError } from './errors.ts';
import { UPLOAD_LIMIT } from './phone.ts';

/** Plugin-owned transient bytes; never use a display name as a filesystem path. */
export class FileStaging {
  readonly directory: string;
  private ready?: Promise<void>;
  private readonly entries = new Map<string, { size: number; removing?: Promise<void> }>();
  constructor(statePath: string) { this.directory = resolve(dirname(statePath), 'mobile-uploads'); }
  assertOutside(workspaces: readonly { path: string }[]): void {
    for (const workspace of workspaces) {
      const path = relative(resolve(workspace.path), this.directory);
      if (!path || (!isAbsolute(path) && path !== '..' && !path.startsWith('..' + sep))) throw new HostError('unsafe_private_path');
    }
  }
  private prepare(): Promise<void> { return this.ready ??= privateDirectory(this.directory); }
  /** Runtime lock is held before this runs; old opaque upload IDs cannot replay. */
  async startup(workspaces: readonly { path: string }[]): Promise<void> {
    try { await lstat(this.directory); } catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return; throw error; }
    this.assertOutside(workspaces); await this.prepare();
    for (const entry of await readdir(this.directory, { withFileTypes: true })) {
      if (!/^[a-f0-9]{48}\.tmp$/.test(entry.name) || !entry.isFile() || entry.isSymbolicLink()) throw new HostError('unsafe_private_path');
      const target = await privatePath(join(this.directory, entry.name));
      if (dirname(target) !== this.directory) throw new HostError('unsafe_private_path');
      await unlink(target);
    }
  }
  async save(data: AsyncIterable<Uint8Array>, size: number, signal: AbortSignal): Promise<string> {
    // Reserve before any await, including directory creation and concurrent saves.
    if (size > UPLOAD_LIMIT || size < 1) throw new HostError('payload_too_large');
    if (this.entries.size >= 64 || [...this.entries.values()].reduce((n, e) => n + e.size, 0) + size > 512 * 1024 * 1024) throw new HostError('rate_limited');
    const path = join(this.directory, randomBytes(24).toString('hex') + '.tmp');
    this.entries.set(path, { size });
    try {
      await this.prepare(); signal.throwIfAborted();
      // The Windows private directory grants only this SID, including inherited
      // file access; POSIX creation is 0600. O_EXCL rejects existing/symlink names.
      const handle = await open(path, 'wx', 0o600);
      try {
        let received = 0;
        for await (const chunk of data) {
          signal.throwIfAborted(); received += chunk.byteLength;
          if (received > size) throw new HostError('payload_too_large');
          let offset = 0;
          while (offset < chunk.byteLength) { const result = await handle.write(chunk, offset, chunk.byteLength - offset); offset += result.bytesWritten; }
        }
        signal.throwIfAborted(); if (received !== size) throw new HostError('invalid_request');
      } finally { await handle.close(); }
      return path;
    } catch (error) { await this.remove(path); throw error; }
  }
  read(path: string, signal: AbortSignal): AsyncIterable<Uint8Array> {
    if (!this.entries.has(path) || dirname(path) !== this.directory) throw new HostError('not_found');
    return createReadStream(path, { highWaterMark: 65536, signal });
  }
  async remove(path: string): Promise<void> {
    const entry = this.entries.get(path); if (!entry) return;
    if (dirname(path) !== this.directory || !/^[a-f0-9]{48}\.tmp$/.test(path.slice(this.directory.length + 1))) throw new HostError('unsafe_private_path');
    if (entry.removing) return entry.removing;
    entry.removing = (async () => {
      try { await unlink(path); } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
      this.entries.delete(path);
    })();
    try { await entry.removing; } finally { delete entry.removing; }
  }
  async close(): Promise<void> { await Promise.all([...this.entries.keys()].map(path => this.remove(path))); }
}
