// Where the jscpd executable comes from: the `jscpd.path` setting, the PATH,
// or a release build downloaded into the extension's global storage.
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as zlib from 'zlib';
import * as crypto from 'crypto';
import { execFile } from 'child_process';

export interface Binary {
  command: string;
  version: string;
  source: 'setting' | 'path' | 'downloaded';
}

export type DownloadPolicy = 'ask' | 'always' | 'never';

const REPO = 'kucherenko/jscpd';
const EXE = process.platform === 'win32' ? 'jscpd.exe' : 'jscpd';

/**
 * How to spawn a command. A `.cmd` or `.bat` shim, which npm leaves on the
 * PATH on Windows, only runs through the shell, so it is quoted and marked.
 */
export function spawnSpec(command: string): { command: string; shell: boolean } {
  const shell = process.platform === 'win32' && /\.(cmd|bat)$/i.test(command);
  return { command: shell ? `"${command}"` : command, shell };
}

/** An argument the way the shell takes it, when a shell is in the way. */
export function shellArg(arg: string, shell: boolean): string {
  return shell && /[\s"&|<>^()]/.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg;
}

/** `jscpd --version` prints `jscpd X.Y.Z`; anything else is not a jscpd we can use. */
export function version(command: string): Promise<string | undefined> {
  return new Promise((resolve) => {
    const spec = spawnSpec(command);
    try {
      execFile(spec.command, ['--version'], { timeout: 15000, windowsHide: true, shell: spec.shell }, (error, stdout) => {
        if (error) {
          resolve(undefined);
          return;
        }
        const m = /(\d+\.\d+\.\d+\S*)/.exec(String(stdout));
        resolve(m ? m[1] : undefined);
      });
    } catch {
      // spawn can throw at once, for example EINVAL for a shim it cannot run
      resolve(undefined);
    }
  });
}

function fromPath(): string[] {
  const dirs = (process.env.PATH ?? '').split(path.delimiter).filter(Boolean);
  const names = process.platform === 'win32' ? ['jscpd.exe', 'jscpd.cmd', 'jscpd'] : ['jscpd'];
  const found: string[] = [];
  for (const dir of dirs) {
    for (const name of names) {
      const candidate = path.join(dir, name);
      try {
        if (fs.statSync(candidate).isFile()) {
          found.push(candidate);
        }
      } catch {
        // not there
      }
    }
  }
  return found;
}

function binDir(ctx: vscode.ExtensionContext): string {
  return path.join(ctx.globalStorageUri.fsPath, 'bin');
}

/** The newest downloaded release, by version. */
function downloaded(ctx: vscode.ExtensionContext, wanted: string): string | undefined {
  const dir = binDir(ctx);
  let tags: string[];
  try {
    tags = fs.readdirSync(dir).filter((t) => fs.existsSync(path.join(dir, t, EXE)));
  } catch {
    return undefined;
  }
  if (wanted !== 'latest') {
    const tag = tags.find((t) => t === wanted || `v${t}` === wanted || t === `v${wanted}`);
    return tag ? path.join(dir, tag, EXE) : undefined;
  }
  tags.sort(compareVersions).reverse();
  return tags[0] ? path.join(dir, tags[0], EXE) : undefined;
}

function compareVersions(a: string, b: string): number {
  const pa = a.replace(/^v/, '').split('.').map(Number);
  const pb = b.replace(/^v/, '').split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) {
      return d;
    }
  }
  return 0;
}

export function platformId(): string | undefined {
  const arch = process.arch === 'arm64' ? 'arm64' : process.arch === 'x64' ? 'x64' : undefined;
  if (!arch) {
    return undefined;
  }
  switch (process.platform) {
    case 'darwin':
      return `darwin-${arch}`;
    case 'win32':
      return `windows-${arch}-msvc`;
    case 'linux':
      return `linux-${arch}-${isMusl() ? 'musl' : 'gnu'}`;
    default:
      return undefined;
  }
}

function isMusl(): boolean {
  try {
    const report = (process as unknown as { report?: { getReport(): { header?: { glibcVersionRuntime?: string } } } }).report;
    return !report?.getReport()?.header?.glibcVersionRuntime;
  } catch {
    return false;
  }
}

/**
 * Finds a jscpd to run. The setting wins, then the PATH, then a downloaded
 * release. When nothing is there, the download policy decides.
 */
