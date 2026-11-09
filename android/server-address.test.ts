import { expect, test } from 'bun:test'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

test('native server validation accepts HTTP/HTTPS LAN and IPv6 origins and rejects credentials or non-web addresses', () => {
  const bundledJdk = '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin'
  const javaBin = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin') : existsSync(bundledJdk) ? bundledJdk : ''
  const output = mkdtempSync(join(tmpdir(), 'lumiverse-server-address-'))
  try {
    const compile = Bun.spawnSync([join(javaBin, 'javac'), '-d', output,
      join(import.meta.dir, 'src/chat/lumiverse/prototype/ServerAddress.java'),
      join(import.meta.dir, 'src/chat/lumiverse/prototype/ConnectionHistory.java'),
      join(import.meta.dir, 'tests/ConnectionHistoryCheck.java'),
      join(import.meta.dir, 'tests/ServerAddressCheck.java')])
    expect(compile.stderr.toString()).toBe('')
    expect(compile.exitCode).toBe(0)
    const check = Bun.spawnSync([join(javaBin, 'java'), '-cp', output, 'chat.lumiverse.prototype.ServerAddressCheck'])
    expect(check.stderr.toString()).toBe('')
    expect(check.exitCode).toBe(0)
    const history = Bun.spawnSync([join(javaBin, 'java'), '-cp', output, 'chat.lumiverse.prototype.ConnectionHistoryCheck'])
    expect(history.stderr.toString()).toBe('')
    expect(history.exitCode).toBe(0)
  } finally { rmSync(output, { recursive: true, force: true }) }
})
