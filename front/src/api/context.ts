import { createContext } from "react"
import type { HomeStampApi } from "./types"

// 화면이 쓰는 API(Mock 또는 HTTP). ApiProvider가 값을 넣어요.
export const ApiContext = createContext<HomeStampApi | null>(null)
