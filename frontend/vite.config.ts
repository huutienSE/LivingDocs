/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies /api to the API Gateway so the browser stays same-origin
// (refresh cookie + CORS). The Gateway is the only backend entry point.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const gateway = env.VITE_GATEWAY_URL || 'http://127.0.0.1:8081';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': { target: gateway, changeOrigin: false },
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
