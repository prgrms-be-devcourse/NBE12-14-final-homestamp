import { ApiError } from "./errors"

// HTTP 모드에서 실제 서버로 보내도 되는 API 목록. 06 4장 주소와 요청·응답 모양을 서버와 맞춰 연결을 마친 것만 적어요.
// 목록에 없는 요청은 서버로 보내지 않고 "아직 서버에 연결되지 않은 기능"으로 안내해요.
// 모양이 다른 요청이 서버에 가서 400·404가 나거나 잘못 기록되는 일을 막으려는 것이에요.
// 연결 작업(API 연동 PR)에서 기능을 하나 연결할 때마다 여기에 한 줄씩 더해요. 예: { method: "POST", path: /^\/api\/auth\/login$/ }
export const CONNECTED_APIS: readonly {
  method: "GET" | "POST"
  path: RegExp
}[] = [
  // 법정동 찾기·단계 선택(06 4-3, ROOM-1, #193)
  { method: "GET", path: /^\/api\/regions\/search$/ },
  { method: "GET", path: /^\/api\/regions$/ },
  // 로그인·재발급·로그아웃·내 정보(06 4-1, ACC-2·4)
  { method: "POST", path: /^\/api\/auth\/login$/ },
  { method: "POST", path: /^\/api\/auth\/refresh$/ },
  { method: "POST", path: /^\/api\/auth\/logout$/ },
  { method: "GET", path: /^\/api\/me$/ },
  // 휴대폰 인증 문자·인증번호 확인·가입(06 4-1, ACC-1, #208)
  { method: "POST", path: /^\/api\/auth\/phone-verifications$/ },
  {
    method: "POST",
    path: /^\/api\/auth\/phone-verifications\/[^/]+\/verify$/,
  },
  { method: "POST", path: /^\/api\/auth\/signup$/ },
]

export const NOT_CONNECTED_MESSAGE = "아직 서버에 연결되지 않은 기능이에요."

export function isConnected(method: "GET" | "POST", path: string) {
  const bare = path.split("?")[0]
  return CONNECTED_APIS.some((a) => a.method === method && a.path.test(bare))
}

export const notConnected = () =>
  new ApiError({
    status: 0,
    code: "NOT_CONNECTED",
    detail: NOT_CONNECTED_MESSAGE,
  })
