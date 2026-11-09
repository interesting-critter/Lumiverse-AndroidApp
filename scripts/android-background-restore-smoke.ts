import assert from "node:assert/strict";
import { zipSync } from "fflate";
import { tapAndroidControl } from "./android-ui-smoke";

type Evaluate = <T>(expression: string) => Promise<T>;
type Command = (...args: string[]) => Promise<string>;

/** Disposable emulator only: the caller has authenticated the prototypecheck account. */
export async function checkAndroidBackgroundRestore(evaluate: Evaluate, command: Command, password: string) {
  const app = "chat.lumiverse.prototype";
  const address = new URL(await evaluate<string>("location.origin"));
  const port = await command("forward", "tcp:0", `tcp:${address.port}`);
  const endpoint = `http://127.0.0.1:${port}`;
  let cookie = "";
  const headers = () => ({ host: address.host, origin: address.origin, cookie });
  const request = (path: string, init?: RequestInit) => fetch(endpoint + path, { ...init, headers: { ...headers(), ...init?.headers } });
  const wait = async (check: () => Promise<boolean>, description: string) => {
    const deadline = Date.now() + 60_000;
    while (Date.now() < deadline) { if (await check()) return; await Bun.sleep(100); }
    throw new Error(`Timed out: ${description}`);
  };
  const services = () => command("shell", "dumpsys", "activity", "services", `${app}/.RuntimeService`);
  const power = () => command("shell", "dumpsys", "power");
  let jobId = "";
  let state: any;
  try {
    const login = await request("/api/auth/sign-in/username", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: "prototypecheck", password }) });
    assert.equal(login.status, 200);
    cookie = login.headers.getSetCookie().map(value => value.split(";")[0]).join("; ");
    assert.ok(cookie, "Test requests must use an authenticated session");
    const encode = (value: unknown) => Buffer.from(JSON.stringify(value) + "\n");
    const settingsKey = `background_smoke_${Date.now()}`;
    const archive = zipSync({
      "manifest.json": encode({ schemaVersion: 1, producer: "lumiverse", archiveId: crypto.randomUUID(), exportedAt: 0,
        includeVectors: false, hasEncryptedSecrets: true, embeddingConfig: { provider: null, model: null, dimension: null }, counts: {}, missingFiles: [] }),
      "secrets/index.json": encode({ keys: [] }),
      "database/settings.ndjson": encode({ key: settingsKey, value: "true", user_id: "source" }),
      [`files/databank/${settingsKey}.bin`]: new Uint8Array(64 * 1024 * 1024),
    });
    const upload = await request("/api/v1/user-data/import", { method: "POST", headers: { "content-type": "application/zip" }, body: Buffer.from(archive) });
    assert.equal(upload.status, 202);
    jobId = (await upload.json() as { jobId: string }).jobId;
    const status = async () => { state = await (await request(`/api/v1/user-data/import/${jobId}/status`)).json(); return state; };
    await wait(async () => (await status()).status === "awaiting_ticket", "ticket gate");
    assert.match(await services(), /isForeground=true/, "Restore must promote the runtime service");
    assert.doesNotMatch((await power()).split("Wake Locks:")[1]?.split("Suspend Blockers:")[0] || "", /Lumiverse::BackupRestore/, "Waiting for a ticket must release the wake lock");

    // Exercise the app's real Close app action, which calls Activity.finish().
    // Back may first dismiss the keyboard or navigate through login history.
    for (let attempt = 0; ; attempt++) {
      await command("shell", "input", "keyevent", "KEYCODE_BACK");
      try { await tapAndroidControl(command, /text="Close app"/i); break; }
      catch (error) { if (attempt >= 3) throw error; }
    }
    await command("shell", "input", "keyevent", "KEYCODE_SLEEP");
    await wait(async () => !/ConnectionRecord/.test(await services()), "activity unbind");
    await wait(async () => /mWakefulness=(?:Asleep|Dozing)/.test(await power()), "screen off");
    assert.match(await services(), /isForeground=true/, "Foreground work must outlive the activity");
    assert.equal((await status()).status, "awaiting_ticket");
    const skipped = await request(`/api/v1/user-data/import/${jobId}/skip-ticket`, { method: "POST" });
    assert.equal(skipped.status, 200);
    await wait(async () => {
      const job = await status();
      assert.notEqual(job.status, "failed", job.error);
      return job.status === "complete";
    }, "restore completion with screen off");
    assert.equal(state.summary.settings.imported, 1);
    await wait(async () => !/isForeground=true/.test(await services()), "foreground service release");
    const locks = (await power()).split("Wake Locks:")[1]?.split("Suspend Blockers:")[0] || "";
    assert.doesNotMatch(locks, /Lumiverse::BackupRestore/, "Completion must release the wake lock");
    return { activityDestroyed: true, screenOff: true, status: state.status, foregroundReleased: true, wakeLockReleased: true };
  } finally {
    await command("shell", "input", "keyevent", "KEYCODE_WAKEUP");
    await command("shell", "wm", "dismiss-keyguard");
    await command("shell", "am", "start", "-n", `${app}/.MainActivity`);
    await command("forward", "--remove", `tcp:${port}`);
  }
}
