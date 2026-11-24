import { defineConfig, mergeConfig } from "vitest/config"
import viteConfig from "./vite.config.ts"

// vite.config.ts(별칭·플러그인)에 테스트 설정만 더해요.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: "jsdom",
      setupFiles: ["./tests/setup.ts"],
      include: ["tests/**/*.test.{ts,tsx}"],
    },
  }),
)
