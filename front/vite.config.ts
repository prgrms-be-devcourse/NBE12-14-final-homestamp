import path from "node:path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  // 로컬 개발: 화면이 /api를 부르면 백엔드(bootRun, 8080)로 넘긴다. 운영은 nginx가 같은 일을 한다(04 3-2)
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
})
