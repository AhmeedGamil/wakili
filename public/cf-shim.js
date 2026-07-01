// Cloudflare-mode shim (loaded by cf.html BEFORE the app boots).
//
// Cloudflare buffers Server-Sent Events, so live updates never reach the phone
// over a Cloudflare tunnel. This replaces window.EventSource with a drop-in that
// is backed by a WebSocket (which Cloudflare does NOT buffer). It mimics the
// EventSource API exactly — onopen / onmessage / onerror / readyState / close()
// and auto-reconnect — so the existing app (api.js, chatController.js) runs
// completely unmodified and never knows the difference.

(function () {
  if (!window.WebSocket) return; // no WebSocket support → keep native EventSource

  function WSEventSource(url) {
    this.url = url;            // the original SSE path, e.g. /api/sessions/ID/stream?t=...
    this.readyState = 0;       // CONNECTING
    this.onmessage = null;
    this.onerror = null;
    this.onopen = null;
    this._closed = false;
    this._open();
  }
  WSEventSource.CONNECTING = 0;
  WSEventSource.OPEN = 1;
  WSEventSource.CLOSED = 2;

  WSEventSource.prototype._open = function () {
    var self = this;
    var proto = location.protocol === "https:" ? "wss" : "ws";
    var ws = new WebSocket(proto + "://" + location.host + "/cf-ws?path=" + encodeURIComponent(this.url));
    this._ws = ws;
    ws.onopen = function () { self.readyState = 1; if (self.onopen) self.onopen({}); };
    ws.onmessage = function (ev) { if (self.onmessage) self.onmessage({ data: ev.data }); };
    ws.onclose = function () {
      self.readyState = 2;
      if (self._closed) return;
      if (self.onerror) self.onerror({});
      setTimeout(function () { if (!self._closed) self._open(); }, 1500); // mimic EventSource auto-reconnect
    };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  };

  WSEventSource.prototype.close = function () {
    this._closed = true;
    this.readyState = 2;
    try { this._ws.close(); } catch (e) {}
  };

  window.EventSource = WSEventSource;
})();
