import { API_MODE } from "@/config"
import type { HomeStampApi } from "./types"
import { createHttpApi } from "./http-api"
import { session } from "./auth"
import { createMockApi } from "./mock/mock-api"
import { createMockStore } from "./mock/store"
import { createDevSeed, createSampleSeed, wantsSample } from "./mock/dev-seed"

// 화면이 쓸 API를 고르는 곳. 목업과 HTTP 중 무엇을 쓸지는 여기서만 알아요(VITE_API_MODE).
// HTTP 모드는 계정 요청과 같은 세션(api/auth의 session)을 써서, 토큰이 만료되면 함께 재발급을 기다려요(#191).
// 임시(개발용): 서버 없이 개발할 때는 확인 계정 두 개가 연결된 호실로 시작해요(?sample이면 예시 기록).
export function createApi(): HomeStampApi {
  return API_MODE === "http"
    ? createHttpApi(session.request)
    : createMockApi(
        createMockStore(wantsSample() ? createSampleSeed : createDevSeed),
      )
}
