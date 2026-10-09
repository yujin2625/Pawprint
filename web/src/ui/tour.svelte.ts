/** The welcome tour: shown once on the first visit, again from Settings. */

const SEEN = 'pawprint.introSeen';

function seen(): boolean {
  try {
    return localStorage.getItem(SEEN) === '1';
  } catch {
    return true;
  }
}

export const intro = $state({ open: !seen() });

export function closeIntro(): void {
  intro.open = false;
  try {
    localStorage.setItem(SEEN, '1');
  } catch {
    // Shown again next time; harmless.
  }
}

export function showIntro(): void {
  intro.open = true;
}
