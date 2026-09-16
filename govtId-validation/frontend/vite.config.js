import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The console talks to the Spring Boot API through this proxy in development, so
    // the browser only ever sees one origin and no CORS preflight is involved.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // The live capture-quality hint calls the face service on every frame while the
      // officer lines the traveller up. Routing that preview loop through the screening
      // API - which would also run OCR and forensics - would be wasteful, so it goes
      // direct, still through this proxy so the browser sees a single origin.
      '/face-service': {
        target: 'http://localhost:5000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/face-service/, ''),
      },
    },
  },
})
