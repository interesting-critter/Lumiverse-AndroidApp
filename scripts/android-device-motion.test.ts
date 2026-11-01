import { afterEach, expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'

const script = readFileSync(new URL('../android/tauri/src-tauri/src/device-motion.js', import.meta.url), 'utf8')
const windows: JSDOM[] = []
afterEach(() => { for (const dom of windows.splice(0)) dom.window.close() })

function fixture(bridge = true) {
  const dom = new JSDOM('', { url: 'http://192.168.1.10:7860', runScripts: 'outside-only' })
  windows.push(dom)
  const messages: any[] = []
  const channel = { postMessage(raw: string) { messages.push(JSON.parse(raw)) }, onmessage: (_: { data: string }) => {} }
  if (bridge) Object.assign(dom.window, { lumiverseDeviceMotion: channel })
  dom.window.eval(script)
  return { dom, channel, messages, native: (dom.window as any).__LUMIVERSE_ANDROID_MOTION__ }
}

test('only exposes motion when the origin-scoped native channel exists', () => {
  expect(fixture(false).native).toBeUndefined()
  const { dom, native, messages } = fixture()
  expect(native.version).toBe(1)
  expect(Object.isFrozen(native)).toBe(true)
  expect(messages).toEqual([{ id: 'document', documentID: native.documentID, action: 'motion.document', payload: {} }])
  dom.window.eval(script)
  expect(messages).toHaveLength(1)
})

test('correlates concurrent replies and propagates native errors', async () => {
  const { native, channel, messages } = fixture()
  const start = native.request('motion.start', { id: 'rotation-1' })
  const stop = native.request('motion.stop', { id: 'rotation-1' })
  expect(messages[1]).toMatchObject({ documentID: native.documentID, action: 'motion.start', payload: { id: 'rotation-1' } })
  channel.onmessage({ data: JSON.stringify({ id: messages[2].id, result: true }) })
  await expect(stop).resolves.toBe(true)
  channel.onmessage({ data: 'invalid' })
  channel.onmessage({ data: JSON.stringify({ id: 'stale', result: {} }) })
  channel.onmessage({ data: JSON.stringify({ id: messages[1].id, error: 'unavailable' }) })
  await expect(start).rejects.toThrow('unavailable')
})

test('page departure stops sensors and rejects pending starts; new documents get a new token', async () => {
  const { dom, native, messages } = fixture()
  const pending = native.request('motion.start', { id: 'rotation-1' })
  dom.window.dispatchEvent(new dom.window.Event('pagehide'))
  await expect(pending).rejects.toThrow('closing')
  expect(messages.at(-1)).toMatchObject({ documentID: native.documentID, action: 'motion.stop' })
  expect(fixture().native.documentID).not.toBe(native.documentID)
})

test('synchronous channel failures reject immediately', async () => {
  const { native, channel } = fixture()
  channel.postMessage = () => { throw new Error('closed channel') }
  await expect(native.request('motion.start', { id: 'rotation-1' })).rejects.toThrow('closed channel')
})
