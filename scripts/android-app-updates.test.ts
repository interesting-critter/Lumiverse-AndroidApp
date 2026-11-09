import { expect, test } from 'bun:test'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

test('Android APK updates validate published assets, compare install versions, and throttle checks and reminders', () => {
  const jdk = process.env.JAVA_HOME || '/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  const output = mkdtempSync(join(tmpdir(), 'lumiverse-app-updates-'))
  try {
    const compile = Bun.spawnSync([join(jdk, 'bin/javac'), '-d', output,
      join(import.meta.dir, '../android/src/chat/lumiverse/prototype/AppUpdatePolicy.java'),
      join(import.meta.dir, '../android/tests/AppUpdatePolicyCheck.java')])
    expect(compile.stderr.toString()).toBe('')
    expect(compile.exitCode).toBe(0)
    const run = Bun.spawnSync([join(jdk, 'bin/java'), '-cp', output, 'chat.lumiverse.prototype.AppUpdatePolicyCheck'])
    expect(run.stderr.toString()).toBe('')
    expect(run.exitCode).toBe(0)
  } finally { rmSync(output, { recursive: true, force: true }) }
})
