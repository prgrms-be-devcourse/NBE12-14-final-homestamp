import path from "node:path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"
import { viteSingleFile } from "vite-plugin-singlefile"

// `vite build --mode single`: JS·CSS·폰트·이미지를 HTML 하나에 넣은 시연본(dist-single/index.html)을 만듭니다.
// 일반 `vite build`와 개발 서버 동작은 그대로입니다.
export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss(), ...(mode === "single" ? [viteSingleFile()] : [])],
  base: mode === "single" ? "./" : "/",
  build: mode === "single" ? { outDir: "dist-single" } : undefined,
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
}))
