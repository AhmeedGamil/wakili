// Filesystem browsing for the phone's project-folder picker. Read-only,
// directories only. Returns { path, parent, dirs:[{name,path}] }. An empty path
// yields the starting points (drives on Windows / "/" on POSIX, plus home), so
// the phone can drill down to any real project without a native folder dialog.

import fsp from "node:fs/promises";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { config } from "./config.mjs";

export async function listFolders(input) {
  const q = (input || "").trim();
  if (!q) return roots();

  let abs;
  try { abs = path.resolve(q); } catch { return roots(); }

  let entries;
  try { entries = await fsp.readdir(abs, { withFileTypes: true }); }
  catch { return { path: abs, parent: parentOf(abs), dirs: [], error: "cannot open" }; }

  const dirs = entries
    .filter((e) => { try { return e.isDirectory(); } catch { return false; } })
    .map((e) => ({ name: e.name, path: path.join(abs, e.name) }))
    .filter((d) => !d.name.startsWith("$")) // hide $Recycle.bin and friends
    .sort((a, b) => a.name.localeCompare(b.name));

  return { path: abs, parent: parentOf(abs), dirs };
}

export async function isDir(p) {
  try { return (await fsp.stat(p)).isDirectory(); } catch { return false; }
}

// Create a sub-folder `name` inside an existing `parent`. Name is sanitised to a
// single path segment (no separators / traversal). Returns the new folder's path.
export async function createFolder(parent, name) {
  const par = String(parent || "").trim();
  if (!par || !(await isDir(par))) throw new Error("invalid parent folder");
  const safe = String(name || "").trim().replace(/[\\/:*?"<>|]/g, "").replace(/^\.+/, "").trim();
  if (!safe) throw new Error("invalid name");
  const dest = path.join(par, safe);
  if (path.dirname(dest) !== path.resolve(par)) throw new Error("invalid name"); // no traversal
  await fsp.mkdir(dest, { recursive: true });
  return dest;
}

// At a filesystem/drive root, dirname(abs) === abs; return "" so the UI's "up"
// navigates back to the roots list rather than getting stuck.
function parentOf(abs) {
  const up = path.dirname(abs);
  return up === abs ? "" : up;
}

function roots() {
  const dirs = [{ name: "~ Home", path: os.homedir() }];
  if (config.isWin) {
    for (let c = 65; c <= 90; c++) {
      const drive = String.fromCharCode(c) + ":\\";
      if (fs.existsSync(drive)) dirs.push({ name: drive, path: drive });
    }
  } else {
    dirs.push({ name: "/", path: "/" });
  }
  return { path: "", parent: null, dirs };
}
