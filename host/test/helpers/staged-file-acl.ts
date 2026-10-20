import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { assertPrivate, privatePath } from '../../src/private-files.ts';

/** Staged files inherit the private DACL, but their owner comes from the process token. */
export async function assertStagedFilePrivate(path: string): Promise<void> {
  if (process.platform !== 'win32') return assertPrivate(path);
  const full = await privatePath(path);
  // An elevated Windows token can default new file ownership to Administrators.
  // IsInRole checks the effective token: a filtered, non-elevated admin does not qualify.
  const script = `$ErrorActionPreference='Stop'; Import-Module (Join-Path $PSHOME 'Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1') -ErrorAction Stop;
$id=[Security.Principal.WindowsIdentity]::GetCurrent(); $principal=[Security.Principal.WindowsPrincipal]::new($id);
$a=Get-Acl -LiteralPath $env:DSHM_TEST_STAGED_PATH;
@{ user=$id.User.Value; owner=$a.GetOwner([Security.Principal.SecurityIdentifier]).Value; elevated=$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator); rules=@($a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]) | ForEach-Object { @{ sid=$_.IdentityReference.Value; rights=[string]$_.FileSystemRights; type=[string]$_.AccessControlType } }) } | ConvertTo-Json -Depth 5 -Compress`;
  const descriptor = JSON.parse(execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], {
    env: { ...process.env, DSHM_TEST_STAGED_PATH: full }, encoding: 'utf8', windowsHide: true, timeout: 15000,
  }).replace(/^\uFEFF/, '')) as { user: string; owner: string; elevated: boolean; rules: { sid: string; rights: string; type: string }[] };
  assert.deepEqual(descriptor.rules, [{ sid: descriptor.user, rights: 'FullControl', type: 'Allow' }], 'staged file must grant access only to the current user');
  assert.ok(descriptor.owner === descriptor.user || (descriptor.elevated === true && descriptor.owner === 'S-1-5-32-544'), 'staged file owner must be the current user, or Administrators only for an elevated token');
}
