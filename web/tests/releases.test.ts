import { describe, expect, it } from 'vitest';
import { guessSystem, readDownloads, type GithubRelease } from '../src/core/releases';

const asset = (name: string) => ({ name, size: 100, browser_download_url: 'https://example.test/' + name });
const release = (tag: string, names: string[], extra: Partial<GithubRelease> = {}): GithubRelease => ({
  tag_name: tag,
  draft: false,
  prerelease: false,
  html_url: 'https://example.test/' + tag,
  published_at: '2026-10-09T00:00:00Z',
  assets: names.map(asset),
  ...extra,
});

describe('download list', () => {
  const releases = [
    release('mod-v0.3.0', ['pawprint-fabric-1.21.1-0.3.0.jar'], { draft: true }),
    release('app-v0.2.0', ['Pawprint_0.2.0_x64-setup.exe'], { prerelease: true }),
    release('mod-v0.2.0', [
      'pawprint-fabric-1.20.1-0.2.0.jar',
      'pawprint-neoforge-1.21.1-0.2.0.jar',
      'pawprint-forge-1.20.1-0.2.0.jar',
      'pawprint-fabric-1.21.1-0.2.0.jar',
      'pawprint-fabric-1.21.1-0.2.0-sources.jar.sha1',
      'notes.txt',
    ]),
    release('app-v0.1.0', ['Pawprint_0.1.0_x64-setup.exe', 'Pawprint_0.1.0_universal.dmg', 'Pawprint_0.1.0_amd64.AppImage', 'Pawprint_0.1.0_amd64.deb', 'latest.json']),
    release('mod-v0.1.0', ['pawprint-fabric-1.21.1-0.1.0.jar']),
  ];
  const d = readDownloads(releases);

  it('takes the newest published release of each kind', () => {
    expect(d.mod?.version).toBe('0.2.0');
    expect(d.app?.version).toBe('0.1.0');
  });

  it('lists jars newest Minecraft first, NeoForge and Forge before Fabric', () => {
    expect(d.mod?.jars.map((j) => `${j.mcVersion} ${j.loader}`)).toEqual(['1.21.1 neoforge', '1.21.1 fabric', '1.20.1 forge', '1.20.1 fabric']);
  });

  it('sorts installers by system and ignores other files', () => {
    expect(d.app?.installers.map((i) => `${i.system} ${i.kind}`)).toEqual(['windows EXE', 'macos DMG', 'linux AppImage', 'linux deb']);
  });

  it('is empty when nothing is published', () => {
    expect(readDownloads([release('mod-v1.0.0', ['x.jar'], { draft: true })])).toEqual({ mod: null, app: null });
  });

  it('guesses the system from the user agent', () => {
    expect(guessSystem('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('windows');
    expect(guessSystem('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')).toBe('macos');
    expect(guessSystem('Mozilla/5.0 (X11; Linux x86_64)')).toBe('linux');
    expect(guessSystem('Mozilla/5.0 (Linux; Android 14)')).toBeNull();
  });
});
