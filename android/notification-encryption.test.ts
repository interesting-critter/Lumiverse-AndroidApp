import { expect, test } from 'bun:test'
import { createCipheriv, createHash, randomBytes } from 'node:crypto'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

test('Android JVM decrypts the backend FCM wire format and rejects altered routing', () => {
  const jdk = process.env.JAVA_HOME || '/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  const output = mkdtempSync(join(tmpdir(), 'lumiverse-fcm-encryption-'))
  try {
    const compile = Bun.spawnSync([join(jdk, 'bin/javac'), '-d', output,
      join(import.meta.dir, 'src/chat/lumiverse/prototype/NotificationEncryption.java'),
      join(import.meta.dir, 'tests/NotificationEncryptionCheck.java')])
    expect(compile.stderr.toString()).toBe(''); expect(compile.exitCode).toBe(0)
    const key = randomBytes(32), nonce = randomBytes(12), keyId = createHash('sha256').update(key).digest('hex').slice(0, 32)
    const fields = ['lumiverse-bc911', 'chat.lumiverse.prototype', 'e'.repeat(43), 'https://private.example:8443', 'lvdi_' + 's'.repeat(32), keyId]
    const aad = JSON.stringify(['lumiverse-fcm', 1, ...fields])
    const clear = JSON.stringify({ title: 'Alice 🦊', body: 'Private response ready', accountId: 'owner' })
    const cipher = createCipheriv('aes-256-gcm', key, nonce); cipher.setAAD(Buffer.from(aad))
    const ciphertext = Buffer.concat([cipher.update(clear, 'utf8'), cipher.final()])
    // Keep argv ASCII even when the Linux build container uses the C locale.
    // The fixture decodes the expected Unicode text explicitly as UTF-8.
    const run = Bun.spawnSync([join(jdk, 'bin/java'), '-cp', output, 'chat.lumiverse.prototype.NotificationEncryptionCheck',
      key.toString('base64'), nonce.toString('base64'), ciphertext.toString('base64'), cipher.getAuthTag().toString('base64'), ...fields, aad, Buffer.from(clear, 'utf8').toString('base64')],
      { env: { ...process.env, LANG: 'C', LC_ALL: 'C' } })
    expect(run.stderr.toString()).toBe(''); expect(run.exitCode).toBe(0)
  } finally { rmSync(output, { recursive: true, force: true }) }
})
