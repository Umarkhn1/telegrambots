import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Собранное приложение кладётся в ресурсы jar и отдаётся бэкендом по /app/.
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: '../src/main/resources/webapp',
    emptyOutDir: true,
    target: 'es2020',
    chunkSizeWarningLimit: 800,
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
});
