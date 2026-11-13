import { afterEach, vi } from "vitest"
import { cleanup } from "@testing-library/react"

afterEach(() => {
  cleanup()
  window.history.replaceState(null, "", "/")
  vi.restoreAllMocks()
})
window.scrollTo = vi.fn()
window.matchMedia = vi.fn().mockImplementation(() => ({
  matches: false,
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
}))
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
