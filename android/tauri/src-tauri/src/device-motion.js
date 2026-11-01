// The native channel exists only on the selected server's origin.
(() => {
  if (window !== window.top || !window.lumiverseDeviceMotion || window.__LUMIVERSE_ANDROID_MOTION__) return;
  const channel = window.lumiverseDeviceMotion;
  const documentID = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const pending = new Map();
  let sequence = 0;
  channel.onmessage = event => {
    try {
      const reply = JSON.parse(event.data);
      const request = pending.get(reply.id);
      if (!request) return;
      pending.delete(reply.id);
      clearTimeout(request.timer);
      if (reply.error) request.reject(new Error(reply.error));
      else request.resolve(reply.result);
    } catch { /* Ignore malformed replies. */ }
  };
  const post = (id, action, payload = {}) => channel.postMessage(JSON.stringify({ id, documentID, action, payload }));
  Object.defineProperty(window, '__LUMIVERSE_ANDROID_MOTION__', { value: Object.freeze({
    version: 1,
    documentID,
    request(action, payload = {}) {
      return new Promise((resolve, reject) => {
        const id = String(++sequence);
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error('Native device motion request timed out.'));
        }, 5000);
        pending.set(id, { resolve, reject, timer });
        try { post(id, action, payload); }
        catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
      });
    },
  }) });
  // A new document invalidates the previous page's stream, including same-origin reloads.
  post('document', 'motion.document');
  window.addEventListener('pagehide', () => {
    post('pagehide', 'motion.stop');
    for (const request of pending.values()) {
      clearTimeout(request.timer);
      request.reject(new Error('The motion page is closing.'));
    }
    pending.clear();
  });
})();
