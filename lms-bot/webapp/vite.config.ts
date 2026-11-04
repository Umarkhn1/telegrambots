import { readFileSync } from 'node:fs';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

const dataUri = (file: string) =>
  `data:image/webp;base64,${readFileSync(new URL(file, import.meta.url)).toString('base64')}`;

/** Эмблемы прямо в index.html: загрузочный экран рисует их с первым кадром, без отдельных запросов. */
function inlineEmblems(): Plugin {
  return {
    name: 'inline-emblems',
    transformIndexHtml: {
      order: 'pre',
      handler: (html) =>
        html
          .replace('__EMBLEM_LIGHT__', dataUri('./src/assets/tuit-emblem-light.webp'))
          .replace('__EMBLEM_DARK__', dataUri('./src/assets/tuit-emblem.webp')),
    },
  };
}

// Собранное приложение кладётся в ресурсы jar и отдаётся бэкендом по /app/.
export default defineConfig({
  plugins: [react(), inlineEmblems()],
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
