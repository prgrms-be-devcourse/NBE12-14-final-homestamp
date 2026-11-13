import { formatWon } from "@/lib/money"
export type Role = "임차인" | "임대인"
// 하자 인정(GLOSSARY): 확인 기다림 / 맞아요 / 보충 요청됨(보류) / 이견 / 확인 없음
export type Ack = "상대 미확인" | "확인 대기" | "확인됨" | "보충 요청" | "이견"
export type Money = "미정" | "합의 대기" | "합의됨" | "이견" | "비용 없음"
export type RepairStatus = "확인 전" | "확인 완료" | "처리 중" | "처리 완료"
export interface RecordTarget {
  kind: "repair" | "inspection" | "part-photo"
  id: string
}
export interface Event {
  target?: RecordTarget
  id: string
  roomId: string
  text: string
  by: string
  at: string
  privateTo?: Role
}
export interface AmountOffer {
  id: string
  by: Role
  amount: number
  reason: string
  round: number
  status: Money
  at: string
  disputeReason?: string
  disputeBy?: Role
}
export interface Transfer {
  id: string
  by: Role
  amount: number
  direction: "반환" | "추가 송금"
  at: string
  confirmed: boolean
}
export interface CauseClaim {
  id: string
  by: Role
  basis: string
  text: string
  at: string
  responses: { by: Role; agree: boolean; reason: string; at: string }[]
  // 근거 종류가 '업체·전문가 소견'이면 업체명, '다른 사람에게 들었어요'면 누구에게서인지(선택)
  company?: string
  source?: string
  photos?: string[]
  // 이전 방식(문장만 바로 고침)으로 남은 기록 표시용. 새로 고치면 언제나 새 설명을 만들고 이전 설명에 supersededBy를 남깁니다(decisions 2026-11-13).
  edits?: { text: string; at: string }[]
  supersededBy?: string
  supersedes?: string
}
export interface AfterRecord {
  id: string
  title: string
  by: Role
  at: string
  photos: string[]
  description: string
}
// 부담 안 = (누가, 얼마) 줄 목록. 합 = 전체 금액. 제3자는 짧은 설명만 받습니다(상태와 규칙 E).
export interface BurdenLine {
  side: Role | "제3자"
  label?: string
  amount: number
}
export interface Proposal {
  lines?: BurdenLine[]
  at?: string
  withdrawn?: boolean
  totalChanged?: boolean
  closedReason?: string
  // 제안 철회 시 되돌릴 직전 상태(없으면 미정)와 그때의 이력 길이
  restore?: Proposal | null
  restoreHistoryLength?: number
  by: Role
  amount: number
  payer: Role | "나눠서"
  status: Money
  round?: number
  previous?: { amount: number; payer: Role | "나눠서" }
  tenantAmount?: number
  reason?: string
  disputeReason?: string
  disputeBy?: Role
}
// 지불 금액 응답의 보류·이견(MONEY-2). kind가 없으면 이전 방식의 금액 이견이에요.
// closed: 기록한 쪽이 보충하고 다시 확인을 요청해 이 응답이 지난 것이 됨(이력으로 남아요)
export interface PaymentDispute {
  by: Role
  reason: string
  at: string
  kind?: "보류" | "이견"
  closed?: boolean
}
// 사실 정정 요청(상태와 규칙 G). 원래 값(from)은 지우지 않고 나란히 남깁니다.
export interface FactCorrection<T = string | number> {
  id: string
  by: Role
  field: string
  from: T
  to: T
  reason: string
  at: string
  // 고치기(상태와 규칙 G): 도장 전이면 바로 반영(mode '확인 전'), 도장 뒤면 확인 대기 → 반영(상대 확인) / 거절 / 철회(화면: 거둠)
  // / 대체됨(같은 칸을 다시 고쳐 닫힘) / 처리 완료로 닫힘(REP-7l). 미응답이어도 반영하지 않습니다.
  // mode의 '상대 미확인'·'상대 없음'은 이전 규칙으로 남은 기록 표시용입니다. decidedBy = 확인·거절한 쪽
  status: "확인 대기" | "반영" | "거절" | "철회" | "대체됨" | "처리 완료로 닫힘"
  mode?: "확인 전" | "상대 확인" | "상대 미확인" | "상대 없음"
  rejectReason?: string
  decidedAt?: string
  // 요청할 때의 기록 모습 번호, 반영될 때 이전 내용에 달려 있던 이견(이력 보존)
  baseVersion?: number
  priorDispute?: string
  photosFrom?: string[]
  photosTo?: string[]
  decidedBy?: Role
  // 점검 정정의 기다림 타이머(시연)
  wait?: "재통지" | "미확인"
  // 상대 응답이 보류(더 필요한 게 있어요)·이견(사실과 달라요)이면 status는 '거절'이고 그 종류(없으면 이전 방식 거절)
  answerKind?: "보류" | "이견"
  // [사진 더해서 다시 요청]: 지난 응답 이력, 더한 근거, 마지막 다시 요청 시각(7일에 한 번)
  answers?: { kind?: "보류" | "이견"; reason: string; by?: Role; at?: string }[]
  supplements?: { text: string; photos: number; at: string }[]
  evidencePhotos?: string[]
  reaskedAt?: string
}
export interface Payment {
  version?: number
  paidOn?: string
  id: string
  by: Role
  payer?: Role | "제3자"
  payee?: Role | "업체" | "제3자"
  amount: number
  confirmed: boolean
  payerLabel?: string
  payeeLabel?: string
  evidence?: string
  photos?: string[]
  at?: string
  disputes?: PaymentDispute[]
  corrections?: FactCorrection<number>[]
  // 마지막 [보충하고 다시 확인 요청] 시각(ISO). 한 기록에 7일에 한 번이에요.
  reaskedAt?: string
  // 보충하고 다시 요청할 때 더한 설명들(같은 설명을 다시 내는 반복 요청을 막는 데 써요)
  supplements?: { text: string; photos: number; at: string }[]
}
// inspectionRefs는 과거 시연 연결 메타데이터의 이력 호환성용입니다. 현 MVP에서는 읽거나 새로 기록하지 않습니다.
export type WaitStage = "재통지" | "미확인"
export type SoloReason =
  "상대 미확인" | "상대 수신 거부" | "상대 탈퇴" | "상대 없음"
