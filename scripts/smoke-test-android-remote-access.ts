#!/usr/bin/env bun
/** Requires a disposable, explicitly selected emulator provisioned by the backend smoke test. */
import assert from 'node:assert/strict'
import { join } from 'node:path'
import { JSDOM } from 'jsdom'

const serial = process.argv[process.argv.indexOf('--serial') + 1]
if (!serial?.startsWith('emulator-')) throw new Error('Select a test emulator with --serial emulator-PORT')
const password = process.env.LUMIVERSE_ANDROID_TEST_PASSWORD
const reportPath = process.argv.includes('--report') ? process.argv[process.argv.indexOf('--report') + 1] : undefined
if (!password) throw new Error('Set LUMIVERSE_ANDROID_TEST_PASSWORD for the emulator\'s prototypecheck account')
const adb = join(process.env.ANDROID_HOME || join(process.env.HOME!, 'Library/Android/sdk'), 'platform-tools/adb')
const app = 'chat.lumiverse.prototype'
const forwards: string[] = []
async function command(...args: string[]) {
  const child = Bun.spawn([adb, '-s', serial!, ...args], { stdout: 'pipe', stderr: 'pipe' })
  const timer = setTimeout(() => child.kill(), 30000)
  const [out, err, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited])
  clearTimeout(timer)
  if (code) throw new Error(`${args[0]}: ${err || out}`)
  return out.trim()
}
async function evaluate<T = unknown>(expression: string, timeoutMs = 15000): Promise<T> {
  const pid = (await command('shell', 'pidof', app)).split(' ')[0]
  const port = await command('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`)
  try {
    const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json() as Array<{ webSocketDebuggerUrl: string }>
    assert.ok(pages[0], 'WebView must be available')
    return await new Promise<T>((resolve, reject) => {
      const socket = new WebSocket(pages[0]!.webSocketDebuggerUrl)
      const timer = setTimeout(() => { socket.close(); reject(new Error('WebView evaluation timed out')) }, timeoutMs)
      socket.onopen = () => socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }))
      socket.onmessage = event => {
        const reply = JSON.parse(String(event.data))
        if (reply.id !== 1) return
        clearTimeout(timer); socket.close()
        if (reply.error || reply.result?.exceptionDetails) reject(new Error(JSON.stringify(reply.error || reply.result.exceptionDetails)))
        else resolve(reply.result.result.value as T)
      }
      socket.onerror = () => { clearTimeout(timer); socket.close(); reject(new Error('WebView debugging failed')) }
    })
  } finally { await command('forward', '--remove', `tcp:${port}`) }
}
async function wait(check: () => Promise<boolean>, label: string, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try { if (await check()) return } catch { /* Navigation can replace the WebView execution context. */ }
    await Bun.sleep(500)
  }
  throw new Error(`Timed out: ${label}`)
}
async function tap(label: string) {
  const path = '/sdcard/lumiverse-remote-controls.xml'
  await command('shell', 'uiautomator', 'dump', path)
  const xml = await command('shell', 'cat', path)
  const bounds = xml.match(new RegExp(`text="${label}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`, 'i'))
  assert.ok(bounds, `Native control must exist: ${label}`)
  await command('shell', 'input', 'tap', String((+bounds[1]! + +bounds[3]!) / 2), String((+bounds[2]! + +bounds[4]!) / 2))
}
async function stopServerFromNotification() {
  await command('shell', 'cmd', 'statusbar', 'expand-notifications')
  try { await tap('Stop server'); return } catch { /* Android can collapse the notification's actions. */ }
  const xml = await command('shell', 'cat', '/sdcard/lumiverse-remote-controls.xml')
  const document = new JSDOM(xml, { contentType: 'text/xml' }).window.document
  const title = [...document.querySelectorAll('node')].find(node => node.getAttribute('text') === 'Lumiverse remote access')
  const row = title?.closest('node[resource-id="com.android.systemui:id/expandableNotificationRow"]')
  const expand = row?.querySelector('node[content-desc="Expand"]')
  const bounds = expand?.getAttribute('bounds')?.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/)
  assert.ok(bounds, 'The remote-server notification must expose its expand control')
  await command('shell', 'input', 'tap', String((+bounds[1]! + +bounds[3]!) / 2), String((+bounds[2]! + +bounds[4]!) / 2))
  await tap('Stop server')
}
async function chooser() {
  await wait(() => evaluate('document.readyState === "complete" && (!document.getElementById("connection-form") || (typeof busy !== "undefined" && !busy && !!document.getElementById("remote-status").textContent))'), 'connection controls ready')
  if (await evaluate("!!document.getElementById('connection-form')")) return
  await control('Change server')
  await wait(() => evaluate("!!document.getElementById('remote-enabled') && !document.getElementById('remote-enabled').disabled"), 'connection page')
}
async function control(label: string) {
  // Back can first dismiss the IME or navigate within the selected server.
  for (let attempt = 0; attempt < 8; attempt++) {
    await command('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    try { await tap(label); return } catch { /* Try again after the keyboard/page closes. */ }
  }
  throw new Error(`Could not open native control: ${label}`)
}
interface State { enabled: boolean; ready: boolean; port: number; urls: string[] }
const state = () => evaluate<State>("window.__TAURI_INTERNALS__.invoke('mobile_connection',{action:'remote-access-info'})")
async function forward(port: string) {
  const local = await command('forward', 'tcp:0', `tcp:${port}`)
  forwards.push(local)
  return local
}
const services = () => command('shell', 'dumpsys', 'activity', 'services', `${app}/.RuntimeService`)
try {
  await command('shell', 'pm', 'grant', app, 'android.permission.POST_NOTIFICATIONS')
  await command('shell', 'am', 'force-stop', app)
  await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
  await wait(() => evaluate('document.readyState === "complete" && (!document.getElementById("connection-form") || (typeof busy !== "undefined" && !busy && !!document.getElementById("remote-status").textContent))'), 'initial connection restored')
  await chooser()
  console.log('Connection page ready')
  // A prior interrupted smoke may have left this disposable server running.
  if ((await state()).enabled) {
    await evaluate("document.getElementById('remote-enabled').click(); true")
    await wait(async () => !(await state()).enabled && await evaluate("!document.getElementById('local').disabled"), 'reset inherited remote access')
  }
  assert.equal((await state()).enabled, false, 'Begin with remote access disabled')
  await evaluate("document.getElementById('remote-port').value='8877'; document.getElementById('remote-enabled').click(); true")
  await wait(async () => { const info = await state(); return info.enabled && info.ready }, 'remote access readiness', 120000)
  const info = await state()
  console.log('Remote access ready')
  assert.equal(info.port, 8877)
  assert.ok(info.urls.length, 'Native network APIs must supply an address')
  assert.match(await services(), /isForeground=true/)
  const origin = info.urls[0]!
  const address = new URL(origin)
  assert.notEqual(address.hostname, '127.0.0.1')
  const local = await forward(address.port)
  const request = (path: string, init: RequestInit = {}) => fetch(`http://127.0.0.1:${local}${path}`, {
    ...init, headers: { host: address.host, origin, ...init.headers }, signal: AbortSignal.timeout(8000),
  })
  assert.equal((await request('/api/v1/characters')).status, 401, 'Remote access must retain authentication')
  const login = await request('/api/auth/sign-in/username', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'prototypecheck', password }),
  })
  assert.equal(login.status, 200, 'Remote login must accept the emulator account credentials')
  const cookie = login.headers.getSetCookie().map(value => value.split(';')[0]).join('; ')
  assert.ok(cookie, 'Login must return a session')
  assert.equal((await request('/api/v1/characters', { headers: { cookie } })).status, 200)
  console.log('Authenticated remote HTTP verified')
  assert.notEqual((await request('/api/v1/runtime-config', { headers: { host: 'untrusted.invalid:8877', origin: 'http://untrusted.invalid:8877' } })).status, 200)

  // Navigate through the phone's actual LAN IP, proving the listener accepts non-loopback traffic.
  // Navigation can destroy the invoking execution context before CDP returns.
  await evaluate(`setTimeout(()=>window.__TAURI_INTERNALS__.invoke('mobile_connection',{action:'remote',server:${JSON.stringify(origin)}}),50); true`)
  await wait(() => evaluate(`location.origin === ${JSON.stringify(origin)} && !!document.getElementById('root')?.firstElementChild`), 'frontend over LAN')
  assert.equal(await evaluate(`fetch('/api/auth/sign-in/username',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:'prototypecheck',password:${JSON.stringify(password)}})}).then(r=>r.status)`), 200)
  const websocketEvent = await evaluate<string>(`new Promise((resolve,reject)=>{
    const socket=new WebSocket('ws://'+location.host+'/api/ws');
    const timer=setTimeout(()=>{socket.close();reject(Error('WebSocket timed out'))},10000);
    socket.onmessage=event=>{clearTimeout(timer);const data=JSON.parse(event.data);socket.close();resolve(data.event)};
    socket.onerror=()=>{clearTimeout(timer);reject(Error('WebSocket failed'))};
  })`)
  assert.equal(websocketEvent, 'CONNECTED')
  console.log('LAN interface and WebSocket verified')
  const storageProbe = crypto.randomUUID()
  const settingPath = '/api/v1/settings/android-background-persistence-probe'
  assert.equal((await request(settingPath, { method: 'PUT', headers: { cookie, 'content-type': 'application/json' }, body: JSON.stringify({ value: storageProbe }) })).status, 200)
  await evaluate(`localStorage.setItem('android-relaunch-persistence', ${JSON.stringify(storageProbe)}); true`)
  // Let Chromium commit before testing a deliberate close/reopen.
  await Bun.sleep(6000)

  // Use the real Close app control and exceed the existing detached idle timeout.
  await control('Close app')
  await command('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
  await wait(async () => !/ConnectionRecord/.test(await services()), 'frontend detach')
  const until = Date.now() + 35000
  while (Date.now() < until) {
    assert.equal((await request('/api/v1/characters', { headers: { cookie } })).status, 200)
    await Bun.sleep(1000)
  }
  assert.match(await services(), /isForeground=true/, 'Remote access must survive frontend close and screen off')
  console.log('Frontend close and screen-off survival verified')
  const sleepingPower = await command('shell', 'dumpsys', 'power')
  assert.match(sleepingPower, /mWakefulness=(?:Asleep|Dozing)/)
  assert.match(sleepingPower, /Lumiverse::RemoteAccess/)

  await command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
  await command('shell', 'wm', 'dismiss-keyguard')
  await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
  await wait(() => evaluate(`location.origin === ${JSON.stringify(origin)} && document.readyState === "complete"`), 'reopened frontend')
  assert.equal(await evaluate("localStorage.getItem('android-relaunch-persistence')"), storageProbe, 'Hosted browser storage must survive Close app/reopen')
  assert.equal((await (await request(settingPath, { headers: { cookie } })).json() as { value: string }).value, storageProbe, 'Hosted backend settings must survive frontend close')
  assert.equal((await request(settingPath, { method: 'DELETE', headers: { cookie } })).status, 200)
  await evaluate("localStorage.removeItem('android-relaunch-persistence'); true")
  await chooser()
  assert.equal((await state()).enabled, true)
  await evaluate("document.getElementById('remote-enabled').click(); true")
  await wait(async () => !(await state()).enabled, 'remote access disabled')
  await wait(() => evaluate("!document.getElementById('local').disabled"), 'device-only backend ready')
  await wait(async () => { try { await request('/'); return false } catch { return true } }, 'remote listening port closed')
  assert.doesNotMatch(await services(), /isForeground=true/)
  assert.doesNotMatch((await command('shell', 'dumpsys', 'power')).split('Wake Locks:')[1]?.split('Suspend Blockers:')[0] || '', /Lumiverse::RemoteAccess/)
  await evaluate("document.getElementById('local').click(); true")
  await wait(() => evaluate("location.hostname === '127.0.0.1' && !!document.getElementById('root')?.firstElementChild"), 'device-only frontend')

  // The native toggle must also work while using the local frontend.
  await control('Remote access')
  await tap('Allow other devices to connect')
  await tap('Apply')
  await wait(() => evaluate("location.hostname === '127.0.0.1' && location.port === '8877' && !!document.getElementById('root')?.firstElementChild"), 'native toggle and local frontend reconnect')
  assert.match(await services(), /isForeground=true/)
  await control('Close app')
  await stopServerFromNotification()
  await wait(async () => !/isForeground=true/.test(await services()), 'notification stop action')
  await wait(async () => { try { await request('/'); return false } catch { return true } }, 'notification stop closes listener')
  const report = { testedAt: new Date().toISOString(), nativeAddresses: true, port: info.port, authenticatedRemoteLogin: true, untrustedOriginRejected: true,
    nonLoopbackFrontend: true, websocket: websocketEvent, frontendClosedAndScreenOff: true, survivedIdleTimeout: true,
    remoteBrowserStoragePersisted: true, remoteBackendSettingsPersisted: true,
    toggleOffClosesPort: true, foregroundAndWakeLockReleased: true, localFrontendRecovered: true,
    nativeToggleReconnectsFrontend: true, notificationStopClosesListener: true }
  if (reportPath) await Bun.write(reportPath, JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify(report, null, 2))
} finally {
  await command('shell', 'cmd', 'statusbar', 'collapse').catch(() => {})
  await command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP').catch(() => {})
  for (const port of forwards) await command('forward', '--remove', `tcp:${port}`).catch(() => {})
}
