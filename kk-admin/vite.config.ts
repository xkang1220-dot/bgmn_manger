import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: 'http://192.168.110.101:8999',
        changeOrigin: true,
      },
      '/ws': {
        target: 'http://192.168.110.101:8999',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  plugins: [vue()],
})
