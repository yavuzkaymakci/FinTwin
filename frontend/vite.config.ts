/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backendUrl = process.env.VITE_BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Proxy backend calls in dev so the browser stays same-origin (no CORS setup needed).
    proxy: {
      '/api': backendUrl,
      '/actuator': backendUrl,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
