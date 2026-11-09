import { expect, test } from 'bun:test'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

test('native notification and download policies canonicalize origins and isolate servers', () => {
  const jdk = process.env.JAVA_HOME || '/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  const output = mkdtempSync(join(tmpdir(), 'lumiverse-notification-policy-'))
  try {
    const compile = Bun.spawnSync([join(jdk, 'bin/javac'), '-d', output,
      join(import.meta.dir, 'src/chat/lumiverse/prototype/ServerAddress.java'),
      join(import.meta.dir, 'src/chat/lumiverse/prototype/NotificationPolicy.java'),
      join(import.meta.dir, 'src/chat/lumiverse/prototype/DownloadPolicy.java'),
      join(import.meta.dir, 'tests/NotificationPolicyCheck.java'),
      join(import.meta.dir, 'tests/DownloadPolicyCheck.java')])
    expect(compile.stderr.toString()).toBe('')
    expect(compile.exitCode).toBe(0)
    const run = Bun.spawnSync([join(jdk, 'bin/java'), '-cp', output, 'chat.lumiverse.prototype.NotificationPolicyCheck'])
    expect(run.stderr.toString()).toBe('')
    expect(run.exitCode).toBe(0)
    const downloads = Bun.spawnSync([join(jdk, 'bin/java'), '-cp', output, 'chat.lumiverse.prototype.DownloadPolicyCheck'])
    expect(downloads.stderr.toString()).toBe('')
    expect(downloads.exitCode).toBe(0)
  } finally { rmSync(output, { recursive: true, force: true }) }
})
