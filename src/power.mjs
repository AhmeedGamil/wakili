// Cross-platform screen lock + keep-awake. All actions shell out to the OS, so
// no native modules. Nothing here changes persistent system settings — keep-awake
// is a child process we hold open and kill, so it reverts the moment it stops.

import { spawn, execFile } from "node:child_process";

const platform = process.platform; // "win32" | "darwin" | "linux"

// --- Lock the screen -------------------------------------------------------
// Per-OS commands, tried in order until one exits 0 (desktops vary on Linux,
// and macOS lock paths have shifted over releases — so we keep fallbacks).
function lockCommands() {
  if (platform === "win32") return [["rundll32.exe", ["user32.dll,LockWorkStation"]]];
  if (platform === "darwin") return [
    ["/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession", ["-suspend"]],
    ["pmset", ["displaysleepnow"]], // with "require password after sleep" set, this locks
  ];
  return [ // linux
    ["loginctl", ["lock-session"]],
    ["xdg-screensaver", ["lock"]],
    ["gnome-screensaver-command", ["-l"]],
    ["dm-tool", ["lock"]],
  ];
}

export function lockScreen() {
  const cmds = lockCommands();
  return new Promise((resolve) => {
    let i = 0;
    const tryNext = () => {
      if (i >= cmds.length) return resolve({ ok: false, error: "No working lock command found on this system." });
      const [cmd, args] = cmds[i++];
      execFile(cmd, args, { timeout: 5000 }, (err) => (err ? tryNext() : resolve({ ok: true })));
    };
    tryNext();
  });
}

// --- Turn the display off (save power; does NOT lock the session) -----------
// The panel powers down until local input; pair with lockScreen() if you also
// want it secured. Works alongside keep-awake (system stays up, screen dark).
export function screenOff() {
  if (platform === "win32") {
    // Broadcast WM_SYSCOMMAND / SC_MONITORPOWER (off) via SendMessage.
    const script = [
      "$sig = '[DllImport(\"user32.dll\")] public static extern int SendMessage(int hWnd, int hMsg, int wParam, int lParam);';",
      "$t = Add-Type -MemberDefinition $sig -Name Display -Namespace Win32 -PassThru;",
      "$t::SendMessage(0xffff, 0x0112, 0xF170, 2) | Out-Null;", // HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, off
    ].join("\n");
    const enc = Buffer.from(script, "utf16le").toString("base64");
    return new Promise((resolve) => {
      execFile("powershell", ["-NoProfile", "-NonInteractive", "-EncodedCommand", enc], { timeout: 5000, windowsHide: true },
        (err) => resolve(err ? { ok: false, error: "Couldn't turn the display off." } : { ok: true }));
    });
  }
  const cmds = platform === "darwin"
    ? [["pmset", ["displaysleepnow"]]]
    : [["xset", ["dpms", "force", "off"]]]; // linux (X11; no-ops under Wayland)
  return new Promise((resolve) => {
    let i = 0;
    const tryNext = () => {
      if (i >= cmds.length) return resolve({ ok: false, error: "No working display-off command found on this system." });
      const [cmd, args] = cmds[i++];
      execFile(cmd, args, { timeout: 5000 }, (err) => (err ? tryNext() : resolve({ ok: true })));
    };
    tryNext();
  });
}

// --- Keep the system awake (but let the display lock / sleep) ---------------
// A long-lived child holds the "don't sleep" state; killing it reverts. We do
// NOT inhibit the display, so the screen can still lock/turn off.
let keepChild = null;

function spawnKeepAwake() {
  if (platform === "win32") {
    // PowerShell holds ES_CONTINUOUS | ES_SYSTEM_REQUIRED while it lives. Passed
    // as a base64 (UTF-16LE) -EncodedCommand to dodge all quoting issues.
    const script = [
      "$sig = '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint e);';",
      "$t = Add-Type -MemberDefinition $sig -Name Power -Namespace Win32 -PassThru;",
      "$t::SetThreadExecutionState(0x80000001) | Out-Null;", // ES_CONTINUOUS|ES_SYSTEM_REQUIRED
      "while ($true) { Start-Sleep -Seconds 3600 }",
    ].join("\n");
    const enc = Buffer.from(script, "utf16le").toString("base64");
    return spawn("powershell", ["-NoProfile", "-NonInteractive", "-EncodedCommand", enc], { stdio: "ignore", windowsHide: true });
  }
  if (platform === "darwin") return spawn("caffeinate", ["-s"], { stdio: "ignore" }); // system sleep blocked, display free
  return spawn("systemd-inhibit", ["--what=sleep:idle", "--who=remote-agent", "--why=phone gateway", "--mode=block", "sleep", "infinity"], { stdio: "ignore" });
}

export function setKeepAwake(on) {
  if (on && !keepChild) {
    try {
      const c = spawnKeepAwake();
      c.on("error", () => { if (keepChild === c) keepChild = null; });
      c.on("exit", () => { if (keepChild === c) keepChild = null; });
      keepChild = c;
    } catch { keepChild = null; }
  } else if (!on && keepChild) {
    try { keepChild.kill(); } catch { /* already gone */ }
    keepChild = null;
  }
  return !!keepChild;
}

export function powerStatus() { return { platform, keepAwake: !!keepChild }; }
export function shutdownPower() { if (keepChild) { try { keepChild.kill(); } catch { /* gone */ } keepChild = null; } }
