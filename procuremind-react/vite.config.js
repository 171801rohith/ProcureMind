import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      // The silent-renew callback is a second entry point: it is loaded by the hidden
      // renewal iframe, never by the application itself.
      input: {
        main: resolve(import.meta.dirname, 'index.html'),
        silentRenew: resolve(import.meta.dirname, 'silent-renew.html'),
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
    },
  },
});
