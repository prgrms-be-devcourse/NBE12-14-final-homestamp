import { createContext } from "react"
import type { HomeStampApi, Session } from "./types"

// 화면이 쓰는 API(Mock 또는 HTTP). ApiProvider가 값을 넣어요.
export const ApiContext = createContext<HomeStampApi | null>(null)
// 로그인한 계정(없으면 null). 여러 화면에서 쓰는 칸(부위 고르기 등)이 API를 부를 때 써요
export const SessionContext = createContext<Session | null>(null)
