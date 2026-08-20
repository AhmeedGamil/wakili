// The guided tour's content. Steps navigate the real UI: the settings panel
// opens, tabs switch, and each device row is spotlit and explained on its own.
// Returns a `guideSteps()` factory so each run reads the live DOM (targets are
// resolved lazily — a row that doesn't exist on this OS is skipped).

export function createGuideSteps({ sidebar, picker, appearanceMenu }) {
  const devRow = (n) => () => appearanceMenu.panel.querySelectorAll(".dev-row")[n];
  return () => [
    {
      before: () => appearanceMenu.close(),
      target: () => sidebar.el.querySelector(".agent-tabs"),
      optional: true, // hidden when only one agent is registered
      title: "Pick your agent",
      body: "Choose who you're talking to. The tabs show that agent's chats, and New chat starts with it. A chat keeps its agent — to move a conversation, use “Continue with…” in its model menu.",
    },
    {
      target: () => sidebar.el.querySelector(".side-new"),
      title: "Start a chat",
      body: "Every chat runs inside a project folder on your computer. Tap New chat to start one in your last folder, or the folder icon to pick (or create) a different one.",
    },
    {
      // The switch lives in the model picker's popover (tap the model name in
      // the topbar) — the drawer closes so the topbar is visible.
      before: () => { document.body.classList.remove("nav-open"); picker.open(); },
      target: () => picker.el.querySelector(".switch-row"),
      title: "Auto continue",
      body: "On by default: when a turn runs into the usage limit, your computer picks the work back up on its own as soon as quota returns — no need to be watching. Turn it off for a chat you'd rather restart yourself.",
    },
    {
      before: () => { picker.close(); appearanceMenu.openTab("connection"); },
      target: () => appearanceMenu.panel.querySelector(".set-content"),
      title: "Connection",
      body: "These are the ways your phone can reach this computer — same Wi-Fi, Tailscale, or Cloudflare. Tap one to switch whenever your network changes.",
    },
    {
      // The row exists only inside the phone app (added via the native bridge),
      // so a browser tour skips this step.
      target: () => appearanceMenu.panel.querySelector(".ep-add"),
      optional: true,
      title: "Add or change the host",
      body: "Opens the hosts page over your session: select another saved computer, scan a QR to add one, or remove one — without losing where you are.",
    },
    {
      before: () => appearanceMenu.openTab("device"),
      target: devRow(0),
      title: "Lock screen",
      body: "Locks your computer's screen remotely, so no one at the desk can see or touch your session.",
    },
    {
      target: devRow(1),
      title: "Turn off screen",
      body: "Switches the computer's display off — the machine keeps running, the screen just goes dark.",
    },
    {
      target: devRow(2),
      title: "Keep awake",
      body: "Stops the computer from going to sleep while you work remotely — turn it on before stepping away.",
    },
    {
      target: devRow(3),
      optional: true, // hidden on OSes the gateway can't register itself on
      title: "Start with computer",
      body: "Launches the gateway automatically when the computer starts, so the app always has something to connect to.",
    },
    {
      target: () => appearanceMenu.panel.querySelector(".dev-danger"),
      title: "Shut down computer",
      body: "Powers the computer off completely. It takes two taps — the first arms the row (\"Tap again to confirm\"), the second shuts down — so a stray touch can't turn your machine off.",
    },
  ];
}
