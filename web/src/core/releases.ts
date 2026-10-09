/**
 * Reads GitHub releases into what the download page offers: the newest mod release (`mod-v*` tags, one jar per
 * Minecraft version and loader) and the newest desktop release (`app-v*` tags, one installer per system).
 */

export interface GithubAsset {
  name: string;
  size: number;
  browser_download_url: string;
}

export interface GithubRelease {
  tag_name: string;
  draft: boolean;
  prerelease: boolean;
  html_url: string;
  published_at: string | null;
  assets: GithubAsset[];
}

export interface Download {
  name: string;
  size: number;
  url: string;
}

export interface ModJar extends Download {
  loader: string;
  mcVersion: string;
}

export type AppSystem = 'windows' | 'macos' | 'linux';

export interface AppInstaller extends Download {
  system: AppSystem;
  /** What kind of file, shown next to the button (AppImage and deb are both for Linux). */
  kind: string;
}

export interface Downloads {
  mod: { version: string; url: string; date: string | null; jars: ModJar[] } | null;
  app: { version: string; url: string; date: string | null; installers: AppInstaller[] } | null;
}

/** Releases come newest first from the API; drafts and pre-releases are skipped. */
function newest(releases: GithubRelease[], prefix: string): GithubRelease | null {
  return releases.find((r) => !r.draft && !r.prerelease && r.tag_name.startsWith(prefix)) ?? null;
}

const JAR = /^pawprint-([a-z]+)-(\d+(?:\.\d+)+)-.+\.jar$/;

/** Newer Minecraft versions first. */
function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number), pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pb[i] ?? 0) - (pa[i] ?? 0);
    if (d) return d;
  }
  return 0;
}

const LOADER_ORDER = ['neoforge', 'forge', 'fabric'];

function installer(asset: GithubAsset): AppInstaller | null {
  const name = asset.name.toLowerCase();
  const base = { name: asset.name, size: asset.size, url: asset.browser_download_url };
  if (name.endsWith('-setup.exe') || name.endsWith('.msi')) return { ...base, system: 'windows', kind: name.endsWith('.msi') ? 'MSI' : 'EXE' };
  if (name.endsWith('.dmg')) return { ...base, system: 'macos', kind: 'DMG' };
  if (name.endsWith('.appimage')) return { ...base, system: 'linux', kind: 'AppImage' };
  if (name.endsWith('.deb')) return { ...base, system: 'linux', kind: 'deb' };
  if (name.endsWith('.rpm')) return { ...base, system: 'linux', kind: 'rpm' };
  return null;
}

export function readDownloads(releases: GithubRelease[]): Downloads {
  const mod = newest(releases, 'mod-v');
  const app = newest(releases, 'app-v');
  const jars = (mod?.assets ?? [])
    .map((a) => {
      const m = JAR.exec(a.name);
      return m ? { name: a.name, size: a.size, url: a.browser_download_url, loader: m[1]!, mcVersion: m[2]! } : null;
    })
    .filter((j): j is ModJar => !!j)
    .sort((a, b) => compareVersions(a.mcVersion, b.mcVersion) || LOADER_ORDER.indexOf(a.loader) - LOADER_ORDER.indexOf(b.loader));
  return {
    mod: mod && jars.length ? { version: mod.tag_name.slice('mod-v'.length), url: mod.html_url, date: mod.published_at, jars } : null,
    app: app
      ? {
          version: app.tag_name.slice('app-v'.length),
          url: app.html_url,
          date: app.published_at,
          installers: app.assets.map(installer).filter((i): i is AppInstaller => !!i),
        }
      : null,
  };
}

/** The visitor's system from a user agent string, for putting their installer first. */
export function guessSystem(userAgent: string): AppSystem | null {
  if (/Windows/i.test(userAgent)) return 'windows';
  if (/Mac OS X|Macintosh/i.test(userAgent) && !/iPhone|iPad/i.test(userAgent)) return 'macos';
  if (/Linux|X11/i.test(userAgent) && !/Android/i.test(userAgent)) return 'linux';
  return null;
}
