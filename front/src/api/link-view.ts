import { API_BASE_URL } from "@/config"
import { endpoints } from "./endpoints"
import { createHttpClient } from "./http-client"

// 가입 전 링크 열람(S-16) 응답. 06 4-4 `POST /api/public/links/view`와 같은 모양이에요(백엔드 LinkViewResponse, #303).
// 문구(statusLine·statusLines·history.label)는 서버가 정해서 주고, 화면은 다시 계산하지 않아요.
export type LinkSide = "TENANT" | "LANDLORD"

export interface LinkPhoto {
  id: string
  // 지운 사진(erased)은 파일이 없어 주소가 없고 흔적(시각·사유·측)만 있어요
  thumbUrl: string | null
  originalUrl: string | null
  // 사진 주소는 서명한 뒤 5분만 열려요
  expiresAt: string | null
  erased: boolean
  erasedAt: string | null
  // 지운 사유. 다른 사람의 요청(THIRD_PARTY)으로 지웠으면 측(erasedSide)이 없어요(03 불변식 16)
  erasedReason?: LinkEraseReason | null
  erasedSide?: LinkSide | null
}

export type LinkEraseReason = "PRIVACY" | "WITHDRAWAL" | "THIRD_PARTY"

export interface LinkItem {
  partName: string
  description: string | null
  statusLine: string
  // 빈 목록이면 그 항목에 보여 줄 사진이 없는 것이에요(못 읽은 것과 다름, 못 읽으면 서버가 오류로 답해요)
  photos: LinkPhoto[]
}

export interface LinkHistoryItem {
  label: string
  side: LinkSide | null
  at: string
}

export interface LinkRecord {
  targetKind: string
  partName: string | null
  title: string | null
  description: string | null
  statusLines: string[]
  photos: LinkPhoto[]
  history: LinkHistoryItem[]
  // 점검이면 항목마다 묶어 줘요. 수리 건이면 빈 목록이에요
  items: LinkItem[]
}

export interface LinkView {
  kind: "RECORD" | "ROOM_INVITE" | "SETTLEMENT_INVITE"
  sido: string | null
  sigungu: string | null
  dong: string | null
  // 링크를 받은 측(보는 사람). 링크를 보낸 측은 그 반대예요. 정산 초대 링크는 위치·측을 주지 않아요(LINK-3a)
  yourSide: LinkSide | null
  record: LinkRecord | null
  hasMoney: boolean
}

// 로그인 토큰을 붙이지 않아요. 링크 열람은 링크 토큰만으로 하고, 보는 사람이 회원인지와 상관없어요.
// 서버가 같은 링크의 열람을 10분에 한 줄로 묶으므로 멱등 키도 보내지 않아요(06 4-4).
const request = createHttpClient({ baseUrl: API_BASE_URL })

// 같은 토큰으로 이미 보낸 요청이 끝나기 전이면 그 요청을 함께 기다려요(개발 모드의 두 번 그리기, 같은 화면의 겹친 호출).
// 끝나면 지워서, [다시 불러오기]는 새로 보내요. 응답은 오래 남기지 않아요(사진 주소가 5분짜리라서).
const inflight = new Map<string, Promise<LinkView>>()

export function viewLink(token: string): Promise<LinkView> {
  const running = inflight.get(token)
  if (running) return running
  const sent = request<LinkView>("POST", endpoints.linkView, {
    body: { token },
    idempotent: false,
  }).finally(() => inflight.delete(token))
  inflight.set(token, sent)
  return sent
}

// 문자 링크는 `/l#토큰`이에요. 토큰은 주소 끝 조각(#)에 있어 서버·접속 로그로 가지 않아요(04 11장, 06 LINK-2b)
export const LINK_VIEW_PATH = "/l"

export const isLinkViewPath = (pathname = window.location.pathname) =>
  pathname === LINK_VIEW_PATH || pathname === `${LINK_VIEW_PATH}/`

export const readLinkToken = (hash = window.location.hash) =>
  hash.length > 1 ? hash.slice(1) : ""
