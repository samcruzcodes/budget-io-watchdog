/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// `npm run dev` serves the dashboard on 5174 (Budget.io's own dev server uses 5173) and forwards /api to the watchdog
// on 8082, so the browser sees one origin, the same as behind nginx in Compose.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8082',
    },
  },
  test: {
    environment: 'node', // the tested code is plain TypeScript; the UI itself is covered by the Selenium tests
  },
});
