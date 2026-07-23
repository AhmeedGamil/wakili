// Device controls (lock the laptop screen; keep it awake while you're away)
// and start-at-login. Thin pass-throughs to the gateway that keep the store's
// power/autostart snapshots fresh; the settings menus render from those.

export function createDeviceController({ api, store }) {
  async function refreshPower() { try { store.set({ power: await api.power() }); } catch { /* ignore */ } }

  // Start-at-login: the gateway registers itself with the OS's per-user
  // autostart mechanism, so this setting lives on the laptop, not the phone.
  async function refreshAutostart() { try { store.set({ autostart: await api.autostart() }); } catch { /* ignore */ } }

  return {
    refreshPower,
    refreshAutostart,
    lockScreen: () => api.lockScreen(),
    screenOff: () => api.screenOff(),
    shutdownComputer: () => api.shutdown(),
    setKeepAwake: async (on) => { try { const p = await api.keepAwake(on); store.set({ power: p }); return p; } catch { return null; } },
    setAutostart: async (on) => { try { const a = await api.setAutostart(on); store.set({ autostart: a }); return a; } catch { return null; } },
  };
}
