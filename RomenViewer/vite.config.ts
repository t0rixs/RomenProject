import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
//
// /api への fetch は同梱の受信サーバ (server.mjs, port 5174) へ転送する。
// THINKLET 側は localhost:5174 へ直接 POST する（adb reverse 経由）。
const RECV_SERVER = process.env.RECV_SERVER || 'http://localhost:5174'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: RECV_SERVER,
        changeOrigin: true,
      },
    },
  },
})