// 표현 정정(화면: '글 수정'): 제목·설명을 바로 고치고 수정 전·후를 이력으로 남깁니다. photosAdded는 이전 방식(사진 보충) 기록 호환용
export interface TextEdit {
  by: Role
  at: string
  titleBefore?: string
  titleAfter?: string
  before: string
  after: string
  photosAdded?: number
}
export interface Repair {
  // 합의 뒤 '바꾸자는 새 제안'(E): 상대가 동의할 때까지 지금 합의(proposal)가 그대로 쓰여요
  changeProposal?: Proposal
  // 이전 방식(바로 반영)의 글 수정 이력. 새 고치기는 corrections에 남습니다
  edits?: TextEdit[]
  // 잘못 올린 사진 표시(PHO-9): 수리 전 사진 순번. 지우지 않고 흐리게 맨 뒤로
  wrongPhotos?: number[]
  // 기다림 타이머(시연): 응답이 필요한 요청별 단계. 키: ack, close, proposal, pay:<지불 id>, fix:<정정 id>
  waits?: Record<string, WaitStage>
  // 하자 응답의 보류(더 필요한 게 있어요)·이견(사실과 달라요)과 사유(08 2장)
  ackDispute?: {
    by: Role
    type: "보류" | "이견"
    reason: string
    at: string
  }
  // 마지막 [보충하고 다시 확인 요청] 시각(ISO). 한 기록에 7일에 한 번이에요.
  reaskedAt?: string
  // 보충하고 다시 요청할 때 더한 설명들(같은 설명을 다시 내는 반복 요청을 막는 데 써요)
  supplements?: { text: string; photos: number; at: string }[]
  // 앞모습 번호(고친 내용이 반영될 때마다 +1). 확인할 때 본 번호와 비교해요.
  version?: number
  startFlag?:
    | "상대 확인 전 수리 시작"
    | "보충 요청 중 수리 시작"
    | "이견 유지 중 수리 시작"
  soloReason?: SoloReason
  recurrenceOf?: string
  corrections?: FactCorrection<string>[]
  // 사진별 '링크에서 숨김'. 키: b:<수리 전 순번>, a:<수리 후 기록 id>:<순번>, l:<이전 형식 수리 후 순번>
  hiddenPhotos?: string[]
  privacyMasks?: PhotoMask[]
  // 통지 상태(시나리오 5 · 목업): 발송 요청됨 → 발송 성공 → 링크 열람. 상대 연결 시 단독 종료 건의 종료 확인 요청 여부
  notifyLog?: { label: string; at: string }[]
  linkViews?: number
  confirmRequested?: boolean
  inspectionRefs?: string[]
  id: string
  roomId: string
  title: string
  zone: string
  description: string
  status: RepairStatus
  ack: Ack
  author: Role
  photos: string[]
  art: string
  date: string
  proposal?: Proposal
  proposalHistory?: Proposal[]
  causes?: CauseClaim[]
  afterRecords?: AfterRecord[]
  closeBy?: Role
  soloClosed?: Role
  afterPhotos?: string[]
  hidden?: boolean
  notes?: { text: string; by: Role; kind: string; at?: string }[]
  payments?: Payment[]
}
// 점검 하자 항목의 이견 기록(누가·사유·시각). 원본 기록은 그대로 두고 덧붙입니다.
// kind: 점검 항목 응답의 보류(더 필요한 게 있어요)·이견(사실과 달라요). 없으면 이전 방식의 이견이에요.
// closed: 기록한 쪽이 보충하고 다시 확인을 요청해 이 응답이 지난 것이 됨(이력으로 남아요)
export interface InspectionDispute {
  by: Role
  reason: string
  at: string
  kind?: "보류" | "이견"
  closed?: boolean
}
// 개인정보 이유로 가린 사진(팀 회의 결정): 삭제 기능은 두지 않고, 올린 사람이 가리면 흐리게 보이며 "개인정보 이유로 가림"이 남아요.
// 되돌릴 수 없고, 상대는 가린 데에 이견을 덧붙일 수 있어요. key: 수리 전 b:<순번>, 수리 후 a:<기록 id>:<순번>, 점검 항목 <순번>
export interface PhotoMask {
  key: string
  by: Role
  at: string
  objections?: InspectionDispute[]
}
export interface Inspection {
  version?: number
  id: string
  roomId: string
  kind: "in" | "out"
  title: string
  zone: string
  description: string
  photos: string[]
  ack: Ack
  author: Role
  art: string
  late?: boolean
  afterEnd?: boolean
  solo?: boolean
  publication?: "draft" | "requested"
  disputes?: InspectionDispute[]
  // 정정(상태와 규칙 G·H): 표현 정정 이력, 사실 정정(부위·항목 삭제), 빠진 하자 추가 요청. 원래 기록은 지우지 않습니다.
  edits?: TextEdit[]
  // 덧붙이기(사진 추가)와 잘못 올린 사진 표시(PHO-9)
  photoAdds?: { by: Role; at: string; count: number; afterConfirm: boolean }[]
  wrongPhotos?: number[]
  privacyMasks?: PhotoMask[]
  corrections?: FactCorrection<string>[]
  removed?: boolean
  addRequest?: FactCorrection<string>
  // 점검 확인 요청의 기다림 타이머(시연)
  wait?: "재통지" | "미확인"
  // 마지막 [보충하고 다시 확인 요청] 시각(ISO, 한 항목에 7일에 한 번)과 보충 이력
  reaskedAt?: string
  supplements?: { text: string; photos: number; at: string }[]
}
// ERD photo.inspection_id + room_part_id + MOVE_IN/MOVE_OUT에 대응하는 목업.
// 하자 항목과 달리 확인 상태를 갖지 않습니다. zone은 기존 화면의 부위 키입니다.
export interface PartPhoto {
  sharedOnLink?: boolean
  id: string
  roomId: string
  kind: "in" | "out"
  zone: string
  title: string
  description: string
  photos: string[]
  author: Role
  recordedAt: string
  late?: boolean
  afterEnd?: boolean
}
export interface Settlement {
  id: string
  roomId: string
  title: string
  amount: number
  direction: "가산" | "차감"
  status: Money
  by: Role
  reason: string
  proposalReason?: string
  disputeReason?: string
  disputeBy?: Role
  round?: number
  history?: Omit<Settlement, "history">[]
  // 수리 건에서 온 후보로 넣은 항목(출처: 수리 건), 원상복구 부위 연결
  repairId?: string
  zone?: string
  // 빼기 제안(ERD proposal.exclude): 상대가 동의해야 정산에서 빠집니다
  // 제안 철회(1-2): 처음 제안을 철회하면 항목 자체가 직전 상태(없음)로 돌아가요. 이력은 보존
  withdrawn?: boolean
  // (자동) 수리 건 변경: 서버가 다시 확인 대기로 돌림. 실제로 수리 건을 바꾼 측(changedBy)은 제안자와 따로 남깁니다
  // pending: 수리 건 돈 상태가 미정·합의 대기·이견이라 금액을 아직 정할 수 없음(0원으로 바꾸지 않고 이전 금액을 따로 보여 줌)
  autoChange?: {
    from: number
    fromStatus?: Money
    to?: number
    at: string
    changedBy: Role
    pending?: boolean
    moneyStatus?: string
  }
  excludeProposal?: { by: Role; reason: string; at: string }
  excluded?: boolean
  excludeHistory?: { by: Role; reason: string; at: string; result: string }[]
}
export interface Room {
  id: string
  name: string
  members?: Partial<Record<Role, string>>
  address: string
  since: string
  status: "거주 중" | "거주 종료" | "정산 완료"
  connection: "연결 전" | "초대됨" | "연결됨" | "거절됨"
  deposit?: number
  createdAt?: string
  inviteLinks?: InviteLink[]
  transferBy?: Role
  received: boolean
  transferAmount?: number
  endRequested?: boolean
  finishBy?: Role
  creator?: Role
  phone?: string
  invitedAt?: string
  depositBy?: Role
  depositConfirmed?: boolean
  depositOffer?: AmountOffer
  depositHistory?: AmountOffer[]
  transfers?: Transfer[]
  // 시연용 상대 상태: 비회원 상대의 수신 거부, 연결된 상대의 탈퇴
  optedOut?: boolean
  counterpartLeft?: boolean
  // 입주 점검(호실당 하나, 상태와 규칙 H). 먼저 작성한 쪽이 기준 기록 작성자. 확인 요청은 점검 전체 단위.
  moveIn?: InspectionSession
  // 퇴거 점검도 입주 점검과 같은 규칙(호실당 하나, 먼저 작성한 쪽이 기준, 전체 확인 요청)
  moveOut?: InspectionSession
  // 같은 집 기록이 두 개일 때 상대 기록에 합류하면 내 기록은 '합류 전 기록'(읽기만)
  preJoinOf?: string
  // 집주인 쪽 거주 종료 요청의 기다림 타이머(시연)와 미확인 뒤 적용 여부
  endWait?: "재통지" | "미확인"
  endUnconfirmed?: boolean
  // 정산 초대 링크(거주 종료 뒤 연결되지 않은 상대에게, 내용 없음)
  settlementLinks?: {
    id: string
    issuedAt: string
    invalidatedAt?: string
    invalidatedReason?: string
  }[]
  shareTarget?: { kind: "repair" | "inspection"; id: string }
}
// 초대 링크 목업(ERD link). 번호 값은 남기지 않고 발급·무효 사실만 남깁니다.
export interface InviteLink {
  id: string
  issuedAt: string
  views?: number
  invalidatedAt?: string
  invalidatedReason?: "번호 변경" | "연결됨"
}
export interface InspectionSession {
  author: Role
  status: "작성 중" | "확인 요청" | "잠김"
  startedAt: string
  requestedAt?: string
  noDefects?: boolean
  noDefectsConfirmedBy?: Role
  // 상대 없음으로 혼자 마친 점검(단독 기록). 상대가 연결되면 확인 요청이 한 번에 갑니다.
  soloLocked?: boolean
  // 늦은 입주 점검(INSP-2a): 입주일보다 늦게 시작했으면 시작한 날(YYYY-MM-DD). 화면에 "입주 후 등록(날짜)"
  lateOn?: string
}
export interface Notice {
  id: string
  roomId: string
  title: string
  to: Role
  read: boolean
  repairId?: string
}
export interface DemoState {
  rooms: Room[]
  repairs: Repair[]
  inspections: Inspection[]
  partPhotos: PartPhoto[]
  settlements: Settlement[]
  events: Event[]
  notices: Notice[]
}
export const uid = () => crypto.randomUUID()
export const won = formatWon
export const other = (role: Role): Role =>
  role === "임차인" ? "임대인" : "임차인"
