import { rmSync } from 'node:fs';
import { join } from 'node:path';
import { defineConfig, type Plugin } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';

/** public/_local holds test files kept out of git (a game jar among them); they must never ship. */
const dropLocal: Plugin = {
  name: 'pawprint-drop-local',
  apply: 'build',
  writeBundle(options) {
    if (options.dir) rmSync(join(options.dir, '_local'), { recursive: true, force: true });
  },
};

export default defineConfig({
  // Relative paths so the same build works on GitHub Pages (/Pawprint/) and inside the desktop app.
  base: './',
  plugins: [svelte(), dropLocal],
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
  },
});
