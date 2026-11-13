import { API_BASE_URL, API_MODE } from "@/config"
import type { HomeStampApi } from "./types"
import { createHttpApi } from "./http-api"
import { createHttpClient } from "./http-client"
import { tokenStore } from "./auth"

export function createApi(mock: () => HomeStampApi): HomeStampApi {
  return API_MODE === "http"
    ? createHttpApi(
        createHttpClient({ baseUrl: API_BASE_URL, getToken: tokenStore.get }),
      )
    : mock()
}