export async function findBinary(
  ctx: vscode.ExtensionContext,
  log: vscode.OutputChannel,
  settingPath: string,
  policy: DownloadPolicy,
  wanted: string,
): Promise<Binary | undefined> {
  if (settingPath) {
    const v = await version(settingPath);
    if (v) {
      return { command: settingPath, version: v, source: 'setting' };
    }
    log.appendLine(`jscpd.path is set to ${settingPath}, but it does not run as jscpd; looking elsewhere`);
  }
  for (const candidate of fromPath()) {
    const v = await version(candidate);
    if (v) {
      return { command: candidate, version: v, source: 'path' };
    }
  }
  const local = downloaded(ctx, wanted);
  if (local) {
    const v = await version(local);
    if (v) {
      return { command: local, version: v, source: 'downloaded' };
    }
  }
  if (policy === 'never') {
    return undefined;
  }
  if (policy === 'ask') {
    const platform = platformId();
    if (!platform) {
      return undefined;
    }
    const choice = await vscode.window.showInformationMessage(
      `jscpd is not installed. Download the release build for ${platform} (about 8 MB) into the extension's storage?`,
      'Download',
      'Use an installed jscpd',
      'Not now',
    );
    if (choice === 'Use an installed jscpd') {
      await vscode.commands.executeCommand('workbench.action.openSettings', 'jscpd.path');
      return undefined;
    }
    if (choice !== 'Download') {
      return undefined;
    }
  }
  return download(ctx, log, wanted);
}

/** Downloads a release build, checks it against checksums.txt and unpacks it. */
export async function download(
  ctx: vscode.ExtensionContext,
  log: vscode.OutputChannel,
  wanted: string,
): Promise<Binary | undefined> {
  const platform = platformId();
  if (!platform) {
    vscode.window.showErrorMessage(`jscpd has no release build for ${process.platform}-${process.arch}. Install it another way and set jscpd.path.`);
    return undefined;
  }
  const asset = `jscpd-${platform}.tar.gz`;
  const base =
    wanted === 'latest'
      ? `https://github.com/${REPO}/releases/latest/download`
      : `https://github.com/${REPO}/releases/download/${wanted.startsWith('v') ? wanted : `v${wanted}`}`;
  try {
    const archive = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: `Downloading jscpd (${asset})`, cancellable: false },
      async () => {
        const [tar, sums] = await Promise.all([fetchBytes(`${base}/${asset}`), fetchBytes(`${base}/checksums.txt`)]);
        const expected = new RegExp(`^([0-9a-f]{64})\\s+${asset.replace(/\./g, '\\.')}$`, 'm').exec(sums.toString('utf8'))?.[1];
        const actual = crypto.createHash('sha256').update(tar).digest('hex');
        if (!expected) {
          throw new Error(`checksums.txt has no entry for ${asset}`);
        }
        if (expected !== actual) {
          throw new Error(`checksum mismatch for ${asset}: expected ${expected}, got ${actual}`);
        }
        return tar;
      },
    );
    const files = untar(zlib.gunzipSync(archive));
    const exe = files.get(EXE) ?? files.get('jscpd');
    if (!exe) {
      throw new Error(`${asset} holds no ${EXE}`);
    }
    const tmp = path.join(binDir(ctx), `tmp-${process.pid}`);
    fs.mkdirSync(tmp, { recursive: true });
    const tmpExe = path.join(tmp, EXE);
    fs.writeFileSync(tmpExe, exe, { mode: 0o755 });
    const v = await version(tmpExe);
    if (!v) {
      throw new Error('the downloaded binary does not run');
    }
    const dir = path.join(binDir(ctx), `v${v}`);
    fs.rmSync(dir, { recursive: true, force: true });
    fs.renameSync(tmp, dir);
    const command = path.join(dir, EXE);
    log.appendLine(`downloaded jscpd ${v} to ${command}`);
    return { command, version: v, source: 'downloaded' };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    log.appendLine(`download failed: ${message}`);
    vscode.window.showErrorMessage(`Could not download jscpd: ${message}`);
    return undefined;
  }
}

async function fetchBytes(url: string): Promise<Buffer> {
  const response = await fetch(url, { headers: { 'User-Agent': `jscpd-vscode (${os.platform()})` }, redirect: 'follow' });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText} for ${url}`);
  }
  return Buffer.from(await response.arrayBuffer());
}

/** The regular files of a tar archive, by name without directories. */
export function untar(data: Buffer): Map<string, Buffer> {
  const files = new Map<string, Buffer>();
  let offset = 0;
  while (offset + 512 <= data.length) {
    const header = data.subarray(offset, offset + 512);
    if (header.every((b) => b === 0)) {
      break;
    }
    const name = header.subarray(0, 100).toString('utf8').replace(/\0.*$/s, '');
    const size = parseInt(header.subarray(124, 136).toString('utf8').replace(/\0.*$/s, '').trim() || '0', 8);
    const type = String.fromCharCode(header[156]);
    const prefix = header.subarray(345, 500).toString('utf8').replace(/\0.*$/s, '');
    const full = prefix ? `${prefix}/${name}` : name;
    offset += 512;
    if (type === '0' || type === '\0') {
      files.set(path.posix.basename(full), data.subarray(offset, offset + size));
    }
    offset += Math.ceil(size / 512) * 512;
  }
  return files;
}
