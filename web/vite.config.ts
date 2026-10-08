import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  // Relative paths so the same build works on GitHub Pages (/Pawprint/) and inside the desktop app.
  base: './',
  plugins: [svelte()],
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
  },
});
