import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // API_PROXY lets a second dev server point at another backend (e.g. a test instance).
    proxy: { '/api': process.env.API_PROXY ?? 'http://localhost:8080' },
  },
});
