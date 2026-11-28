import { ApiError } from "./errors"

// HTTP 모드에서 실제 서버로 보내도 되는 API 목록. 06 4장 주소와 요청·응답 모양을 서버와 맞춰 연결을 마친 것만 적어요.
// 목록에 없는 요청은 서버로 보내지 않고 "아직 서버에 연결되지 않은 기능"으로 안내해요.
// 모양이 다른 요청이 서버에 가서 400·404가 나거나 잘못 기록되는 일을 막으려는 것이에요.
// 연결 작업(API 연동 PR)에서 기능을 하나 연결할 때마다 여기에 한 줄씩 더해요. 예: { method: "POST", path: /^\/api\/auth\/login$/ }
export const CONNECTED_APIS: readonly {
  method: "GET" | "POST"
  path: RegExp
}[] = [
  // 수리 건 상세와 그 버튼(06 4-6, REP-2~5·8, #179). 하자 등록·사진 추가는 부위(A)·업로드 표(C) 연결 뒤에 더해요
  { method: "GET", path: /^\/api\/repair-cases\/[^/]+$/ },
  // 호실의 수리 건 목록(06 4-6, #290)
  { method: "GET", path: /^\/api\/rooms\/[^/]+\/repair-cases$/ },
  {
    method: "POST",
    path: /^\/api\/repair-cases\/[^/]+\/(respond|supplement|start|request-close|confirm-close|not-yet|close-alone|cause-notes)$/,
  },
  { method: "POST", path: /^\/api\/cause-notes\/[^/]+\/respond$/ },
  // 진행 상황 묻기·남기기·상황 변화 응답(06 4-6, REP-6, #367)
  {
    method: "POST",
    path: /^\/api\/repair-cases\/[^/]+\/(ask-progress|progress-notes)$/,
  },
  { method: "POST", path: /^\/api\/progress-notes\/[^/]+\/respond$/ },
  // 호실 만들기·내 호실 목록·호실 상세(06 4-3, ROOM-1·2, #232)
  { method: "POST", path: /^\/api\/rooms$/ },
  { method: "GET", path: /^\/api\/rooms$/ },
  { method: "GET", path: /^\/api\/rooms\/[^/]+$/ },
  // 거주 종료·거주 종료 요청과 답(06 4-3, ROOM-9, #445). 버튼은 서버 availableActions가 있을 때만 보여요
  {
    method: "POST",
    path: /^\/api\/rooms\/[^/]+\/move-out(\/(confirm|reject))?$/,
  },
  // 부위 목록·부위 더하기(06 4-3, ROOM-3, #356)
  { method: "GET", path: /^\/api\/rooms\/[^/]+\/parts$/ },
  { method: "POST", path: /^\/api\/rooms\/[^/]+\/parts$/ },
  // 부위 숨김·다시 보이기·켜고 끔(06 4-3, ROOM-3a·3b·3d, #456)
  {
    method: "POST",
    path: /^\/api\/rooms\/[^/]+\/parts\/[^/]+\/(hide|show|toggle)$/,
  },
  // 호실 경과(06 4장, REP-11, #359)
  { method: "GET", path: /^\/api\/rooms\/[^/]+\/timeline$/ },
  // 호실 기록 내려받기 목록과 사진 주소 받기(06 4장·4-5, DL-1·S-19, #321)
  { method: "GET", path: /^\/api\/rooms\/[^/]+\/download$/ },
  { method: "POST", path: /^\/api\/photos\/view-urls$/ },
  // 사진 올리기: 업로드 표 받기(06 4-5, PHO-1, #365). S3에는 화면이 presigned POST로 직접 올려요
  { method: "POST", path: /^\/api\/rooms\/[^/]+\/upload-tickets$/ },
  // 입주 점검 시작·상세·확인 요청·항목 응답·남은 항목 모두 확인·보충(06 4-7, INSP-1, #357).
  // 점검 ID는 위 호실 경과에서 찾아요. 항목 더하기·부위 사진은 아직 연결 전이에요
  { method: "POST", path: /^\/api\/rooms\/[^/]+\/inspections$/ },
  { method: "GET", path: /^\/api\/inspections\/[^/]+$/ },
  {
    method: "POST",
    path: /^\/api\/inspections\/[^/]+\/(request-confirm|confirm-all)$/,
  },
  {
    method: "POST",
    path: /^\/api\/inspection-items\/[^/]+\/(respond|supplement)$/,
  },
  // 법정동 찾기·단계 선택(06 4-3, ROOM-1, #193)
  { method: "GET", path: /^\/api\/regions\/search$/ },
  { method: "GET", path: /^\/api\/regions$/ },
  // 로그인·재발급·로그아웃·내 정보(06 4-1, ACC-2·4)
  { method: "POST", path: /^\/api\/auth\/login$/ },
  { method: "POST", path: /^\/api\/auth\/refresh$/ },
  { method: "POST", path: /^\/api\/auth\/logout$/ },
  { method: "GET", path: /^\/api\/me$/ },
  // 바뀐 약관·확인 항목 다시 확인(06 4-1, CONS-4, #338)
  { method: "POST", path: /^\/api\/me\/agreements$/ },
  // 휴대폰 인증 문자·인증번호 확인·가입(06 4-1, ACC-1, #208)
  { method: "POST", path: /^\/api\/auth\/phone-verifications$/ },
  {
    method: "POST",
    path: /^\/api\/auth\/phone-verifications\/[^/]+\/verify$/,
  },
  { method: "POST", path: /^\/api\/auth\/signup$/ },
  // 내 정보 수정·문자 설정·비밀번호 변경·재설정(06 4-1, ACC-4·5·6, #416)
  { method: "POST", path: /^\/api\/me$/ },
  { method: "POST", path: /^\/api\/me\/password$/ },
  { method: "POST", path: /^\/api\/auth\/reset-password$/ },
  // 가입 전 링크 열람(06 4-4 `POST /api/public/links/view`, S-16, #303). 문자 그만 받기(`links/stop`)는 서버에 아직 없어요
  { method: "POST", path: /^\/api\/public\/links\/view$/ },
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
