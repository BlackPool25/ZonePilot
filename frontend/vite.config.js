import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// VITE_API_BASE_URL is injected at build time by Vercel (or locally via .env).
// In dev, leave it unset — the proxy below forwards /api → localhost:8080.
// In production, set VITE_API_BASE_URL=https://your-backend.onrender.com in Vercel env vars.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
