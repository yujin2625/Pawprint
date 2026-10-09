/**
 * Talks to the Pawprint mod in a running game on this computer (its web link listens on 127.0.0.1, ports
 * 25599–25603, and answers only the Pawprint editor). See WebLink.java in the mod.
 */

export const GAME_PORTS = [25599, 25600, 25601, 25602, 25603];

export interface GameStatus {
  port: number;
  version: string;
  mcVersion: string;
  loader: string;
}

function base(port: number): string {
  return `http://127.0.0.1:${port}`;
}

async function fetchWithTimeout(url: string, init: RequestInit, ms: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

/** Running games with the mod, first port first; empty when none answers. */
export async function findGames(): Promise<GameStatus[]> {
  const found = await Promise.all(
    GAME_PORTS.map(async (port) => {
      try {
        const res = await fetchWithTimeout(base(port) + '/status', {}, 1500);
        if (!res.ok) return null;
        const data = (await res.json()) as Partial<GameStatus> & { app?: string };
        return data.app === 'pawprint' ? { port, version: data.version ?? '', mcVersion: data.mcVersion ?? '', loader: data.loader ?? '' } : null;
      } catch {
        return null;
      }
    }),
  );
  return found.filter((g): g is GameStatus => !!g);
}

/** Sends a `.pawprint` to the game's library; returns the name it was saved under. */
export async function sendToGame(port: number, name: string, bytes: Uint8Array): Promise<string> {
  const res = await fetchWithTimeout(
    base(port) + '/blueprint',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream', 'X-Pawprint-Name': encodeURIComponent(name) },
      body: bytes as BodyInit,
    },
    30_000,
  );
  const data = (await res.json().catch(() => ({}))) as { ok?: boolean; name?: string; error?: string };
  if (!res.ok || !data.ok) throw new Error(data.error ?? `HTTP ${res.status}`);
  return data.name ?? name;
}

/** A blueprint the game offered with "Open in web editor". */
export async function receiveFromGame(port: number, token: string): Promise<Uint8Array> {
  if (!GAME_PORTS.includes(port) || !/^[0-9a-f]{32}$/.test(token)) throw new Error('bad link');
  const res = await fetchWithTimeout(`${base(port)}/blueprint/${token}`, {}, 30_000);
  if (!res.ok) throw new Error(res.status === 404 ? 'expired' : `HTTP ${res.status}`);
  return new Uint8Array(await res.arrayBuffer());
}
