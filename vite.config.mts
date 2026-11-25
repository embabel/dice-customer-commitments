import { defineConfig } from 'vite'

export default defineConfig({
  root: 'web',
  build: {
    outDir: '../target/generated-web/static',
    emptyOutDir: true,
    assetsDir: 'assets/commitments',
    rollupOptions: {
      input: { recorded: 'web/index.html', live: 'web/live.html' },
    },
  },
})
