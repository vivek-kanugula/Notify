import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The backend the dev server proxies to. NOTIFY_E2E_BACKEND_URL is set only by scripts/e2e-delivery.ps1 to point the
// browser journeys at its isolated backend; the default is the normal local backend.
const backend = process.env.NOTIFY_E2E_BACKEND_URL ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: backend,
        changeOrigin: true
      },
      '/auth': {
        target: backend,
        changeOrigin: true,
        // The OAuth SPA callback route . It lives under /auth/callback but is served
        // by the frontend React Router, not the backend. Bypass the proxy so index.html is served.
        bypass: (req) => {
          if (req.url?.startsWith('/auth/callback')) {
            return '/index.html';
          }
        }
      },
      // The in-app SSE stream . It lives outside /api/v1 because an EventSource can send no headers, so it
      // needs its own proxy entry: without this the browser's EventSource hit the dev server and got a 404, and no
      // browser journey ever exercised the real stream.
      '/sse': {
        target: backend,
        changeOrigin: true
      },
      // Public demo stream and receiver . Bypass /demo SPA page to index.html while proxying /demo/stream.
      '/demo': {
        target: backend,
        changeOrigin: true,
        bypass: (req) => {
          if (!req.url?.startsWith('/demo/stream') && !req.url?.startsWith('/demo/webhook-receiver')) {
            return '/index.html';
          }
        }
      },
      '/actuator': {
        target: backend,
        changeOrigin: true
      }
    }
  }
})
