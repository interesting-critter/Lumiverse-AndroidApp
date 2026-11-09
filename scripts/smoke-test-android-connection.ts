#!/usr/bin/env bun
/** Runs only against an explicitly selected emulator; uses a disposable hosted page. */
import assert from 'node:assert/strict'
import { join } from 'node:path'
import { audioSmokeFixture, nativeAudioSmokeBundle } from './native-audio-smoke'

const serial = process.argv[process.argv.indexOf('--serial') + 1]
if (!serial?.startsWith('emulator-')) throw new Error('Select a test emulator with --serial emulator-PORT')
const adb = join(process.env.ANDROID_HOME || join(process.env.HOME!, 'Library/Android/sdk'), 'platform-tools/adb')
const app = 'chat.lumiverse.prototype'
async function command(...args: string[]) {
  const result = Bun.spawnSync([adb, '-s', serial, ...args])
  if (result.exitCode) throw new Error(result.stderr.toString() || result.stdout.toString())
  return result.stdout.toString().trim()
}
async function evaluate<T = unknown>(expression: string): Promise<T> {
  const pid = (await command('shell', 'pidof', app)).split(' ')[0]
  const port = await command('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`)
  try {
    const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json() as Array<{ webSocketDebuggerUrl: string }>
    assert.ok(pages[0], 'WebView must be available')
    return await new Promise<T>((resolve, reject) => {
      const socket = new WebSocket(pages[0]!.webSocketDebuggerUrl)
      const timer = setTimeout(() => { socket.close(); reject(new Error('WebView evaluation timed out')) }, 15000)
      socket.onopen = () => socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }))
      socket.onmessage = event => {
        const reply = JSON.parse(String(event.data))
        if (reply.id !== 1) return
        clearTimeout(timer); socket.close()
        if (reply.error || reply.result?.exceptionDetails) reject(new Error(JSON.stringify(reply)))
        else resolve(reply.result.result.value)
      }
      socket.onerror = () => { clearTimeout(timer); socket.close(); reject(new Error('WebView debugging failed')) }
    })
  } finally { await command('forward', '--remove', `tcp:${port}`) }
}
async function wait(expression: string, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try { if (await evaluate(expression)) return } catch { /* Navigation can replace the execution context. */ }
    await Bun.sleep(250)
  }
  throw new Error(`Timed out: ${expression}`)
}
async function chooser() {
  const ready = "!!document.getElementById('connection-form') && !document.getElementById('connect').disabled"
  // A cold launch briefly shows this form before restoring its saved connection.
  // Only the explicit Change server page stays open for a new selection.
  if (await evaluate("new URLSearchParams(location.search).has('changeServer') && !!document.getElementById('connection-form')")) {
    await wait(ready)
    return
  }
  for (let attempt = 0; attempt < 8; attempt++) {
    await command('shell', 'input', 'keyevent', '4')
    await command('shell', 'uiautomator', 'dump', '/sdcard/lumiverse-controls.xml')
    const xml = await command('shell', 'cat', '/sdcard/lumiverse-controls.xml')
    const bounds = xml.match(/text="Change server"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)
    if (!bounds) continue
    await command('shell', 'input', 'tap', String((+bounds[1]! + +bounds[3]!) / 2), String((+bounds[2]! + +bounds[4]!) / 2))
    await wait(ready)
    return
  }
  throw new Error('Native Change server control did not open')
}
async function connect(url: string) {
  await evaluate(`document.getElementById('server').value=${JSON.stringify(url)}; document.getElementById('connection-form').requestSubmit(); true`)
}
const audioBundle = await nativeAudioSmokeBundle()
const server = Bun.serve({ hostname: '127.0.0.1', port: 0, fetch: request => {
  const path = new URL(request.url).pathname
  if (path === '/audio-probe.js') return new Response(audioBundle, { headers: { 'content-type': 'text/javascript' } })
  if (path === '/message-received.mp3') return new Response(audioSmokeFixture(), { headers: { 'content-type': 'audio/wav' } })
  if (path === '/audio-check') return new Response(null, { status: 204 })
  return new Response(
  '<!doctype html><title>Hosted Lumiverse test</title><h1>Hosted connection works</h1>',
  { headers: { 'content-type': 'text/html', 'set-cookie': 'hosted-check=1; Path=/; SameSite=Lax', 'cache-control': 'no-store' } },
  )
} })
const port = String(server.port)
try {
  await command('reverse', `tcp:${port}`, `tcp:${port}`)
  await command('shell', 'am', 'force-stop', app)
  await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
  await wait('document.readyState === "complete"')
  await chooser()
  assert.equal(await evaluate("window.__TAURI_INTERNALS__.invoke('mobile_connection',{action:'remote',server:'file:///data/data'}).then(()=>false,()=>true)"), true)
  await connect(`http://127.0.0.1:${port}`)
  await wait("document.title === 'Hosted Lumiverse test'")
  assert.equal(await evaluate("document.cookie.includes('hosted-check=1')"), true)
  assert.equal(await evaluate("document.documentElement.getAttribute('data-tauri-mobile')"), 'android')
  assert.equal(await evaluate("window.__TAURI_INTERNALS__.invoke('mobile_connection',{action:'info',server:null}).then(()=>false,()=>true)"), true, 'Hosted content must not receive connection-management IPC')
  await evaluate("const script = document.createElement('script'); script.src='/audio-probe.js'; document.head.append(script); true")
  await wait('!!window.__lumiverseAudioResult')
  assert.equal(await evaluate('window.__lumiverseAudioResult.passed'), true, JSON.stringify(await evaluate('window.__lumiverseAudioResult')))
  await command('shell', 'am', 'force-stop', app)
  await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
  await wait("document.title === 'Hosted Lumiverse test'")
  const processes = await command('shell', 'ps', '-A')
  assert.ok(!processes.includes('libbun.so'), 'Remote mode must not start the bundled backend')
  await chooser()
  await connect('https://example.com')
  await wait("location.protocol === 'https:' && document.title === 'Example Domain'", 45000)
  assert.equal(await evaluate("!!window.lumiverseNativeDownloads"), true)
  await chooser()
  await connect('http://example.com')
  await wait("location.hostname === 'example.com' && document.readyState === 'complete'", 45000)
  // Exercise the origin transition even when the example server serves HTTP
  // directly instead of returning an upgrade redirect.
  await evaluate("location.replace('https://example.com'); true")
  await wait("location.protocol === 'https:' && document.title === 'Example Domain'", 45000)
  assert.equal(await evaluate("!!window.lumiverseNativeDownloads"), true, 'A standard HTTP to HTTPS upgrade must preserve the download bridge')
  await chooser()
  await evaluate("document.getElementById('local').click(); true")
  await wait("location.hostname === '127.0.0.1' && !document.getElementById('connection-form')", 90000)
  console.log(JSON.stringify({ http: 'passed', https: 'passed', httpsUpgradeDownloads: 'passed', cookies: 'passed', savedConnection: 'passed', nativeRecovery: 'passed', remoteIpcDenied: 'passed', noRemoteBackendProcess: 'passed', localHosting: 'passed', audioWithoutSilentPrimer: 'passed' }, null, 2))
} finally {
  server.stop(true)
  await command('reverse', '--remove', `tcp:${port}`)
}
