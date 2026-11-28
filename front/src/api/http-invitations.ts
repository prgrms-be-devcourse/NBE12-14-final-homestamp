import { other, type Room } from "@/data/demo"
import { regionName } from "@/lib/region"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"
import { toRole, type ServerRegion, type ServerSide } from "./http-rooms"

// 받은 초대·연결·거절(06 4-3 /api/invitations, ROOM-5, S-08)과 초대(ROOM-4)의 서버 모양.

// 기록 공유 동의 문구 판(06 CONS-2, 2장 설정 terms.version.share-consent). 서버에 지금 판을 주는 곳이 아직 없어
// 가입 확인 항목(ACK_TEXT_VERSIONS)처럼 v1로 보내요. 판을 정식으로 관리하는 일은 CONS-4에서 해요
export const SHARE_CONSENT_VERSION = "v1"

// GET /api/invitations 한 줄(InvitationSummary). 연결 전이라 호실 기록 ID·별칭·번호는 주지 않아요
export interface InvitationSummaryBody {
  partyId: string
  // 연결하면 내가 앉는 측
  side: ServerSide
  region: ServerRegion
  // 초대한 사람의 가린 이름(03 6-1). 탈퇴했으면 null
  inviterName: string | null
}

// POST /api/rooms/{id}/invite 본문(06 4-3 ROOM-4). phone을 비우면 번호만(NUMBER_ONLY) 자리는 호실을 만들 때 저장한 번호로
// 초대하고, 넣으면 그 번호로 덮어써요. 비어 있음(EMPTY) 자리는 필수예요. record는 지금 점검(INSPECTION)만 받아요
export interface InviteBody {
  phone?: string
  consentVersion: string
  record?: { kind: "INSPECTION"; id: string }
}
// POST /api/invitations/{partyId}/connect 본문. 같은 집 기록 합류(ROOM-6)는 아직 받지 않아요
export interface ConnectBody {
  consentVersion: string
}

export const listInvitations = (request: HttpRequest) =>
  request<InvitationSummaryBody[]>("GET", endpoints.invitations)

// 받은 초대 한 줄 → 화면의 호실 자리표. 받은 초대 목록·연결 창이 호실 모양을 읽어 그대로 써요.
// - id는 partyId예요. 연결·거절 주소(/api/invitations/{partyId}/…)에 그대로 들어가요. 연결한 뒤 호실 ID는 연결 응답에서 받아요
// - 서버는 내 인증 번호로 온 초대만 줘요(06 4-3). 번호를 주지 않으니 phone은 비워 두고, 화면(App)이 내 인증 번호를 넣어
//   목업과 같은 번호 확인(canReceiveInvitation)을 지나게 해요(withAccountPhone). 진짜 확인은 서버가 연결 때 해요(PHONE_MISMATCH)
// - 목록은 호실 상태를 주지 않아요. 거주 종료된 호실의 초대도 올 수 있지만 연결 기준은 같아(#163 3-1) 거주 중으로 둬요
export function roomFromInvitation(b: InvitationSummaryBody): Room {
  const mine = toRole(b.side)
  return {
    id: b.partyId,
    name: b.inviterName ? `${b.inviterName}님의 호실` : "초대받은 호실",
    address: regionName(b.region),
    since: "",
    status: "거주 중",
    connection: "초대됨",
    received: true,
    creator: other(mine),
    members: {},
    invitation: { partyId: b.partyId, inviterName: b.inviterName },
  }
}

// 받은 초대 자리표에 내 인증 번호를 넣어요(위 roomFromInvitation 참고). 번호가 없으면 서버도 빈 목록을 줘요
export const withAccountPhone = (
  rooms: readonly Room[],
  accountPhone: string,
) => rooms.map((r) => (r.invitation ? { ...r, phone: accountPhone } : r))
