import assert from 'node:assert/strict'

type Evaluate = <T>(expression: string) => Promise<T>
type Command = (...args: string[]) => Promise<string>

export async function waitForAndroidLocalFrontend(evaluate: Evaluate): Promise<string> {
  // The restore check deliberately finishes the activity and then launches it.
  // am start acknowledges launch before the process/WebView is ready to inspect.
  let origin = ''
  const deadline = Date.now() + 45_000
  while (Date.now() < deadline) {
    try {
      const candidate = await evaluate<string>('location.origin')
      if (/^http:\/\/127\.0\.0\.1:\d+$/.test(candidate)) { origin = candidate; break }
    } catch { /* Activity launch can still be creating the WebView. */ }
    await Bun.sleep(250)
  }
  assert.ok(origin, 'The local frontend must return after the preceding restore check')
  return origin
}

/** The real embedded server must remain reachable without a live DevTools session. */
export async function checkAndroidBackgroundConnection(evaluate: Evaluate, command: Command) {
  const app = 'chat.lumiverse.prototype'
  const origin = await waitForAndroidLocalFrontend(evaluate)
  const address = new URL(origin)
  const mainPid = await command('shell', 'pidof', app)
  const runtimePid = await command('shell', 'pidof', `${app}:runtime`)
  const forwarded = await command('forward', 'tcp:0', `tcp:${address.port}`)
  let checks = 0
  try {
    const services = await command('shell', 'dumpsys', 'activity', 'services', `${app}/.ConnectionSessionService`)
    assert.match(services, /isForeground=true/, 'Device-only connections must protect the frontend process')
    await command('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    await Bun.sleep(3000)
    await command('shell', 'am', 'kill', app)
    assert.equal(await command('shell', 'pidof', app), mainPid)
    assert.equal(await command('shell', 'pidof', `${app}:runtime`), runtimePid)
    await command('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    await Bun.sleep(1500)
    assert.ok(/mWakefulness=(?:Asleep|Dozing)/.test(await command('shell', 'dumpsys', 'power')), 'The screen must be off')
    for (let elapsed = 0; elapsed < 90; elapsed += 5) {
      const response = await fetch(`http://127.0.0.1:${forwarded}/api/v1/runtime-config`, {
        headers: { host: address.host }, signal: AbortSignal.timeout(10_000),
      })
      assert.equal(response.status, 200, 'The embedded backend must remain reachable with its UI backgrounded')
      assert.equal(typeof (await response.json() as { safeThemeMode: boolean }).safeThemeMode, 'boolean')
      checks++
      await Bun.sleep(5000)
    }
    assert.equal(await command('shell', 'pidof', app), mainPid)
    assert.equal(await command('shell', 'pidof', `${app}:runtime`), runtimePid)
    return { screenOffSeconds: 90, successfulRequests: checks, sameFrontendProcess: true, sameRuntimeProcess: true, survivedBackgroundReclaim: true }
  } finally {
    await command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    await command('shell', 'wm', 'dismiss-keyguard')
    await command('shell', 'am', 'start', '-n', `${app}/.MainActivity`)
    await command('forward', '--remove', `tcp:${forwarded}`)
  }
}
