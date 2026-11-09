#!/usr/bin/env bun
/** Uses only a selected disposable emulator. No DevTools connection stays open while backgrounded. */
import assert from 'node:assert/strict'
import { join } from 'node:path'
import { tapAndroidControl } from './android-ui-smoke'
import { backendRoot } from './backend-project'

const option = (name: string) => { const index = process.argv.indexOf(name); return index < 0 ? undefined : process.argv[index + 1] }
const serial = option('--serial')
if (!serial?.startsWith('emulator-')) throw new Error('Select a disposable emulator with --serial emulator-PORT')
const baseline = process.argv.includes('--baseline')
const seconds = Number(option('--seconds') || 75)
assert.ok(seconds >= 60 && seconds <= 600, 'Use at least one minute per background phase')
const app = 'chat.lumiverse.prototype'
const adb = join(process.env.ANDROID_HOME || join(process.env.HOME!, 'Library/Android/sdk'), 'platform-tools/adb')
async function command(...args: string[]) {
  const child = Bun.spawn([adb, '-s', serial!, ...args], { stdout: 'pipe', stderr: 'pipe' })
  const timer = setTimeout(() => child.kill(), 30_000)
  const [out, err, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited])
  clearTimeout(timer)
  if (code) throw new Error(err || out)
  return out.trim()
}
async function evaluate<T = any>(expression: string, userGesture = false): Promise<T> {
  const pid = (await command('shell', 'pidof', app)).split(' ')[0]
  const port = await command('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`)
  try {
    const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json() as { webSocketDebuggerUrl: string }[]
    assert.ok(pages[0], 'WebView is available')
    return await new Promise<T>((resolve, reject) => {
      const ws = new WebSocket(pages[0]!.webSocketDebuggerUrl)
      const timer = setTimeout(() => { ws.close(); reject(new Error('WebView evaluation timed out')) }, 15_000)
      ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true, userGesture } }))
      ws.onmessage = event => {
        const reply = JSON.parse(String(event.data))
        if (reply.id !== 1) return
        clearTimeout(timer); ws.close()
        if (reply.error || reply.result.exceptionDetails) reject(new Error(JSON.stringify(reply)))
        else resolve(reply.result.result.value)
      }
      ws.onerror = () => { clearTimeout(timer); ws.close(); reject(new Error('DevTools failed')) }
    })
  } finally { await command('forward', '--remove', `tcp:${port}`) }
}
async function wait(expression: string) {
  for (let i = 0; i < 120; i++) {
    try { if (await evaluate(expression)) return } catch { /* navigation */ }
    await Bun.sleep(250)
  }
  throw new Error(`Timed out: ${expression}`)
}
async function chooser() {
  if (await evaluate("!!document.getElementById('connection-form')")) return
  await command('shell', 'input', 'keyevent', 'KEYCODE_BACK')
  await tapAndroidControl(command, /text="Change server"/)
  await wait("!!document.getElementById('connection-form')")
}
// A real, long audio track makes playback progress measurable across screen-off.
const samples = 8000 * Math.max(600, seconds * 2 + 60)
const wav = Buffer.alloc(44 + samples * 2)
wav.write('RIFF'); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8)
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22)
wav.writeUInt32LE(8000, 24); wav.writeUInt32LE(16000, 28); wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34)
wav.write('data', 36); wav.writeUInt32LE(samples * 2, 40)
for (let i = 0; i < samples; i++) wav.writeInt16LE(Math.round(100 * Math.sin(i * Math.PI / 20)), 44 + i * 2)
const bundle = await Bun.build({
  entrypoints: [join(import.meta.dir, 'fixtures/background-session-client.ts')],
  plugins: [{ name: 'shared-frontend', setup(build) {
    build.onResolve({ filter: /^\.\.\/\.\.\/frontend\// }, args => ({ path: Bun.resolveSync(join(backendRoot(), args.path.slice(6)), backendRoot()) }))
  } }],
  target: 'browser', format: 'iife', tsconfig: join(backendRoot(), 'frontend/tsconfig.json'),
  define: { 'import.meta.env.VITE_API_BASE': '""', 'import.meta.url': 'location.href' },
})
if (!bundle.success) throw new AggregateError(bundle.logs, 'Could not bundle the real WebSocket client')
const clientScript = await bundle.outputs[0]!.text()
const worker = await Bun.build({ entrypoints: [join(backendRoot(), 'frontend/src/ws/heartbeat.worker.ts')], target: 'browser' })
if (!worker.success) throw new AggregateError(worker.logs, 'Could not bundle the heartbeat worker')
const workerScript = await worker.outputs[0]!.text()
let received = 0, pings = 0, connects = 0
const clients = new Set<any>()
const server = Bun.serve({ hostname: '127.0.0.1', port: 0,
  fetch(request, server) {
    const path = new URL(request.url).pathname
    if (path === '/ws' && server.upgrade(request)) return
    if (path === '/session-client.js') return new Response(clientScript, { headers: { 'content-type': 'text/javascript' } })
    if (path === '/heartbeat.worker.ts') return new Response(workerScript, { headers: { 'content-type': 'text/javascript' } })
    if (path === '/audio.wav') return new Response(wav, { headers: { 'content-type': 'audio/wav' } })
    return new Response(`<!doctype html><title>Background session probe</title><h1>Background session probe</h1><input type="file" id="file" accept=".lumiverse,.jsonl,.zip"><audio id="audio" src="/audio.wav" preload="auto"></audio><script>
      localStorage.setItem('background-persist', localStorage.getItem('background-persist') || crypto.randomUUID());
    </script><script src="/session-client.js"></script>`, { headers: { 'content-type': 'text/html', 'cache-control': 'no-store' } })
  },
  websocket: {
    open(ws) { clients.add(ws); connects++ }, close(ws) { clients.delete(ws) },
    message(ws, data) {
      const message = JSON.parse(String(data))
      if (message.type === 'probe_ack') received++
      if (message.type === 'ping') { pings++; ws.send(JSON.stringify({ type: 'pong', id: message.id })) }
      if (message.type === 'stream_focus') (ws as any).focusedChat = message.chatId
    },
  },
})
const tick = setInterval(() => { for (const ws of clients) if (ws.focusedChat) ws.send(JSON.stringify({ event: 'STREAM_TOKEN_RECEIVED', payload: { token: 'token' } })) }, 1000)
const port = String(server.port)
const services = () => command('shell', 'dumpsys', 'activity', 'services', `${app}/.ConnectionSessionService`)
const results: Record<string, any> = { baseline, secondsPerPhase: seconds }
try {
  await command('reverse', `tcp:${port}`, `tcp:${port}`)
  await command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
  await command('shell', 'wm', 'dismiss-keyguard')
  await command('shell', 'am', 'force-stop', app)
  await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
  await wait('document.readyState === "complete"')
  await chooser()
  results.idleInfoMilliseconds = await evaluate<number>("(async()=>{const start=performance.now(); await window.__TAURI_INTERNALS__.invoke('mobile_connection',{action:'info'}); return performance.now()-start})()")
  if (!baseline) assert.ok(results.idleInfoMilliseconds < 1500, 'A stopped runtime query must not wait for the five-second bind timeout')
  await evaluate(`document.getElementById('server').value='http://127.0.0.1:${port}'; document.getElementById('connection-form').requestSubmit(); true`)
  await wait("document.title === 'Background session probe' && window.sessionClient?.connected")
  const beforeReclaim = await command('shell', 'pidof', app)
  await command('shell', 'input', 'keyevent', 'KEYCODE_HOME')
  await Bun.sleep(3000)
  // am kill reclaims only eligible background processes; it is not force-stop.
  await command('shell', 'am', 'kill', app)
  await Bun.sleep(2000)
  results.survivesBackgroundReclaimWithoutAudio = await command('shell', 'pidof', app).catch(() => '') === beforeReclaim
  console.log(JSON.stringify({ survivesBackgroundReclaimWithoutAudio: results.survivesBackgroundReclaimWithoutAudio }))
  if (!baseline) assert.ok(results.survivesBackgroundReclaimWithoutAudio, 'An active connection must not be cached and reclaimable between TTS clips')
  await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
  await wait("document.title === 'Background session probe' && window.sessionClient?.connected")
  await evaluate("document.getElementById('audio').play().then(()=>true)", true)
  const pid = await command('shell', 'pidof', app)
  const foreground = /isForeground=true/.test(await services())
  results.sessionForeground = foreground
  if (!baseline) assert.ok(foreground, 'The UI process needs its own foreground service in hosted mode')
  for (const [phase, key] of [['home', 'KEYCODE_HOME'], ['screenOff', 'KEYCODE_SLEEP']]) {
    const audioBefore = await evaluate<number>("document.getElementById('audio').currentTime")
    const before = received
    const pingsBefore = pings, connectsBefore = connects
    await command('shell', 'input', 'keyevent', key!)
    if (phase === 'screenOff') {
      await Bun.sleep(1500)
      assert.ok(/mWakefulness=(?:Asleep|Dozing)/.test(await command('shell', 'dumpsys', 'power')), 'Screen must be off')
    }
    console.log(`Testing ${phase} for ${seconds}s without DevTools attached`)
    // Small waits keep interruption responsive; no JS evaluation occurs in the app.
    for (let elapsed = 0; elapsed < seconds; elapsed += 5) {
      if (elapsed === 20) for (const ws of clients) ws.close(1012, 'network reconnect probe')
      await Bun.sleep(5000)
    }
    const acknowledgements = received - before
    const backgroundPings = pings - pingsBefore, backgroundReconnects = connects - connectsBefore
    await command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    await command('shell', 'wm', 'dismiss-keyguard')
    await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
    await wait("document.title === 'Background session probe' && !!document.getElementById('audio')")
    const audioAfter = await evaluate<number>("document.getElementById('audio').currentTime")
    results[phase!] = { acknowledgements, pings: backgroundPings, backgroundReconnects,
      playbackSeconds: audioAfter - audioBefore, sameProcess: (await command('shell', 'pidof', app)) === pid }
    console.log(JSON.stringify(results[phase!]))
    if (!baseline) {
      assert.ok(acknowledgements >= seconds * .75, `${phase}: WebSocket must keep consuming generation tokens`)
      assert.ok(backgroundPings > 0, `${phase}: heartbeat must stay active`)
      assert.ok(backgroundReconnects > 0, `${phase}: reconnect must succeed while backgrounded`)
      assert.ok(audioAfter - audioBefore >= seconds * .8, `${phase}: TTS/audio must continue playing`)
      assert.equal(await command('shell', 'pidof', app), pid, 'UI process must survive')
    }
  }
  // Cancel and immediately reopen an extension-only picker repeatedly.
  for (let attempt = 0; attempt < 3; attempt++) {
    await evaluate("document.getElementById('file').click(); true", true)
    await Bun.sleep(500)
    assert.match(await command('shell', 'dumpsys', 'activity', 'activities'), /(?:DocumentsActivity|documentsui|ResolverActivity)/, 'File manager must open')
    await command('shell', 'input', 'keyevent', 'KEYCODE_BACK')
  }
  results.repeatedFilePicker = true
  for (let attempt = 0; ; attempt++) {
    await command('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    try { await tapAndroidControl(command, /text="Close app"/); break }
    catch (error) { if (attempt >= 3) throw error }
  }
  await Bun.sleep(1500)
  results.foregroundReleasedOnClose = !/isForeground=true/.test(await services())
  if (!baseline) {
    assert.ok(results.foregroundReleasedOnClose)
    assert.doesNotMatch((await command('shell', 'dumpsys', 'power')).split('Wake Locks:')[1]?.split('Suspend Blockers:')[0] || '', /Lumiverse::ConnectionSession/)
  }
  console.log(JSON.stringify(results, null, 2))
  if (process.argv.includes('--report')) await Bun.write(option('--report')!, JSON.stringify(results, null, 2) + '\n')
} finally {
  clearInterval(tick); server.stop(true)
  await command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
  await command('reverse', '--remove', `tcp:${port}`)
}
