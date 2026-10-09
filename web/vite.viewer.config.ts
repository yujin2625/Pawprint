import { defineConfig } from 'vite';

/**
 * The single-file HTML viewer runtime (src/viewer/main.ts): one script with the mesher worker inlined, which the
 * editor embeds into exported .html files. Built into viewer-dist/ before the app (see package.json).
 */
export default defineConfig({
  define: { 'process.env.NODE_ENV': '"production"' },
  build: {
    outDir: 'viewer-dist',
    emptyOutDir: true,
    copyPublicDir: false,
    lib: {
      entry: 'src/viewer/main.ts',
      formats: ['iife'],
      name: 'PawprintViewer',
      fileName: () => 'viewer.js',
    },
  },
});
