// Minimal service worker: its presence (with a fetch handler) is what makes the
// app installable. All requests go straight to the network — no caching, so the
// server's own versioning and SSE streams behave exactly as in a plain tab.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
