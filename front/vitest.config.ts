import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

// vite.config.ts가 모드별 설정(함수)이라 테스트 모드로 불러서 합칩니다.
export default defineConfig(env => mergeConfig(viteConfig({ ...env, mode: 'test' }), {
  test: { environment: 'jsdom', setupFiles: ['./tests/setup.ts'], include: ['tests/**/*.test.{ts,tsx}'] },
}))