// API 연결 시 이 초기 데이터와 useDemo의 명령 함수를 서버 저장소로 교체합니다.
// 실제 서비스 시작 상태: 아무 기록도 없어요. 가입한 회원이 호실을 만들거나 초대를 받아 연결하면서 채워져요.
export const emptyState = (): DemoState => ({
  rooms: [],
  repairs: [],
  inspections: [],
  partPhotos: [],
  settlements: [],
  events: [],
  notices: [],
})
export function createDemo(): DemoState {
  return {
    rooms: [
      {
        id: "302",
        name: "연남하우스 302호",
        address: "서울시 마포구 연남동 · A동",
        since: "2026-03-01",
        status: "거주 중",
        connection: "연결됨",
        deposit: 10000000,
        received: false,
        moveIn: {
          author: "임차인",
          status: "확인 요청",
          startedAt: "2026. 3. 1.",
          requestedAt: "2026. 3. 1.",
        },
      },
      {
        id: "201",
        name: "망원빌라 201호",
        address: "서울시 마포구 망원동 · B동",
        since: "2024-05-01",
        status: "거주 종료",
        connection: "연결됨",
        deposit: 10000000,
        depositConfirmed: true,
        received: false,
        moveIn: {
          author: "임차인",
          status: "확인 요청",
          startedAt: "2024. 5. 1.",
          requestedAt: "2024. 5. 1.",
        },
        moveOut: {
          author: "임차인",
          status: "확인 요청",
          startedAt: "2026. 10. 30.",
          requestedAt: "2026. 10. 30.",
        },
      },
    ],
    repairs: [
      {
        id: "leak",
        roomId: "302",
        title: "욕실 천장 누수",
        zone: "욕실 · 천장",
        description:
          "비가 온 뒤 천장에 물 자국이 생겼어요. 물방울이 떨어지는 부분과 주변 상태를 함께 기록했습니다.",
        status: "처리 중",
        ack: "확인됨",
        author: "임차인",
        photos: [],
        art: "ceiling",
        date: "2026.11.04",
        proposal: {
          by: "임대인",
          amount: 65000,
          payer: "임대인",
          status: "합의 대기",
        },
      },
      {
        id: "window",
        roomId: "302",
        title: "창틀 실리콘 틈",
        zone: "침실 · 창문",
        description:
          "창틀 틈새로 바람이 들어와 실리콘 보수를 진행했습니다. 수리는 종료했으며 비용 부담은 아직 정하지 않았습니다.",
        status: "처리 완료",
        ack: "확인됨",
        author: "임차인",
        photos: [],
        art: "window",
        date: "2026.10.22",
      },
      {
        id: "wall",
        roomId: "302",
        title: "벽지 모서리 들뜸",
        zone: "거실 · 벽",
        description:
          "책상 옆 벽지 모서리가 일부 들떠 있습니다. 현재 상태를 기록하고 확인을 요청합니다.",
        status: "확인 전",
        ack: "상대 미확인",
        waits: { ack: "미확인" },
        author: "임차인",
        photos: [],
        art: "wall",
        date: "2026.11.06",
      },
      {
        id: "old",
        roomId: "201",
        title: "욕실 천장 누수",
        zone: "욕실 · 천장",
        description: "퇴거 전 수리를 마쳤습니다.",
        status: "처리 완료",
        ack: "확인됨",
        author: "임차인",
        photos: [],
        art: "ceiling",
        date: "2026.08.12",
        proposal: {
          by: "임대인",
          amount: 65000,
          payer: "임대인",
          status: "합의됨",
        },
      },
    ],
    inspections: [
      {
        id: "i1",
        roomId: "302",
        kind: "in",
        title: "입주 당시 벽지 얼룩",
        zone: "거실 · 벽",
        description: "창가 옆 벽지에 작은 얼룩이 있어요.",
        photos: [],
        ack: "확인됨",
        author: "임차인",
        art: "wall",
      },
      {
        id: "i2",
        roomId: "302",
        kind: "in",
        title: "현관 바닥 스크래치",
        zone: "현관 · 바닥",
        description: "입주일에 촬영한 현관 바닥 상태입니다.",
        photos: [],
        ack: "확인 대기",
        author: "임차인",
        art: "floor",
      },
      {
        id: "i3",
        roomId: "201",
        kind: "in",
        title: "입주 당시 벽지 얼룩",
        zone: "거실 · 벽",
        description: "입주 첫날 촬영한 기존 얼룩입니다.",
        photos: [],
        ack: "확인됨",
        author: "임차인",
        art: "wall",
      },
      {
        id: "i4",
        roomId: "201",
        kind: "out",
        title: "퇴거 당시 벽지 상태",
        zone: "거실 · 벽",
        description: "입주 때와 같은 부위를 비교해 주세요.",
        photos: [],
        ack: "확인 대기",
        author: "임차인",
        art: "wall",
      },
    ],
    partPhotos: [],
    settlements: [
      {
        id: "s1",
        roomId: "201",
        title: "욕실 누수 수리비 환급",
        amount: 65000,
        direction: "가산",
        status: "합의됨",
        by: "임대인",
        reason: "임대인 부담, 임차인이 업체에 먼저 지불",
      },
      {
        id: "s2",
        roomId: "201",
        title: "관리비 미납",
        amount: 70000,
        direction: "차감",
        status: "합의됨",
        by: "임대인",
        reason: "퇴거 월 관리비 정산",
      },
      {
        id: "s3",
        roomId: "201",
        title: "벽지 도배",
        amount: 150000,
        direction: "차감",
        status: "이견",
        by: "임대인",
        reason: "임차인 의견: 입주 당시에도 존재한 얼룩",
      },
    ],
    events: [
      {
        id: "e1",
        target: { kind: "repair", id: "leak" },
        roomId: "302",
        text: "욕실 천장 누수 · 비용 부담 65,000원 제안",
        by: "임대인",
        at: "2026.11.06 15:00",
      },
      {
        id: "e2",
        target: { kind: "repair", id: "wall" },
        roomId: "302",
        text: "벽지 모서리 들뜸 · 하자 등록",
        by: "임차인",
        at: "2026.11.06 11:00",
      },
      {
        id: "e3",
        target: { kind: "repair", id: "leak" },
        roomId: "302",
        text: "욕실 천장 누수 · 수리 시작",
        by: "임대인",
        at: "2026.11.05 10:00",
      },
      {
        id: "e4",
        target: { kind: "repair", id: "leak" },
        roomId: "302",
        text: "욕실 천장 누수 · 사실 기록 확인",
        by: "임대인",
        at: "2026.11.04 14:20",
      },
      {
        id: "e5",
        roomId: "201",
        text: "벽지 도배 · 정산 항목에 이견 등록",
        by: "임차인",
        at: "2026.11.04 09:20",
      },
    ],
    notices: [
      {
        id: "n1",
        roomId: "302",
        title: "욕실 천장 누수의 비용 부담 제안을 확인해 주세요.",
        to: "임차인",
        read: false,
        repairId: "leak",
      },
      {
        id: "n2",
        roomId: "302",
        title: "벽지 모서리 들뜸의 사실 기록 확인 요청이 도착했어요.",
        to: "임대인",
        read: false,
        repairId: "wall",
      },
    ],
  }
}

export const partGroups: Record<string, string[]> = {
  현관: ["현관문·도어락", "신발장", "바닥"],
  방: [
    "벽",
    "천장",
    "바닥",
    "창문·창틀",
    "방충망",
    "붙박이장",
    "조명",
    "콘센트·스위치",
  ],
  주방: ["싱크대·배수구", "수전", "가스레인지·인덕션", "후드", "수납장"],
  욕실: [
    "천장",
    "벽·타일",
    "바닥·배수구",
    "변기",
    "세면대·수전",
    "샤워기·욕조",
    "환풍기",
  ],
  "설비·가전": ["보일러", "에어컨", "냉장고", "세탁기", "인터폰"],
  기타: ["기타"],
}
