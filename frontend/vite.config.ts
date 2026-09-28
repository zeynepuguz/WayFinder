import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The browser calls /api on the same origin; Vite forwards it to Spring Boot (no CORS in dev)
    proxy: {
      '/api': 'http://localhost:8080',
      // User photos are files served by the backend (/media/photos/…)
      '/media': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})
