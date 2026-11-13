import {
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type FormEvent,
} from "react"
import {
  ArrowRight,
  Bell,
  Building2,
  Camera,
  ChevronRight,
  CircleCheck,
  ClipboardCheck,
  FileText,
  History,
  Info,
  LayoutDashboard,
  Link2,
  LogOut,
  Menu,
  Plus,
  Search,
  ShieldCheck,
  Wallet,
  Wrench,
  X,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Checkbox } from "@/components/ui/checkbox"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog"
import {
  Status,
  Logo,
  Panel,
  Empty,
  Timeline,
  Choice,
} from "@/components/prototype"
import {
  emptyState,
  other,
  uid,
  won,
  partGroups,
  type Role,
  type Repair,
  type DemoState,
  type Inspection,
  type Room,
  type PartPhoto,
  type RecordTarget,
} from "@/data/demo"
import { useRouter, type Page } from "@/lib/router"
import { EmptyState, LoadingState, NotFoundState } from "@/components/states"
import { NotificationsPage } from "@/components/notifications-page"
import { HomeDashboard, RepairListItem } from "@/components/home-dashboard"
import { RepairDetail } from "@/components/repair-detail"
import { ApiProvider, createApi } from "@/api"
import { createMockApi } from "@/api/mock/mock-api"
import { createMockStore, recordAction, type MockStore } from "@/api/mock/store"
import {
  createDevSeed,
  createSampleSeed,
  wantsSample,
} from "@/api/mock/dev-seed"
import { createAuth, maskPhone, type Account } from "@/api/auth"
import { AuthScreen } from "@/components/auth-screen"
import { API_MODE } from "@/config"
import { PhotoGallery } from "@/components/photo-gallery"
import { CountChip } from "@/components/fold"
import { AddressSearch } from "@/components/address-search"
import { StatusTag } from "@/components/home-dashboard"
import { MoneyInput } from "@/components/money-input"
import { ReceivedInvitations } from "@/components/received-invitations"
import { PartPhotoContent } from "@/components/part-photo-content"
import { RecordDescription } from "@/components/record-description"
import { InspectionDisputes } from "@/components/inspection-disputes"
import { CostAgreement, CauseNotes } from "@/components/repair-discussions"
import { RepairActions, RepairBadges } from "@/components/repair-status"
import {
  CorrectionHistory,
  RepairCorrections,
  type RepairCorrectionsHandle,
  PhotoVisibility,
  visibleBefore,
  visibleAfter,
  beforePhotos,
  splitWrong,
  WrongPhotos,
  RepairMaskedNotes,
} from "@/components/repair-records"
import { afterMasks, beforeMasks, maskOf } from "@/lib/photo-privacy"
import { RepairPayments } from "@/components/repair-payments"
import { MoneyLeftNotice } from "@/components/money-left"
import {
  InspectionItemCorrections,
  MissingDefectDialog,
  ConfirmAllItems,
} from "@/components/inspection-corrections"
import { RepairCandidates } from "@/components/repair-candidates"
import { ComparisonRow } from "@/components/comparison-row"
import { syncRepairSettlements } from "@/lib/repair-rules"
import {
  inspectionStatus,
  sessionOf,
  kindLabel,
  ensureAuthor,
  requestInspectionAll,
  finishSoloInspection,
  confirmNoDefects,
  confirmItem,
  startInspection,
} from "@/lib/inspection-rules"
import { RoomFinancials } from "@/components/room-financials"
import { parseMoney } from "@/lib/money"
import {
  canAccessRoom,
  canSeeInspection,
  respondToOffer,
  invitationMatches,
  acceptInvitation,
  declineInvitation,
  canReceiveInvitation,
  isLateMoveInInspection,
  todayKey,
  issueInvite,
  canChangeInvitePhone,
  activeInviteLinks,
  isSettlementInvitation,
  activeSettlementLink,
  sendSettlementLink,
  logRepairNotice,
  recordLinkView,
  requestSoloRecordsOnConnect,
  canEditNumberOnlyPhone,
  updateNumberOnlyPhone,
  sideOf,
  inviteeSide,
} from "@/lib/workflows"
import { myTurn } from "@/lib/turn"
import {
  hasResidentId,
  hasUnsettledMoney,
  RESIDENT_ID_MESSAGE,
  safeSharedText,
} from "@/lib/service-rules"
import "./App.css"
import "./readability.css"
import "./service.css"
import { sideName, sideText } from "@/lib/words"
import { plainText } from "@/lib/plain-words"
import { Notice } from "@/components/notice"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"

// 브라우저가 주소 변경을 막는 환경(file:// 등)에서도 화면 동작은 계속되게 합니다.
function replaceUrl(_state: null, _title: string, url: string) {
  try {
    window.history.replaceState(null, "", url)
  } catch {
    /* 주소 표시만 바뀌지 않음 */
  }
}
type Modal =
  | "repair"
  | "room"
  | "inspection"
  | "invite"
  | "proposal"
  | "settlement"
  | "payment"
  | "note"
  | "dispute"
  | "account"
  | "after"
  | "connect"
  | "reconfirm"
  | "phone"
  | null
const nav = [
  { id: "home", label: "홈", icon: LayoutDashboard },
  { id: "rooms", label: "호실 기록", icon: Building2 },
  { id: "inspection", label: "입주 점검", icon: ClipboardCheck },
  { id: "repairs", label: "하자 · 수리 기록", icon: Wrench },
  { id: "money", label: "수리비 · 부담 합의", icon: Wallet },
  { id: "checkout", label: "퇴거 · 정산", icon: FileText },
  { id: "connection", label: "초대 및 연결", icon: Link2 },
  { id: "timeline", label: "행동 기록", icon: History },
] as const
// 화면 머리말: 각 화면이 무엇을 하는 곳인지 한 줄로 알려 줘요.
const pageIntro: Partial<Record<Page, string>> = {
  rooms: "거주 기간마다 호실 기록이 하나씩 생겨요. 지금 볼 호실을 골라 주세요.",
  inspection:
    "입주 당시 상태를 부위별 사진으로 남기고, 상대 확인을 받아 기준 기록으로 잠가요.",
  repairs:
    "하자를 사진과 함께 남기고, 확인 · 수리 · 종료까지의 경과를 한곳에서 봐요.",
  money: "수리 진행과 별개로, 누가 얼마를 부담할지 제안 · 동의로 정해요.",
  checkout:
    "입주 기록과 지금을 같은 부위끼리 비교하고, 합의한 항목으로 보증금을 정산해요.",
  connection:
    "상대를 초대해 같은 기록을 함께 봐요. 연결은 번호 확인과 수락을 거쳐야 해요.",
  timeline: "이 호실에서 있었던 일을 시간순으로 모았어요.",
  notifications: "응답이 필요한 일과 새 소식을 모았어요.",
}

// 호실이 하나도 없을 때 화면 계산에 쓰는 빈 호실(화면에는 나타나지 않아요)
const NO_ROOM: Room = {
  id: "",
  name: "",
  address: "",
  since: "",
  status: "거주 중",
  connection: "연결 전",
  received: false,
}
const afterConfirmPhotos = (r: Repair) =>
  (r.notes || [])
    .filter((n) => n.kind.includes("확인 뒤 추가"))
    .reduce((s, n) => s + Number(n.text.match(/사진 (\d+)장/)?.[1] || 0), 0)
const isShareLink = () => {
  const q = new URLSearchParams(window.location.search)
  return q.has("share") || q.has("inspection") || q.has("photo")
}

// 앱 진입점: 로그인 전에는 로그인·가입, 로그인 뒤에는 내 호실. 공유 링크(?share= 등)는 가입 전에도 그 기록 한 건만 읽어요.
// 기록 API가 연결되기 전까지 호실 기록은 저장소(api/mock/store)에 두고, 계정·인증은 api/auth(VITE_API_MODE=http면 서버)로 처리해요.
export default function App() {
  // 임시(개발용): 서버 없이 개발할 때는 확인 계정 두 개가 연결된 호실로 시작해요(api/mock/dev-seed).
  const [store] = useState(() =>
    createMockStore(
      API_MODE === "mock"
        ? wantsSample()
          ? createSampleSeed
          : createDevSeed
        : emptyState,
    ),
  )
  const [api] = useState(() => createApi(() => createMockApi(store)))
  const [auth] = useState(() => createAuth())
  const [account, setAccount] = useState<Account | null>(null)
  const [restoring, setRestoring] = useState(true)
  const [authOpen, setAuthOpen] = useState(false)
  useEffect(() => {
    let alive = true
    auth.restore().then((a) => {
      if (alive) {
        setAccount(a)
        setRestoring(false)
      }
    })
    return () => {
      alive = false
    }
  }, [auth])
  const link = isShareLink()
  if (restoring)
    return (
      <div className="auth-loading">
        <LoadingState label="HomeStamp를 여는 중이에요" />
      </div>
    )
  if (!account && (!link || authOpen))
    return (
      <AuthScreen
        auth={auth}
        initialMode={link ? "signup" : "login"}
        onDone={(a) => {
          setAccount(a)
          setAuthOpen(false)
        }}
        onBack={link ? () => setAuthOpen(false) : undefined}
        notice={
          link
            ? "공유받은 기록에 응답하려면 가입한 뒤, 초대받은 번호로 호실에 연결해 주세요."
            : undefined
        }
      />
    )
  return (
    <ApiProvider api={api}>
      <HomeStampApp
        key={account?.id ?? "guest"}
        store={store}
        account={account}
        onRequireAuth={() => setAuthOpen(true)}
        onLogout={() => {
          void auth.logout().finally(() => {
            replaceUrl(null, "", "/")
            setAccount(null)
          })
        }}
      />
    </ApiProvider>
  )
}

function HomeStampApp({
  store,
  account,
  onRequireAuth,
  onLogout,
}: {
  store: MockStore
  account: Account | null
  onRequireAuth: () => void
  onLogout: () => void
}) {
  const query = new URLSearchParams(window.location.search)
  const accountId = account?.id
  // 화면·호실·수리 건은 주소(/rooms/:roomId/...)에 둡니다. 뒤로 가기·새로고침·주소 공유가 그대로 동작해요.
  const [route, navigate] = useRouter({
    page: "home",
    roomId: "",
    repairId: query.get("share") || "",
  })
  const { page, repairId } = route
  const db = useSyncExternalStore(store.subscribe, store.getState)
  // 내 호실: 내가 만들었거나 연결을 수락한 호실. 호실마다 내 측(임차인/임대인)은 연결로 정해져요.
  const myRooms = db.rooms.filter((r) => {
    const s = sideOf(r, accountId)
    return Boolean(s && canAccessRoom(r, s))
  })
  const roomId = route.roomId || myRooms[0]?.id || ""
  const setRoomId = (id: string) =>
    navigate((r) => ({ ...r, roomId: id }), false)
  const setRepairId = (id: string) =>
    navigate((r) => ({ ...r, repairId: id }), false)
  const [guest, setGuest] = useState(isShareLink())
  const [sharedKind, setSharedKind] = useState<
    "repair" | "inspection" | "part-photo" | "invite" | "settlement"
  >(
    query.has("photo")
      ? "part-photo"
      : query.has("inspection")
        ? "inspection"
        : "repair",
  )
  const [sharedInspectionId, setSharedInspectionId] = useState(
    query.get("inspection") || "",
  )
  const [sharedPhotoId, setSharedPhotoId] = useState(query.get("photo") || "")
  const [inviteRoomId, setInviteRoomId] = useState("")
  const [inviteTarget, setInviteTarget] = useState<
    { kind: "repair" | "inspection"; id: string } | undefined
  >()
  const [counterProposal] = useState(false)
  const [invitePhone, setInvitePhone] = useState("")
  // 사용자가 '번호 변경'을 명시적으로 선택했을 때만 상대 번호를 수정합니다.
  const [changePhone, setChangePhone] = useState(false)
  // 재발 등록: 양측 확인으로 끝난 수리 건을 참조하는 새 수리 건(상태와 규칙 D 재발)
  const [recurrenceOf, setRecurrenceOf] = useState("")
  const [missingOpen, setMissingOpen] = useState(false)
  const [settlementZone, setSettlementZone] = useState("연결 안 함")
  const [joinChoice, setJoinChoice] = useState<"" | "join" | "own">("")
  const [sameHouseId, setSameHouseId] = useState("")
  const [settlePhone, setSettlePhone] = useState("")
  const [moneyPrefill, setMoneyPrefill] = useState<{
    from: Role
    to: Role
    amount: number
  }>()
  // 호실 만들기의 '내 쪽'. 마지막에 고른 쪽이 기본이에요(S-05).
  const [roomSide, setRoomSide] = useState<Role>("임차인")
  const [, setConnectionAccepted] = useState(false)
  const [mobile, setMobile] = useState(false)
  const [modal, setModal] = useState<Modal>(null)
  const [message, setMessage] = useState("")
  const [filter, setFilter] = useState("전체")
  const [search, setSearch] = useState("")
  const [hasInspectionDefect, setHasInspectionDefect] = useState(false)
  const [kind, setKind] = useState<"in" | "out">("in")
  const [zone, setZone] = useState("욕실")
  const [part, setPart] = useState("천장")
  const [photos, setPhotos] = useState<string[]>([])
  const [formError, setFormError] = useState("")
  const [paymentKind, setPaymentKind] = useState("업체 지불")
  const [paymentDirection, setPaymentDirection] = useState("보냈어요")
  const [payer, setPayer] = useState("임대인")
  const [direction, setDirection] = useState("차감")
  const [pending, setPending] = useState<{
    text: string
    update: (d: DemoState) => void
  } | null>(null)
  const [shareChoice, setShareChoice] = useState<"now" | "later" | null>(null)
  const [target, setTarget] = useState<{
    type: "repair" | "money" | "inspection" | "settlement" | "notyet"
    id: string
  }>({ type: "repair", id: "leak" })
  // 없는 호실 주소면 첫 호실 화면 대신 '찾을 수 없는 기록'을 보여 줘요(남의 호실도 같은 응답, 명세 4-3).
  const routeRoom = db.rooms.find((r) => r.id === roomId)
  const room = routeRoom ?? NO_ROOM
  const routeMissing =
    Boolean(route.notFound) ||
    (route.roomId !== "" && !routeRoom) ||
    (page === "detail" &&
      !db.repairs.some((r) => r.id === repairId && r.roomId === roomId))
  const repair = db.repairs.find((r) => r.id === repairId)
  const sharedInspection = db.inspections.find(
    (i) => i.id === sharedInspectionId,
  )
  const sharedPhoto = db.partPhotos.find((p) => p.id === sharedPhotoId)
  const sharedRepair = sharedKind === "repair" ? repair : undefined
  const sharedRoom = db.rooms.find(
    (r) =>
      r.id ===
      (sharedKind === "part-photo"
        ? sharedPhoto?.roomId
        : sharedKind === "inspection"
          ? sharedInspection?.roomId
          : sharedKind === "invite" || sharedKind === "settlement"
            ? inviteRoomId
            : sharedRepair?.roomId),
  )
  // 지금 화면의 내 쪽: 연결된 호실이면 연결된 쪽, 초대받은 호실이면 초대받는 자리. 수동으로 바꾸지 않아요.
  const roleRoom = guest && sharedRoom ? sharedRoom : routeRoom
  const role: Role =
    sideOf(roleRoom, accountId) ?? (roleRoom ? inviteeSide(roleRoom) : "임차인")
  const act = (
    text: string,
    update: (d: DemoState) => void,
    notify = true,
    eventRoomId = roomId,
    connecting = false,
    privateTo?: Role,
    target?: RecordTarget,
  ) =>
    recordAction(store, role, text, update, {
      notify,
      roomId: eventRoomId,
      connecting,
      privateTo,
      target,
    })
  const sessionPhone = account?.phone ?? ""
  // 같은 집 기록이 두 개일 때(기획서 195): 같은 쪽으로 내가 만든 거주 중 호실 중에서 골라 합류하거나 거절해요.
  const recordCount = (id: string) =>
    db.repairs.filter((x) => x.roomId === id).length +
    db.inspections.filter((x) => x.roomId === id).length +
    db.partPhotos.filter((x) => x.roomId === id).length
  const myOwnRooms = sharedRoom
    ? myRooms.filter(
        (x) =>
          x.id !== sharedRoom.id &&
          x.creator === role &&
          sideOf(x, accountId) === role &&
          x.status === "거주 중" &&
          !x.preJoinOf,
      )
    : []
  const sameHouseRooms = myOwnRooms.filter((x) => x.id === sameHouseId)
  const hasRoomAccess =
    Boolean(sideOf(room, accountId)) && canAccessRoom(room, role)
  const accessibleRooms = myRooms
  // 받은 초대: 가입할 때 인증한 번호로 온, 아직 연결하지 않은 초대
  const receivedInvitations = account
    ? db.rooms.filter(
        (r) =>
          !sideOf(r, accountId) &&
          canReceiveInvitation(r, inviteeSide(r), account.phone),
      )
    : []
  const visibleInspections = db.inspections.filter((i) =>
    canSeeInspection(i, role),
  )
  // 연결 수락: 로그인 계정의 인증 번호와 초대 번호가 같아야 하고, 기록 공유에 동의해야 해요.
  const phoneMatched = Boolean(
    account &&
    sharedRoom &&
    invitationMatches(sharedRoom, role, account.phone, account.phone),
  )
  // 연결은 [동의하고 연결하기]를 누르는 것으로 동의해요(S-08). 번호가 같아야 해요.
  const canConnect = Boolean(account && phoneMatched && sharedRoom)
  const repairs = hasRoomAccess
    ? db.repairs.filter((r) => r.roomId === roomId)
    : []
  const events = hasRoomAccess
    ? db.events.filter(
        (e) => e.roomId === roomId && (!e.privateTo || e.privateTo === role),
      )
    : []
  const notices = db.notices.filter((n) => {
    const r = db.rooms.find((x) => x.id === n.roomId)
    const s = sideOf(r, accountId)
    return Boolean(r && s && s === n.to && canAccessRoom(r, s))
  })
  const unread = notices.filter((n) => !n.read).length
  const settlements = hasRoomAccess
    ? db.settlements.filter((s) => s.roomId === roomId)
    : []
  const openCount = repairs.filter((r) => r.status !== "처리 완료").length
  const moneyLeft = repairs.filter(
    (r) => r.status === "처리 완료" && hasUnsettledMoney(r),
  )
  // 정산 완료 또는 상대 기록에 합류한 '합류 전 기록'은 읽기만
  const readOnly = room.status === "정산 완료" || Boolean(room.preJoinOf)
  const basicPhotoMode =
    modal === "inspection" && kind === "in" && !hasInspectionDefect
  // 입주 점검 작성 중에는 상대에게 내용 대신 '작성 중'만 보입니다(부위 사진 포함).
  const partPhotos = db.partPhotos.filter(
    (p) =>
      p.roomId === roomId &&
      hasRoomAccess &&
      !(
        sessionOf(room, p.kind)?.status === "작성 중" &&
        sessionOf(room, p.kind)!.author !== role &&
        p.author === sessionOf(room, p.kind)!.author
      ),
  )
  const inspectionItems = db.inspections.filter(
    (i) =>
      i.roomId === roomId && i.kind === (page === "inspection" ? "in" : "out"),
  )
  // 잠금: 처음 점검 항목이 모두 확인·이견·상대 미확인(정정으로 추가·삭제된 항목은 제외, lib/inspection-rules)
  // 점검은 입주·퇴거 각각 호실당 하나(상태와 규칙 H). 화면에 맞는 쪽의 점검 상태
  const sessKind: "in" | "out" = page === "checkout" ? "out" : "in"
  const session = sessionOf(room, sessKind)
  const moveIn = inspectionStatus(
    room,
    db.inspections.filter((i) => i.roomId === roomId && i.kind === sessKind),
    sessKind,
  )
  const moveInMine = !session || session.author === role
  // 입주 점검은 점검 전체 단위로 잠깁니다. 퇴거 점검은 기존 항목 기준 그대로.
  const inspectionLocked = moveIn === "잠김"
  // INSP-2b: 입주일이 비어 있는 호실은 입주 점검을 시작하기 전에 입주일부터 받아요(그 전에는 시작하는 버튼을 숨김)
  const needMoveInDate = page === "inspection" && !session && !room.since
  // 확인 요청 뒤에는 하자를 정정으로만 덧붙이고, 부위 사진은 작성자가 계속 올릴 수 있어요.
  const moveInDefectClosed = moveIn === "확인 요청"
  // 호실마다 다음에 할 일 한 줄(홈·호실 목록 공통)
  const nextStep = (r: Room) => {
    const openRepairs = db.repairs.filter(
      (x) => x.roomId === r.id && x.status !== "처리 완료",
    ).length
    return r.status === "정산 완료"
      ? "정산이 끝난 기록이에요 · 읽기 전용"
      : r.connection === "연결 전"
        ? "상대를 초대해 같은 기록을 함께 보세요"
        : !sessionOf(r, "in")
          ? "입주 점검을 시작해 보세요"
          : r.status === "거주 종료"
            ? "퇴거 정산을 마무리해 주세요"
            : openRepairs
              ? `진행 중인 수리 ${openRepairs}건`
              : "새로 확인할 일이 없어요"
  }
  // 하자·수리 / 수리비 목록의 탭별 건수
  const tabCount = (v: string) =>
    v === "전체"
      ? repairs.length
      : v === "내 차례"
        ? repairs.filter((r) => myTurn(r, role)).length
        : page === "money"
          ? v === "돈이 남은 건"
            ? moneyLeft.length
            : repairs.filter((r) => (r.proposal?.status || "미정") === v).length
          : repairs.filter((r) => r.status === v).length
  function go(p: Page) {
    navigate((r) => ({ ...r, page: p, roomId: r.roomId || roomId }))
    setMobile(false)
    setSearch("")
    setFilter("전체")
    window.scrollTo({ top: 0 })
  }
  function open(m: Modal) {
    setFormError("")
    setPhotos([])
    setShareChoice(null)
    if (m === "inspection") {
      setHasInspectionDefect(false)
      setZone("구역 선택")
      setPart("")
    }
    if (m === "repair") {
      setZone("욕실")
      setPart("천장")
      setRecurrenceOf("")
    }
    if (m === "settlement") setSettlementZone("연결 안 함")
    // 내 쪽 기본값 = 마지막에 고른 쪽(S-05): 브라우저 저장 없이, 내가 만든 가장 최근 호실의 쪽을 써요. 없으면 지금 고른 값 그대로
    if (m === "room") {
      const last = db.rooms
        .filter((r) => r.creator && r.members?.[r.creator] === accountId)
        .sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? ""))
        .at(-1)
      if (last?.creator) setRoomSide(last.creator)
    }
    if (m === "invite") {
      setInvitePhone(room.phone || "")
      setChangePhone(false)
    }
    if (m === "connect") {
      setConnectionAccepted(false)
      setJoinChoice("")
      setSameHouseId("")
    }
    setModal(m)
  }
  function toast(text: string) {
    setMessage(text)
    window.setTimeout(() => setMessage(""), 4500)
  }
  function commit(
    text: string,
    update: (d: DemoState) => void,
    eventRoomId = roomId,
    privateTo?: Role,
    recordTarget?: RecordTarget,
  ) {
    const targetRoom = db.rooms.find((r) => r.id === eventRoomId)
    if (
      targetRoom &&
      !(sideOf(targetRoom, accountId) && canAccessRoom(targetRoom, role))
    ) {
      toast("호실 연결 후에 기록할 수 있어요.")
      return
    }
    if (/거주 종료$|정산 끝내기/.test(text)) {
      setPending({ text, update })
      return
    }
    try {
      act(text, update, !privateTo, eventRoomId, false, privateTo, recordTarget)
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리하지 못했어요.")
      return
    }
    toast("기록했어요.")
    setModal(null)
  }
  // 공유받은 기록 한 건 보기(받은 초대에서 '공유받은 기록 보기')
  function showShared(
    type: "repair" | "inspection" | "part-photo",
    id: string,
  ) {
    setSharedKind(type)
    if (type === "repair") setRepairId(id)
    else if (type === "inspection") setSharedInspectionId(id)
    else setSharedPhotoId(id)
    setGuest(true)
    replaceUrl(
      null,
      "",
      type === "repair"
        ? `?share=${id}`
        : type === "inspection"
          ? `?inspection=${id}`
          : `?photo=${id}`,
    )
    window.scrollTo({ top: 0 })
  }
  // 이 기록 한 건만 보이는 링크 복사. 링크에는 위치·개인정보가 없어요(실제 토큰 발급은 서버 연결 시 링크 API로 바꿉니다).
  function copyShareLink(
    type: "repair" | "inspection" | "part-photo",
    id: string,
  ) {
    if (type === "part-photo") {
      const photo = db.partPhotos.find((p) => p.id === id)
      if (!photo || photo.author !== role || room.status !== "거주 중") return
      if (!photo.sharedOnLink)
        act(
          "부위 사진 · 이 기록만 읽기 전용 공유",
          (d) => {
            d.partPhotos.find((p) => p.id === id)!.sharedOnLink = true
          },
          false,
          photo.roomId,
          false,
          role,
        )
    }
    const url = `${window.location.origin}/?${type === "repair" ? "share" : type === "inspection" ? "inspection" : "photo"}=${encodeURIComponent(id)}`
    const done = () =>
      toast(
        "공유 링크를 복사했어요. 받은 사람은 이 기록 한 건만 읽을 수 있어요.",
      )
    if (navigator.clipboard)
      navigator.clipboard.writeText(url).then(done, () => toast(`링크: ${url}`))
    else toast(`링크: ${url}`)
  }
  function openReceivedInvite(invitation: Room) {
    if (
      !account ||
      !canReceiveInvitation(invitation, inviteeSide(invitation), account.phone)
    )
      return
    setInviteRoomId(invitation.id)
    setGuest(true)
    if (isSettlementInvitation(invitation)) {
      setSharedKind("settlement")
      open("connect")
      return
    }
    recordAction(
      store,
      inviteeSide(invitation),
      "링크 열람",
      (d) => recordLinkView(d, invitation.id),
      { roomId: invitation.id, connecting: true },
    )
    setSharedKind("invite")
    open("connect")
  }
  function rejectReceivedInvite() {
    if (
      !account ||
      !sharedRoom ||
      !canReceiveInvitation(sharedRoom, role, account.phone)
    )
      return
    act(
      "호실 연결 거절 · 제 집이 아니에요",
      (d) =>
        declineInvitation(
          d.rooms.find((r) => r.id === sharedRoom.id)!,
          role,
          account.phone,
        ),
      true,
      sharedRoom.id,
      true,
    )
    setGuest(false)
    setModal(null)
    setConnectionAccepted(false)
    replaceUrl(null, "", "/")
    navigate((r) => ({ ...r, page: "home", roomId: "" }))
    toast("초대를 거절했어요. 그 호실 기록에는 접근할 수 없어요.")
  }
  function detail(id: string) {
    setRepairId(id)
    go("detail")
  }
  // 점검 확인 요청: 작성자가 명시적으로 누를 때 입주 점검 전체가 확인 요청 상태가 됩니다. 상대가 없으면 초대와 함께 보냅니다.
  function requestMoveInCheck() {
    const items = db.inspections.filter(
      (i) =>
        i.roomId === roomId &&
        i.kind === sessKind &&
        i.author === role &&
        !i.addRequest &&
        !i.removed,
    )
    if (room.connection === "연결 전" && items.length) {
      setInviteTarget({ kind: "inspection", id: items[0].id })
      open("invite")
      return
    }
    commit(
      items.length
        ? `${kindLabel(sessKind)} 점검 확인 요청 · 하자 ${items.length}개`
        : `${kindLabel(sessKind)} 점검 확인 요청 · 하자 기록 없음`,
      (d) => requestInspectionAll(d, roomId, sessKind, role),
    )
  }
  // 점검 정정·확인 행동: 점검 항목 ID로 행동 기록을 남깁니다.
  function inspectionAct(
    text: string,
    update: (d: DemoState) => void,
    targetId: string,
  ) {
    commit(
      text,
      update,
      roomId,
      undefined,
      targetId ? { kind: "inspection", id: targetId } : undefined,
    )
  }
  function recur(prev: Repair) {
    open("repair")
    setRecurrenceOf(prev.id)
    const [z, p] = prev.zone.split(" · ")
    setZone(partGroups[z] ? z : "기타")
    setPart(p || "기타")
  }
  function dispute(type: typeof target.type, id: string) {
    setTarget({ type, id })
    open("dispute")
  }
  function patchRepair(text: string, update: (r: Repair) => void) {
    commit(
      `${repair!.title} · ${text}`,
      (d) => {
        update(d.repairs.find((r) => r.id === repairId)!)
        syncRepairSettlements(d, repairId, role)
      },
      roomId,
      undefined,
      { kind: "repair", id: repairId },
    )
  }
  function upload(files: FileList | null) {
    if (!files) return
    const list = Array.from(files)
    if (
      list.length + photos.length > 10 ||
      list.some(
        (f) =>
          f.size > 10 * 1024 * 1024 ||
          !["image/jpeg", "image/png", "image/webp"].includes(f.type),
      )
    ) {
      setFormError(
        "JPEG·PNG·WebP 사진을 10장까지, 장당 10MB 이하로 선택해 주세요.",
      )
      return
    }
    setFormError("")
    Promise.all(
      list.map(
        (file) =>
          new Promise<string>((resolve, reject) => {
            const reader = new FileReader()
            reader.onerror = reject
            reader.onload = () => {
              const img = new Image()
              img.onerror = reject
              img.onload = () => {
                const scale = Math.min(
                  1,
                  2048 / Math.max(img.width, img.height),
                )
                const canvas = document.createElement("canvas")
                canvas.width = img.width * scale
                canvas.height = img.height * scale
                canvas
                  .getContext("2d")!
                  .drawImage(img, 0, 0, canvas.width, canvas.height)
                resolve(canvas.toDataURL("image/jpeg", 0.85))
              }
              img.src = String(reader.result)
            }
            reader.readAsDataURL(file)
          }),
      ),
    )
      .then((p) => setPhotos((old) => [...old, ...p]))
      .catch(() =>
        setFormError("사진을 읽을 수 없어요. 다른 사진을 선택해 주세요."),
      )
  }
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    const text = (key: string) => String(f.get(key) || "").trim()
    if (
      Array.from(f.values()).some(
        (v) => typeof v === "string" && hasResidentId(v),
      )
    ) {
      setFormError(RESIDENT_ID_MESSAGE)
      return
    }
    const recordZone = zone === "기타" ? text("customZone") : zone
    const recordPart = part === "기타" ? text("customPart") : part
    if (
      (modal === "repair" || modal === "inspection") &&
      (!recordZone || recordZone === "구역 선택" || !recordPart)
    ) {
      setFormError("구역과 부위를 선택하고, 기타는 이름을 직접 입력해 주세요.")
      return
    }
    const amount = parseMoney(text("amount"))
    if (
      ["proposal", "settlement", "payment"].includes(modal || "") &&
      (!Number.isSafeInteger(amount) || amount < 0)
    ) {
      setFormError("금액은 0 이상의 정수로 입력해 주세요.")
      return
    }
    if (modal === "connect") {
      if (!account || !guest || !canConnect || !sharedRoom) {
        setFormError(
          phoneMatched
            ? "기록 공유에 동의해 주세요."
            : "초대받은 번호와 이 계정의 인증 번호가 달라 연결할 수 없어요.",
        )
        return
      }
      if (sameHouseRooms.length && joinChoice !== "join") {
        setFormError(
          "같은 집의 내 기록을 골랐어요. 상대 기록에 합류할지, 거절하고 내 기록으로 초대할지 골라 주세요.",
        )
        return
      }
      const joining = sameHouseRooms.map((x) => x.id)
      const accountPhone = account.phone,
        me = account.id
      act(
        "호실 연결 수락 · 번호 일치 확인 및 기록 공유 동의",
        (d) => {
          const target = d.rooms.find((r) => r.id === sharedRoom.id)!
          acceptInvitation(
            target,
            role,
            accountPhone,
            phoneMatched,
            true,
            accountPhone,
          )
          target.members = { ...target.members, [role]: me }
          // 혼자 끝낸 기록(점검·끝낸 수리 건·지불)에 확인 요청이 한 번에 가요(시나리오 3)
          requestSoloRecordsOnConnect(d, sharedRoom.id)
          // ① 상대 기록에 합류: 내 기록은 '합류 전 기록'(읽기만)으로 남아요
          joining.forEach((id) => {
            d.rooms.find((x) => x.id === id)!.preJoinOf = sharedRoom.id
          })
        },
        true,
        sharedRoom.id,
        true,
      )
      setModal(null)
      setGuest(false)
      replaceUrl(null, "", "/")
      navigate((r) => ({ ...r, page: "home", roomId: sharedRoom.id }))
      window.scrollTo({ top: 0 })
      toast(`${sharedRoom.name}에 ${sideText(role)}으로 연결했어요.`)
    } else if (modal === "repair") {
      if (!photos.length) {
        setFormError("수리 전 사진을 1장 이상 첨부해 주세요.")
        return
      }
      if (room.connection === "연결 전" && !shareChoice) {
        setFormError("상대에게 알릴지, 혼자 기록할지 선택해 주세요.")
        return
      }
      const id = uid()
      commit(
        `${text("title")} · 하자 등록${recurrenceOf ? " · 이전 수리 건 이후 재발" : ""}${room.connection === "연결 전" ? " · 혼자 남긴 기록" : " · 상대 확인 요청"}`,
        (d) => {
          d.repairs.unshift({
            id,
            roomId,
            title: text("title"),
            zone: `${recordZone} · ${recordPart}`,
            description: text("description"),
            photos,
            art: "wall",
            ack: "확인 대기",
            recurrenceOf: recurrenceOf || undefined,
            author: role,
            status: "확인 전",
            date: new Date().toLocaleDateString("ko-KR"),
          })
          if (room.connection === "초대됨") logRepairNotice(d.repairs[0])
        },
        roomId,
        undefined,
        { kind: "repair", id },
      )
      setRecurrenceOf("")
      setRepairId(id)
      go("detail")
      if (shareChoice === "now") {
        setInviteTarget({ kind: "repair", id })
        open("invite")
      }
    } else if (modal === "room") {
      if (!account) return
      // 서버 연결 시 주소는 검색 결과로만 채워져요(숨은 칸이라 required가 걸리지 않아 여기서 확인)
      if (API_MODE === "http" && !text("address")) {
        setFormError("[주소 검색]으로 동까지 골라 주세요.")
        return
      }
      const phone = text("phone").replace(/\D/g, "")
      if (phone && !/^010\d{8}$/.test(phone)) {
        setFormError("선택 전화번호를 010으로 시작하는 11자리로 입력해 주세요.")
        return
      }
      if (sessionPhone && phone === sessionPhone) {
        setFormError("내 계정 번호는 상대 번호로 넣을 수 없어요.")
        return
      }
      // 보증금은 만들 때 받지 않아요(S-05 칸 아님). 미입력으로 만들고 정산에서 보증금 확인·제안으로 남겨요.
      // 입주일도 비워 두면 그대로 비워 둬요(자동 날짜 없음). 입주 점검을 시작할 때 묻는 것은 INSP-2b
      const id = uid()
      // 처음 입력한 상대 번호는 이 호실의 기준 연락처로 저장만 하고, 초대·연결은 하지 않습니다.
      const side = roomSide,
        me = account.id
      recordAction(
        store,
        side,
        "새 호실 기록 만들기",
        (d) => {
          d.rooms.push({
            id,
            name: text("title"),
            address: text("address"),
            since: text("date"),
            createdAt: todayKey(),
            status: "거주 중",
            connection: "연결 전",
            received: false,
            creator: side,
            members: { [side]: me },
            phone: phone || undefined,
          })
        },
        { roomId: id, notify: false },
      )
      setModal(null)
      navigate((r) => ({ ...r, page: "home", roomId: id }))
      window.scrollTo({ top: 0 })
      toast(
        "호실 기록을 만들었어요. 입주 점검을 시작하거나 하자를 등록해 보세요.",
      )
    } else if (modal === "inspection") {
      if (basicPhotoMode) {
        if (!photos.length) {
          setFormError("부위 사진을 한 장 이상 첨부해 주세요.")
          return
        }
        const title =
          text("title") || recordZone + " · " + recordPart + " 입주 부위 사진"
        if (room.moveIn && room.moveIn.author !== role) {
          setFormError(
            `입주 점검은 호실에 하나예요. ${sideText(room.moveIn.author)}이 기준 기록을 쓰고 있어요.`,
          )
          return
        }
        commit(
          "입주 부위 사진 · " + title + " 등록",
          (d) => {
            ensureAuthor(
              d.rooms.find((r) => r.id === roomId)!,
              "in",
              role,
              true,
            )
            d.partPhotos.push({
              id: uid(),
              roomId,
              kind: "in",
              zone: recordZone + " · " + recordPart,
              title,
              description: text("photoMemo"),
              photos: [...photos],
              author: role,
              recordedAt: new Date().toLocaleString("ko-KR"),
              late: isLateMoveInInspection(room),
            })
          },
          roomId,
          role,
        )
        return
      }
      if (inspectionLocked) {
        setFormError(
          "점검이 잠겨 있어요. 기존 하자 기록은 고치기로 덧붙여 주세요.",
        )
        return
      }
      const inspectionId = uid()
      const kindSession = sessionOf(room, kind)
      if (
        kindSession &&
        (kindSession.author !== role || kindSession.status !== "작성 중")
      ) {
        setFormError(
          kindSession.author !== role
            ? `${kindLabel(kind)} 점검은 호실에 하나예요. ${sideText(kindSession.author)}이 기준 기록을 쓰고 있어요.`
            : "확인 요청을 보낸 뒤에는 하자를 [빠진 하자 추가]로 덧붙여요.",
        )
        return
      }
      // 입주 점검 하자 등록만으로는 확인 요청이 가지 않아요(점검 전체의 '점검 확인 요청'으로만).
      commit(
        `${kind === "in" ? "입주" : "퇴거"} 점검 · ${text("title")} 등록${room.connection === "연결 전" ? " · 혼자 남긴 기록(상대 확인 없음)" : ""}`,
        (d) => {
          ensureAuthor(
            d.rooms.find((r) => r.id === roomId)!,
            kind,
            role,
            false,
          )
          d.inspections.push({
            id: inspectionId,
            roomId,
            kind,
            title: text("title"),
            description:
              text("description") +
              (text("photoMemo") ? "\n\n사진 메모: " + text("photoMemo") : ""),
            zone: `${recordZone} · ${recordPart}`,
            photos,
            ack: "확인 대기",
            author: role,
            art: "wall",
            late: kind === "in" && isLateMoveInInspection(room),
            afterEnd: room.status === "거주 종료",
            solo: room.connection === "연결 전",
            publication: "draft",
          })
        },
        roomId,
        role,
      )
    } else if (modal === "invite") {
      // 번호 변경을 선택하지 않으면 호실에 저장된 기준 연락처를 그대로 사용합니다.
      const changing = changePhone && canChangeInvitePhone(room, role)
      const phone = (
        changing ? text("phone") : room.phone || text("phone")
      ).replace(/\D/g, "")
      if (!/^010\d{8}$/.test(phone)) {
        setFormError("010으로 시작하는 11자리 번호를 입력해 주세요.")
        return
      }
      if (sessionPhone && phone === sessionPhone) {
        setFormError("내 계정 번호는 상대로 넣을 수 없어요.")
        return
      }
      if (room.connection === "거절됨" && !changing) {
        setFormError(
          "상대가 거절한 번호로는 다시 초대할 수 없어요. 번호 변경을 선택하고 새 번호로 초대해 주세요.",
        )
        return
      }
      if (changing && phone === room.phone?.replace(/\D/g, "")) {
        setFormError(
          "기존 번호와 같아요. 다른 번호를 입력하거나 번호 변경을 취소해 주세요.",
        )
        return
      }
      // 행동 기록에는 번호 값을 남기지 않고 변경 사실만 남깁니다.
      commit(
        changing
          ? "상대 번호 변경 · 이전 초대 링크 무효 · 새 번호로 다시 초대"
          : "공유 확인 · 상대 초대 · 문자 발송 요청",
        (d) => {
          const r = d.rooms.find((r) => r.id === roomId)!
          issueInvite(r, role, phone, changing)
          r.shareTarget = inviteTarget
          if (inviteTarget?.kind === "repair") {
            const rep = d.repairs.find((x) => x.id === inviteTarget.id)
            if (rep) logRepairNotice(rep)
          }
          if (inviteTarget?.kind === "inspection") {
            const i = d.inspections.find((i) => i.id === inviteTarget.id)!
            requestInspectionAll(d, roomId, i.kind, role)
          }
        },
      )
      toast(
        API_MODE === "mock"
          ? "초대를 만들었어요. 서버가 연결되지 않아 문자는 보내지 않았어요. 상대가 이 번호로 가입하면 받은 초대에 보여요."
          : "초대 문자를 곧 보낼게요. 상대가 이 번호로 가입하고 연결하면 함께 볼 수 있어요.",
      )
    } else if (modal === "phone") {
      // 초대 전 번호 수정: 번호만 바꾸고 초대·링크 발급은 하지 않습니다.
      const phone = text("phone").replace(/\D/g, "")
      if (!/^010\d{8}$/.test(phone)) {
        setFormError("010으로 시작하는 11자리 번호를 입력해 주세요.")
        return
      }
      if (sessionPhone && phone === sessionPhone) {
        setFormError("내 계정 번호는 상대로 넣을 수 없어요.")
        return
      }
      if (phone === room.phone?.replace(/\D/g, "")) {
        setFormError("기존 번호와 같아요.")
        return
      }
      commit(
        "상대 번호 수정 · 초대 전",
        (d) =>
          updateNumberOnlyPhone(
            d.rooms.find((r) => r.id === roomId)!,
            role,
            phone,
          ),
        roomId,
        role,
      )
    } else if (modal === "proposal") {
      const tenantAmount = parseMoney(text("tenantAmount"))
      if (
        payer === "나눠서" &&
        (!Number.isSafeInteger(tenantAmount) ||
          tenantAmount < 0 ||
          tenantAmount > amount)
      ) {
        setFormError(
          "임차인 부담 금액을 전체 금액 이하로 입력해 주세요. 나머지는 임대인 부담입니다.",
        )
        return
      }
      const reason = text("proposalReason")
      if (counterProposal && !reason) {
        setFormError("다른 안 사유를 입력해 주세요.")
        return
      }
      patchRepair(
        `${counterProposal ? "부담 다른 안 제안" : "부담 제안"} · ${payer} ${won(amount)} · ${reason}`,
        (r) => {
          if (r.proposal)
            r.proposalHistory = [
              ...(r.proposalHistory || []),
              structuredClone(r.proposal),
            ]
          r.proposal = {
            by: role,
            payer: payer as Role | "나눠서",
            amount,
            status: "합의 대기",
            tenantAmount: payer === "나눠서" ? tenantAmount : undefined,
            reason,
            round: r.proposal ? (r.proposal.round || 1) + 1 : 1,
          }
        },
      )
    } else if (modal === "settlement") {
      commit(`${text("title")} · 정산 항목 제안`, (d) =>
        d.settlements.push({
          id: uid(),
          roomId,
          title: text("title"),
          amount,
          direction: direction as "가산" | "차감",
          status: "합의 대기",
          by: role,
          reason: text("description"),
          zone: settlementZone === "연결 안 함" ? undefined : settlementZone,
        }),
      )
    } else if (modal === "payment") {
      const paymentPayer =
        paymentKind === "당사자 간 상환" && paymentDirection === "받았어요"
          ? other(role)
          : role
      const paymentPayee =
        paymentKind === "업체 지불" ? "업체" : other(paymentPayer)
      if (
        repair!.payments?.some(
          (p) =>
            !p.confirmed &&
            (p.payer ?? p.by) === paymentPayer &&
            (p.payee ?? "업체") === paymentPayee &&
            p.amount === amount,
        )
      ) {
        setFormError(
          "같은 방향·금액의 확인 대기 기록이 있어요. 기존 기록의 금액 확인을 이용해 주세요.",
        )
        return
      }
      patchRepair(
        `${paymentKind} · ${paymentPayer} → ${paymentPayee} ${won(amount)} · 한쪽 기록`,
        (r) => {
          r.payments = [
            ...(r.payments || []),
            {
              id: uid(),
              by: role,
              payer: paymentPayer,
              payee: paymentPayee,
              amount,
              confirmed: false,
              at: new Date().toISOString(),
            },
          ]
        },
      )
    } else if (modal === "reconfirm") {
      if (repair!.author !== role) return
      patchRepair(
        `설명·사진 보충 후 다시 확인 요청 · ${text("description")}`,
        (r) => {
          r.notes = [
            ...(r.notes || []),
            {
              text: text("description"),
              by: role,
              kind: "보충·재확인 요청",
              at: new Date().toLocaleString("ko-KR"),
            },
          ]
          r.photos.push(...photos)
          r.ack = "확인 대기"
          r.ackDispute = undefined
          if (r.waits) delete r.waits.ack
        },
      )
    } else if (modal === "after") {
      if (!photos.length) {
        setFormError("수리 후 사진을 1장 이상 첨부해 주세요.")
        return
      }
      patchRepair(
        `수리 후 사진 ${photos.length}장 · ${text("description")}`,
        (r) => {
          r.afterPhotos = [...(r.afterPhotos || []), ...photos]
          r.afterRecords = [
            ...(r.afterRecords || []),
            {
              id: uid(),
              title: text("title") || "수리 후 사진",
              by: role,
              at: new Date().toLocaleString("ko-KR"),
              photos: [...photos],
              description: text("description"),
            },
          ]
          r.notes = [
            ...(r.notes || []),
            { text: text("description"), by: role, kind: "수리 후 기록" },
          ]
        },
      )
    } else if (modal === "dispute") {
      commit(`이견 · ${text("description")}`, (d) => {
        if (target.type === "inspection") {
          const it = d.inspections.find((i) => i.id === target.id)!
          it.ack = "이견"
          it.disputes = [
            ...(it.disputes || []),
            {
              by: role,
              reason: text("description"),
              at: new Date().toLocaleString("ko-KR", {
                timeZone: "Asia/Seoul",
              }),
            },
          ]
        } else if (target.type === "settlement")
          d.settlements.find((s) => s.id === target.id)!.status = "이견"
        else {
          const r = d.repairs.find((r) => r.id === target.id)!
          if (target.type === "money") {
            respondToOffer(r.proposal!, role, false, text("description"))
          } else if (target.type === "notyet") r.closeBy = undefined
          else r.ack = "이견"
          r.notes = [
            ...(r.notes || []),
            {
              text: text("description"),
              by: role,
              kind: target.type === "money" ? "비용 이견" : "하자 이견",
              at: new Date().toLocaleString("ko-KR"),
            },
          ]
        }
      })
    }
  }
  // 사진 필수: 입주 부위 사진, 하자 등록(수리 전 사진), 수리 후 기록
  const photoRequired =
    basicPhotoMode || modal === "repair" || modal === "after"
  const photoLabel =
    modal === "repair"
      ? "수리 전 사진"
      : modal === "after"
        ? "수리 후 사진"
        : "사진 기록"
  const fileField = (
    <div className="field">
      <label htmlFor="photos">
        {photoLabel}{" "}
        <span>{photoRequired ? "필수 · 1~10장" : "선택 · 최대 10장"}</span>
      </label>
      <label className="upload-zone" htmlFor="photos">
        <Camera size={25} />
        <strong>사진을 선택하거나 촬영해 주세요</strong>
        <small>JPEG, PNG, WebP · 장당 10MB 이하</small>
        <Input
          id="photos"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          onChange={(e) => upload(e.target.files)}
        />
      </label>
      <small className="muted">
        계약서·신분증·통장 사진은 올리지 마세요. 얼굴·우편물도 확인해 주세요.
      </small>
      <div className="preview-grid">
        {photos.map((p, i) => (
          <div key={i}>
            <img src={p} alt={`첨부 사진 ${i + 1}`} />
            <Button
              type="button"
              variant="secondary"
              size="icon-xs"
              aria-label={`첨부 사진 ${i + 1} 제외`}
              onClick={() => setPhotos(photos.filter((_, n) => n !== i))}
            >
              <X />
            </Button>
          </div>
        ))}
      </div>
    </div>
  )
  const partField = (
    <div className="form-grid">
      <div className="field">
        <label>구역</label>
        <Choice
          label="구역"
          value={zone}
          values={Object.keys(partGroups)}
          onChange={(v) => {
            setZone(v)
            setPart(
              v === "기타"
                ? "기타"
                : modal === "inspection" && kind === "in"
                  ? ""
                  : partGroups[v][0],
            )
          }}
        />
      </div>
      {zone !== "구역 선택" && (
        <div className="field">
          <label>부위 · 사진 비교 기준</label>
          <Choice
            label="부위"
            value={part || "부위 선택"}
            values={Array.from(new Set([...(partGroups[zone] || []), "기타"]))}
            onChange={setPart}
          />
        </div>
      )}
      {zone === "기타" && (
        <div className="field">
          <label htmlFor="custom-zone">구역 직접 입력</label>
          <Input id="custom-zone" name="customZone" required />
        </div>
      )}
      {part === "기타" && (
        <div className="field">
          <label htmlFor="custom-part">부위 직접 입력</label>
          <Input id="custom-part" name="customPart" required />
        </div>
      )}
    </div>
  )

  // 점검 항목 사진의 '개인정보 이유로 가림' 정보(보이는 순서대로). wrong: 잘못 올린 사진 쪽인지
  const itemMasks = (i: Inspection, wrong?: boolean) =>
    i.photos
      .map((_, n) => n)
      .filter(
        (n) =>
          wrong === undefined || Boolean(i.wrongPhotos?.includes(n)) === wrong,
      )
      .map((n) => maskOf(i, String(n)))
  const photoContent = (p: PartPhoto) => (
    <PartPhotoContent
      record={p}
      onShare={
        p.author === role && room.status === "거주 중"
          ? () => copyShareLink("part-photo", p.id)
          : undefined
      }
    />
  )
  // 점검 하자 항목: 썸네일 + 지금 기록(제목·부위·설명·상태)만. 이견·사진 추가·고친 이력은 항목의 '경과'에서 펼쳐 봐요.
  // 입주 점검 항목 카드 겉모양(흰 카드 · 안쪽 여백). 안쪽 배치는 rec-item 규칙 그대로
  // 문서 9장 [반드시]: 빠뜨린 하자 버튼 바로 아래 ④ 안내 한 줄(살면서 생긴 하자는 점검이 아니라 수리 건). 입주 점검에서만, 수리 건을 만들 수 있을 때(거주 중)만 누를 수 있어요
  const missingHint =
    page === "inspection" ? (
      <p className="basis-full text-right text-[13px] leading-[1.6] text-sub max-md:text-left">
        ⓘ 살면서 생기거나 나빠진 하자는{" "}
        {room.status === "거주 중" ? (
          <Button
            variant="link"
            size="sm"
            className="inline-flex min-h-0 p-0 align-baseline text-[13px]"
            onClick={() => open("repair")}
          >
            [수리 건 만들기]
          </Button>
        ) : (
          "[수리 건 만들기]"
        )}
        로 남겨 주세요
      </p>
    ) : null
  const inspectionCard =
    "min-w-0 self-start rounded-[16px] border border-line bg-white [&>.rec-item]:p-4 max-md:[&>.rec-item]:p-3.5"
  const inspectionContent = (i: Inspection) => (
    <div className="rec-item relative">
      <div className="rec-thumb">
        <PhotoGallery
          title={i.title}
          description={i.description}
          photos={splitWrong(i.photos, i.wrongPhotos).main}
          masks={itemMasks(i, false)}
          art={i.art}
          layout="compact"
        />
      </div>
      <div className="rec-body">
        <div className="rec-head pr-10">
          <h3>{i.title}</h3>
          <StatusTag>
            {i.publication === "draft"
              ? i.solo
                ? "작성 중 · 혼자 남긴 기록"
                : "작성 중"
              : i.solo
                ? "혼자 남긴 기록(상대 확인 없음)"
                : i.ack}
          </StatusTag>
        </div>
        <small className="rec-meta">
          {i.zone} · {i.kind === "in" ? "입주 기존 하자" : "퇴거 점검 하자"} ·{" "}
          {sideName(i.author)} 등록{i.late ? " · 입주 후 등록" : ""}
          {i.afterEnd ? " · 거주 종료 뒤 추가" : ""}
        </small>
        <RecordDescription text={i.description} />
        <WrongPhotos
          photos={splitWrong(i.photos, i.wrongPhotos).wrong}
          masks={itemMasks(i, true)}
        />
        {!readOnly &&
          i.author !== role &&
          i.ack === "확인 대기" &&
          !i.addRequest &&
          !i.removed && (
            <div className="actions">
              <Button
                size="sm"
                onClick={() =>
                  commit(
                    `${i.title} · 점검 확인 · 본 모습 ${i.version ?? 1}번`,
                    (d) => confirmItem(d, role, i.id, i.version ?? 1),
                  )
                }
              >
                확인
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => dispute("inspection", i.id)}
              >
                이견
              </Button>
            </div>
          )}
        <InspectionItemCorrections
          i={i}
          db={db}
          role={role}
          readOnly={readOnly}
          onAct={inspectionAct}
          history={
            i.disputes?.length ? (
              <InspectionDisputes disputes={i.disputes} />
            ) : undefined
          }
          onShare={
            i.author === role && i.publication !== "draft"
              ? () => copyShareLink("inspection", i.id)
              : undefined
          }
        />
      </div>
    </div>
  )

  const correctionsRef = useRef<RepairCorrectionsHandle>(null)
  // 수리 건 상세(S-12). 링크 열람자(isGuest)에게는 돈 관련 경과와 행동 버튼을 보이지 않아요.
  const detailView = (r: Repair, isGuest = false) => {
    const evs = db.events.filter(
      (e) =>
        e.roomId === r.roomId &&
        (!e.privateTo || (!isGuest && e.privateTo === role)) &&
        e.target?.kind === "repair" &&
        e.target.id === r.id &&
        (!isGuest ||
          !/부담|금액|지불|비용|정산|원/.test(e.text.replace(r.title, ""))),
    )
    const canAct = !isGuest && !readOnly
    return (
      <RepairDetail
        r={r}
        room={room}
        role={role}
        events={evs}
        isGuest={isGuest}
        backLabel={`${room.name} 수리 기록`}
        onBack={() => go("repairs")}
        notices={
          <>
            <RepairBadges
              compact
              r={r}
              room={db.rooms.find((x) => x.id === r.roomId) || room}
              repairs={
                isGuest ? [] : db.repairs.filter((x) => x.roomId === r.roomId)
              }
              onOpenRepair={isGuest ? undefined : detail}
            />
            {!isGuest && room.status !== "거주 중" && (
              <Notice>
                거주 종료 시점의 수리 상태예요. 돈·정산 기록은 계속할 수 있어요.
              </Notice>
            )}
          </>
        }
        gallery={
          <>
            <h3 className="mb-2.5 text-lg font-bold">수리 전 사진</h3>
            {!isGuest ||
            (!r.hidden &&
              !(r.photos.length > 0 && !visibleBefore(r).length)) ? (
              <>
                <PhotoGallery
                  title={`${r.title} · 수리 전`}
                  photos={beforePhotos(r, isGuest).main}
                  masks={beforeMasks(r, isGuest)}
                  art={r.art}
                  description={
                    isGuest ? safeSharedText(r.description) : r.description
                  }
                  layout="strip"
                />
                <WrongPhotos
                  photos={beforePhotos(r, isGuest).wrong}
                  masks={beforePhotos(r, isGuest).wrongMasks}
                />
              </>
            ) : (
              <Empty text="등록한 사람이 링크에서 사진을 숨겼어요." />
            )}
            {(!isGuest || !r.hidden) && Boolean(r.afterPhotos?.length) && (
              <section>
                <h3 className="mt-[22px] mb-2.5 text-lg font-bold">
                  수리 후 사진
                </h3>
                {r.afterRecords?.length
                  ? r.afterRecords.map((record) => (
                      <div key={record.id} className="after-record">
                        <h4>{record.title}</h4>
                        <p className="muted">
                          등록일 {record.at} · 등록자 {sideName(record.by)}
                        </p>
                        <PhotoGallery
                          title={record.title}
                          description={
                            isGuest
                              ? safeSharedText(record.description)
                              : record.description
                          }
                          photos={
                            isGuest
                              ? visibleAfter(r, record.id, record.photos)
                              : record.photos
                          }
                          masks={afterMasks(
                            r,
                            record.id,
                            record.photos,
                            isGuest,
                          )}
                          layout="strip"
                        />
                      </div>
                    ))
                  : null}
                {Boolean(
                  r.afterPhotos?.some(
                    (photo) =>
                      !r.afterRecords?.some((record) =>
                        record.photos.includes(photo),
                      ),
                  ),
                ) && (
                  <>
                    <p className="muted">
                      기존 수리 후 기록 · 등록일·등록자 정보 없음
                    </p>
                    <PhotoGallery
                      title={`${r.title} · 수리 후`}
                      photos={r.afterPhotos!.filter(
                        (photo) =>
                          !r.afterRecords?.some((record) =>
                            record.photos.includes(photo),
                          ),
                      )}
                      layout="strip"
                    />
                  </>
                )}
              </section>
            )}
            <RepairMaskedNotes
              r={r}
              role={role}
              readOnly={!canAct}
              onAction={patchRepair}
            />
          </>
        }
        description={
          <>
            <RecordDescription
              text={isGuest ? safeSharedText(r.description) : r.description}
            />
            {afterConfirmPhotos(r) > 0 && (
              <p className="fix-note">
                확인 뒤 추가한 사진 {afterConfirmPhotos(r)}장이 함께 있어요.
                추가한 내역은 경과에서 볼 수 있어요.
              </p>
            )}
          </>
        }
        actions={
          canAct && room.status === "거주 중" ? (
            <RepairActions
              r={r}
              room={room}
              role={role}
              onAction={patchRepair}
              onOpen={(m) => open(m)}
              onRecur={recur}
            />
          ) : undefined
        }
        corrections={
          <RepairCorrections
            ref={correctionsRef}
            r={r}
            room={room}
            role={role}
            readOnly={readOnly}
            guest={isGuest}
            onAction={patchRepair}
          />
        }
        timeline={
          <>
            {!isGuest && (r.notifyLog?.length || r.linkViews) ? (
              <div className="note mb-4">
                <strong>보낸 알림 상태</strong>
                {r.notifyLog?.map((n, k) => (
                  <p key={k}>
                    ● {plainText(n.label)} · {n.at}
                  </p>
                ))}
                <p>{r.ack === "확인됨" ? "● 확인" : "○ 확인 · 대기 중"}</p>
                <small className="muted block">
                  링크로 본 횟수 {r.linkViews || 0}회 · ‘보냈어요’는 문자 업체가
                  받았다는 뜻이고 상대가 읽었다는 보장은 아니에요.
                </small>
              </div>
            ) : null}
            <Timeline compact events={evs} stripTitle={r.title} />
            <CorrectionHistory
              list={r.corrections || []}
              edits={r.edits}
              stageOf={(c) => r.waits?.[`fix:${c.id}`]}
              guest={isGuest}
            />
          </>
        }
        money={
          !isGuest ? (
            <CostAgreement
              repair={r}
              room={room}
              role={role}
              readOnly={readOnly}
              onAction={patchRepair}
            />
          ) : undefined
        }
        payments={
          !isGuest ? (
            <RepairPayments
              r={r}
              room={room}
              role={role}
              readOnly={readOnly}
              onAction={patchRepair}
              prefill={moneyPrefill}
              onPrefillUsed={() => setMoneyPrefill(undefined)}
            />
          ) : undefined
        }
        causes={
          !isGuest ? (
            <CauseNotes
              repair={r}
              role={role}
              readOnly={readOnly || room.status !== "거주 중"}
              onAction={patchRepair}
            />
          ) : undefined
        }
        sameZone={
          <>
            {!isGuest && (
              <Panel title="같은 부위의 입주 기록">
                {partPhotos
                  .filter((p) => p.kind === "in" && p.zone === r.zone)
                  .map((p) => (
                    <div key={p.id}>{photoContent(p)}</div>
                  ))}
                {visibleInspections
                  .filter(
                    (i) =>
                      i.roomId === roomId &&
                      i.kind === "in" &&
                      i.zone === r.zone,
                  )
                  .map((i) => (
                    <div className="comparison-record" key={i.id}>
                      <PhotoGallery
                        title={i.title}
                        description={i.description}
                        photos={i.photos}
                        masks={itemMasks(i)}
                        art={i.art}
                        layout="compact"
                      />
                      <div>
                        <strong>{i.title}</strong>
                        <RecordDescription text={i.description} />
                        <Status>
                          {i.publication === "draft"
                            ? i.solo
                              ? "작성 중 · 혼자 남긴 기록"
                              : "작성 중"
                            : i.solo
                              ? "혼자 남긴 기록(상대 확인 없음)"
                              : i.ack}
                        </Status>
                        {i.author === role && i.publication !== "draft" && (
                          <Button
                            variant="link"
                            onClick={() => copyShareLink("inspection", i.id)}
                          >
                            공유 링크 복사
                          </Button>
                        )}
                        {i.late && (
                          <small className="block mt-2 muted">
                            입주 후 등록 · 입주 당시 상태와 다를 수 있어요.
                          </small>
                        )}
                        {i.afterEnd && (
                          <small className="block mt-2 muted">
                            거주 종료 뒤 추가된 기록이에요.
                          </small>
                        )}
                      </div>
                    </div>
                  ))}
                {!visibleInspections.some(
                  (i) =>
                    i.roomId === roomId && i.kind === "in" && i.zone === r.zone,
                ) &&
                  !partPhotos.some(
                    (p) => p.kind === "in" && p.zone === r.zone,
                  ) && (
                    <p className="muted">
                      입주 점검에 같은 부위의 기록이 없어요.
                    </p>
                  )}
              </Panel>
            )}
          </>
        }
        extra={
          <>
            {!isGuest && (
              <p className="svc-note">
                <ShieldCheck size={16} aria-hidden />
                하자를 확인해도 비용을 누가 낼지는 따로 정해요. HomeStamp는 누가
                맞는지 판단하지 않아요.
              </p>
            )}
            {!isGuest && room.status === "거주 중" && (
              <Button
                variant="outline"
                className="w-full"
                onClick={() => copyShareLink("repair", r.id)}
              >
                <Link2 />
                공유 링크 복사
              </Button>
            )}
          </>
        }
        photoSettings={
          canAct ? (
            <PhotoVisibility r={r} role={role} onAction={patchRepair} />
          ) : undefined
        }
        onEdit={
          canAct && r.author === role
            ? () => correctionsRef.current?.openEdit()
            : undefined
        }
        onAddPhotos={
          canAct && r.author === role
            ? () => correctionsRef.current?.openPhotos()
            : undefined
        }
        onNewRepair={
          canAct && room.status === "거주 중" ? () => open("repair") : undefined
        }
        onShare={
          canAct &&
          room.status === "거주 중" &&
          room.connection === "연결 전" &&
          room.creator === role
            ? () => {
                setInviteTarget({ kind: "repair", id: r.id })
                open("invite")
              }
            : undefined
        }
      />
    )
  }
  return (
    <>
      {guest ? (
        <div className="guest-shell">
          <header>
            <Logo />
            <div className="flex items-center gap-2">
              <Badge variant="secondary">
                {account ? "로그인됨 · 호실 연결 전" : "읽기 전용 · 비회원"}
              </Badge>
              {account ? (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setGuest(false)
                    replaceUrl(null, "", "/")
                    navigate((r) => ({ ...r, page: "home", roomId: "" }))
                  }}
                >
                  내 홈으로
                </Button>
              ) : (
                <Button size="sm" variant="outline" onClick={onRequireAuth}>
                  로그인
                </Button>
              )}
            </div>
          </header>
          <div className="guest-banner">
            <Link2 size={22} />
            <div>
              <h1>
                {sharedKind === "inspection"
                  ? "공유된 점검 하자 항목 · 읽기 전용"
                  : sharedKind === "part-photo"
                    ? "공유된 부위 사진 · 읽기 전용"
                    : sharedKind === "invite"
                      ? "호실 연결 초대"
                      : sharedKind === "settlement"
                        ? "퇴거 정산 확인 요청"
                        : "공유된 수리 기록 · 읽기 전용"}
              </h1>
              <p>
                이 링크에 지정된 기록 한 건만 표시합니다. 다른 점검·수리 기록과
                호실 전체 기록은 공개되지 않아요.
              </p>
            </div>
          </div>
          {sharedKind === "settlement" ? (
            sharedRoom && isSettlementInvitation(sharedRoom) ? (
              <Card className="guest-cta">
                <h2>퇴거 정산 확인 요청이 있어요</h2>
                <p>
                  정산 금액과 항목은 가입하고 호실에 연결한 뒤에 볼 수 있어요.
                </p>
                {account ? (
                  <Button onClick={() => open("connect")}>
                    호실 연결 확인
                    <ArrowRight />
                  </Button>
                ) : (
                  <Button onClick={onRequireAuth}>
                    가입하고 확인하기
                    <ArrowRight />
                  </Button>
                )}
              </Card>
            ) : (
              <Empty text="유효하지 않은 정산 초대 링크예요." />
            )
          ) : sharedRoom?.status === "거주 중" &&
            sharedRoom.connection !== "거절됨" &&
            (sharedKind === "invite" ||
              (sharedKind === "repair" && sharedRepair) ||
              (sharedKind === "inspection" &&
                sharedInspection &&
                sharedInspection.publication !== "draft") ||
              (sharedKind === "part-photo" && sharedPhoto?.sharedOnLink)) ? (
            <>
              {sharedKind === "repair" &&
                sharedRepair &&
                detailView(sharedRepair, true)}
              {sharedKind === "inspection" && sharedInspection && (
                <Panel title={safeSharedText(sharedInspection.title)}>
                  <Badge variant="secondary">
                    기존 하자 기록 · 응답은 연결 후 가능
                  </Badge>
                  <p className="muted">
                    {sharedInspection.kind === "in" ? "입주 당시" : "퇴거 당시"}{" "}
                    점검에 남긴 하자 항목 한 건입니다. 사실 확인과 비용 부담
                    동의는 별개입니다.
                  </p>
                  <RecordDescription
                    text={safeSharedText(sharedInspection.description)}
                  />
                  <PhotoGallery
                    title={safeSharedText(sharedInspection.title)}
                    description={safeSharedText(sharedInspection.description)}
                    photos={sharedInspection.photos}
                    masks={itemMasks(sharedInspection)}
                    art={sharedInspection.art}
                  />
                  <Status>{sharedInspection.ack}</Status>
                  <InspectionItemCorrections
                    i={sharedInspection}
                    db={db}
                    role={role}
                    readOnly
                    guest
                    onAct={inspectionAct}
                  />
                  <p className="muted">
                    현재 확인 상태만 표시합니다. 확인·이견 응답은 가입·번호
                    확인·호실 연결을 마친 뒤 회원 화면에서 남길 수 있어요.
                  </p>
                </Panel>
              )}
              {sharedKind === "part-photo" && sharedPhoto && (
                <Panel title="공유된 부위 사진 한 건">
                  <PartPhotoContent record={sharedPhoto} shared />
                </Panel>
              )}
              <Card className="guest-cta">
                <h2>
                  {sharedKind === "part-photo"
                    ? "호실의 기록을 함께 보려면"
                    : "연결한 뒤 기록에 응답할 수 있어요"}
                </h2>
                <p>
                  {sharedKind === "part-photo"
                    ? "이 사진은 상대 확인 대상이 아닙니다. 호실 전체 기록을 함께 보려면 가입·번호 확인·연결 수락이 필요해요."
                    : "가입·로그인만으로 호실 전체가 열리지는 않습니다. 초대 번호 확인과 명시적인 연결 수락을 마쳐야 해요."}
                </p>
                {account ? (
                  <Button onClick={() => open("connect")}>
                    호실 연결 확인
                    <ArrowRight />
                  </Button>
                ) : (
                  <Button onClick={onRequireAuth}>
                    가입·로그인하고 연결하기
                    <ArrowRight />
                  </Button>
                )}
              </Card>
            </>
          ) : (
            <Empty text="공유되지 않았거나 유효하지 않은 기록입니다." />
          )}
          <p className="guest-foot">
            공유 범위는 이 기록 한 건입니다. 읽음·열람은 하자 확인이나 합의가
            아닙니다.{" "}
          </p>
        </div>
      ) : (
        <div className="app-shell">
          <Sheet open={mobile} onOpenChange={setMobile}>
            <SheetContent side="left" className="mobile-nav">
              <SheetHeader className="sr-only">
                <SheetTitle>메뉴</SheetTitle>
                <SheetDescription>화면 이동과 내 계정</SheetDescription>
              </SheetHeader>
              <div className="side-top">
                <Logo />
              </div>
              <nav aria-label="주 메뉴">
                {nav.map((n) => (
                  <Button
                    variant="ghost"
                    key={n.id}
                    className={`nav-item ${page === n.id || (page === "detail" && n.id === "repairs") ? "active" : ""}`}
                    onClick={() => {
                      if (n.id === "inspection") setKind("in")
                      go(n.id)
                    }}
                  >
                    <n.icon size={19} />
                    {n.label}
                    {n.id === "repairs" && repairs.length > 0 && (
                      <span
                        className="nav-count"
                        title={`전체 ${repairs.length}건 · 진행 중 ${openCount}건`}
                        aria-label={`전체 ${repairs.length}건, 진행 중 ${openCount}건`}
                      >
                        {repairs.length}
                      </span>
                    )}
                  </Button>
                ))}
              </nav>
              <div className="sidebar-bottom">
                {hasRoomAccess && (
                  <div className="side-room">
                    <small>지금 보는 호실</small>
                    <strong>{room.name}</strong>
                    <span className="badges">
                      <Status>{room.status}</Status>
                      <Status>{room.connection}</Status>
                    </span>
                  </div>
                )}
                <Button
                  variant="ghost"
                  className="profile"
                  onClick={() => open("account")}
                >
                  <span className="avatar">
                    {account?.name.slice(0, 1) || "나"}
                  </span>
                  <span>
                    <strong>{account?.name} 님</strong>
                    <small>
                      {hasRoomAccess
                        ? `이 호실에서 ${sideText(role)}`
                        : account?.email}
                    </small>
                  </span>
                  <LogOut size={16} />
                </Button>
              </div>
            </SheetContent>
          </Sheet>
          <aside className="sidebar">
            <div className="side-top">
              <Logo />
            </div>
            <nav aria-label="주 메뉴">
              {nav.map((n) => (
                <Button
                  variant="ghost"
                  key={n.id}
                  className={`nav-item ${page === n.id || (page === "detail" && n.id === "repairs") ? "active" : ""}`}
                  onClick={() => {
                    if (n.id === "inspection") setKind("in")
                    go(n.id)
                  }}
                >
                  <n.icon size={19} />
                  {n.label}
                  {n.id === "repairs" && repairs.length > 0 && (
                    <span
                      className="nav-count"
                      title={`전체 ${repairs.length}건 · 진행 중 ${openCount}건`}
                      aria-label={`전체 ${repairs.length}건, 진행 중 ${openCount}건`}
                    >
                      {repairs.length}
                    </span>
                  )}
                </Button>
              ))}
            </nav>
            <div className="sidebar-bottom">
              {hasRoomAccess && (
                <div className="side-room">
                  <small>지금 보는 호실</small>
                  <strong>{room.name}</strong>
                  <span className="badges">
                    <Status>{room.status}</Status>
                    <Status>{room.connection}</Status>
                  </span>
                </div>
              )}
              <Button
                variant="ghost"
                className="profile"
                onClick={() => open("account")}
              >
                <span className="avatar">
                  {account?.name.slice(0, 1) || "나"}
                </span>
                <span>
                  <strong>{account?.name} 님</strong>
                  <small>
                    {hasRoomAccess
                      ? `이 호실에서 ${sideText(role)}`
                      : account?.email}
                  </small>
                </span>
                <LogOut size={16} />
              </Button>
            </div>
          </aside>
          <div className="main-shell">
            <header className="topbar">
              <div className="top-left">
                <Button
                  variant="ghost"
                  size="icon"
                  className="mobile-menu"
                  aria-label="메뉴 열기"
                  onClick={() => setMobile(true)}
                >
                  <Menu />
                </Button>
                <span className="breadcrumb">
                  내 공간 <ChevronRight size={13} />{" "}
                  <strong>
                    {page === "detail"
                      ? "하자 상세"
                      : page === "notifications"
                        ? "알림"
                        : nav.find((n) => n.id === page)?.label}
                  </strong>
                </span>
              </div>
              <div className="top-actions">
                {receivedInvitations.length > 0 && (
                  <Button variant="outline" onClick={() => go("connection")}>
                    받은 초대 {receivedInvitations.length}건
                  </Button>
                )}
                <Button
                  variant="ghost"
                  size="icon"
                  className="bell"
                  aria-label={`알림 ${unread}개`}
                  onClick={() => go("notifications")}
                >
                  <Bell />
                  {unread > 0 && <span>{unread}</span>}
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="avatar small"
                  aria-label="내 계정"
                  onClick={() => open("account")}
                >
                  {account?.name.slice(0, 1) || "나"}
                </Button>
              </div>
            </header>
            <main>
              {(page === "connection" || !hasRoomAccess) && (
                <ReceivedInvitations
                  rooms={receivedInvitations}
                  accountPhone={sessionPhone}
                  onOpen={openReceivedInvite}
                />
              )}
              {route.notFound ||
              (routeMissing && page !== "rooms" && page !== "notifications") ? (
                <NotFoundState
                  onHome={() =>
                    navigate((r) => ({
                      ...r,
                      roomId: accessibleRooms[0]?.id ?? "",
                      page: "home",
                    }))
                  }
                />
              ) : hasRoomAccess ? (
                <>
                  {page !== "detail" && (
                    <div className="page-heading">
                      <div>
                        <div className="eyebrow">
                          {page === "rooms"
                            ? `내 호실 ${accessibleRooms.length}곳`
                            : `${room.name} · ${room.status}`}
                        </div>
                        <h1>
                          {page === "home"
                            ? `${account?.name ?? ""}님, 안녕하세요`
                            : page === "notifications"
                              ? "내 알림"
                              : nav.find((n) => n.id === page)?.label}
                          {page === "home" && (
                            <span className="greeting-dot">.</span>
                          )}
                        </h1>
                        <p
                          className={
                            page === "home" ? "max-md:hidden" : undefined
                          }
                        >
                          {page === "home"
                            ? "답할 일과 진행 중인 기록을 먼저 보여 드려요."
                            : (pageIntro[page] ??
                              "사실과 합의를 구분하고, 서로의 행동을 기록으로 남겨요.")}
                        </p>
                      </div>
                      <div className="heading-actions">
                        <div className="room-select">
                          <Building2 size={16} />
                          <Choice
                            label="현재 호실 선택"
                            value={room.name}
                            values={accessibleRooms.map((r) => r.name)}
                            onChange={(v) => {
                              setRoomId(db.rooms.find((r) => r.name === v)!.id)
                              go(page)
                            }}
                          />
                        </div>
                        {room.status === "거주 중" &&
                          !readOnly &&
                          page === "repairs" && (
                            <Button onClick={() => open("repair")}>
                              <Plus />
                              하자 등록
                            </Button>
                          )}
                      </div>
                    </div>
                  )}
                  {readOnly && (
                    <Notice className="mb-5" icon={<ShieldCheck aria-hidden />}>
                      정산 완료 · 읽기 전용으로 보관 중이에요. 정산 완료 뒤
                      1년이 지나면 삭제돼요.
                    </Notice>
                  )}
                  {room.preJoinOf && (
                    <Notice className="mb-5">
                      합류 전 기록이에요. 같은 집의 상대 기록(
                      {db.rooms.find((x) => x.id === room.preJoinOf)?.name})에
                      합류해서 이 기록은 읽기만 할 수 있어요.
                    </Notice>
                  )}
                  {page === "home" &&
                    !readOnly &&
                    room.status === "거주 중" &&
                    !sessionOf(room, "in") &&
                    !repairs.length &&
                    !partPhotos.length && (
                      <div className="first-steps">
                        <div>
                          <strong>첫 할 일을 골라 주세요</strong>
                          <p className="muted">
                            입주 당시 모습을 남기거나, 지금 있는 하자를 바로
                            등록할 수 있어요.
                          </p>
                        </div>
                        <div className="first-steps-actions">
                          <Button
                            onClick={() => {
                              setKind("in")
                              go("inspection")
                            }}
                          >
                            <ClipboardCheck />
                            입주 점검 시작
                          </Button>
                          <Button
                            variant="outline"
                            onClick={() => open("repair")}
                          >
                            <Wrench />
                            하자 등록
                          </Button>
                        </div>
                      </div>
                    )}
                  {page === "home" && (
                    <HomeDashboard
                      role={role}
                      room={room}
                      rooms={accessibleRooms}
                      repairs={repairs}
                      events={events}
                      notices={notices}
                      moneyLeft={moneyLeft}
                      readOnly={readOnly}
                      nextStep={nextStep}
                      sideFor={(r) => sideOf(r, accountId)}
                      onOpenRepair={detail}
                      onOpenRoom={(id) => {
                        setRoomId(id)
                        go("home")
                      }}
                      onGo={(p) => {
                        if (p === "inspection") setKind("in")
                        go(p)
                      }}
                      onNewRepair={() => open("repair")}
                      onNewRoom={() => open("room")}
                      onOpenNotice={(n) => {
                        setRoomId(n.roomId)
                        if (n.repairId) detail(n.repairId)
                        else go("timeline")
                      }}
                      thumb={(r) => (
                        <PhotoGallery
                          title={r.title}
                          photos={r.photos}
                          art={r.art}
                          layout="compact"
                        />
                      )}
                      moneyNotice={(r) => (
                        <MoneyLeftNotice
                          r={r}
                          role={role}
                          readOnly={readOnly}
                          onRecord={(from, to, amount) => {
                            setMoneyPrefill({ from, to, amount })
                            detail(r.id)
                          }}
                        />
                      )}
                    />
                  )}
                  {page === "rooms" &&
                    (() => {
                      const roomCard = (r: Room) => {
                        const openRepairs = db.repairs.filter(
                          (x) => x.roomId === r.id && x.status !== "처리 완료",
                        ).length
                        return (
                          <Card
                            className={`svc-card svc-room-card ${r.id === roomId ? "is-current" : ""}`}
                            key={r.id}
                          >
                            <CardContent className="svc-card-body space-y-3">
                              <div className="svc-room-card-head">
                                <span className="svc-room-icon" aria-hidden>
                                  <Building2 size={20} />
                                </span>
                                <div className="min-w-0">
                                  <h2>{r.name}</h2>
                                  <p className="muted">{r.address}</p>
                                </div>
                              </div>
                              <div className="badges">
                                <Status>{r.status}</Status>
                                <Status>{r.connection}</Status>
                                {r.creator && (
                                  <Badge variant="outline">
                                    {r.creator === role
                                      ? `${sideText(role)} · 내가 만듦`
                                      : `${sideText(role)}`}
                                  </Badge>
                                )}
                              </div>
                              <dl className="svc-facts">
                                <div>
                                  <dt>입주일</dt>
                                  <dd>{r.since || "미입력"}</dd>
                                </div>
                                <div>
                                  <dt>진행 중 수리</dt>
                                  <dd>{openRepairs}건</dd>
                                </div>
                                <div>
                                  <dt>보증금</dt>
                                  <dd>
                                    {r.deposit === undefined
                                      ? "미입력"
                                      : won(r.deposit)}
                                  </dd>
                                </div>
                              </dl>
                              <p className="svc-next">
                                <ArrowRight size={14} aria-hidden />
                                {nextStep(r)}
                              </p>
                              <Button
                                className="w-full"
                                variant={
                                  r.id === roomId ? "default" : "outline"
                                }
                                onClick={() => {
                                  setRoomId(r.id)
                                  go("home")
                                }}
                              >
                                {r.id === roomId
                                  ? "선택한 호실 보기"
                                  : "호실 선택"}
                                <ArrowRight />
                              </Button>
                            </CardContent>
                          </Card>
                        )
                      }
                      const live = accessibleRooms.filter(
                          (r) => sideOf(r, accountId) === "임차인",
                        ),
                        own = accessibleRooms.filter(
                          (r) => sideOf(r, accountId) === "임대인",
                        )
                      // 새 호실 기록 만들기는 두 묶음(사는 집 · 관리하는 호실) 어느 쪽에도 속하지 않아서 목록 맨 위에 하나만 둬요.
                      return (
                        <div className="space-y-6">
                          <div className="rooms-bar">
                            <p className="muted">
                              내 호실 {accessibleRooms.length}곳
                            </p>
                            <Button onClick={() => open("room")}>
                              <Plus />새 호실 기록 만들기
                            </Button>
                          </div>
                          {live.length > 0 && (
                            <section>
                              <h2 className="room-group-title">
                                사는 집 <CountChip>{live.length}</CountChip>
                              </h2>
                              <div className="svc-room-grid">
                                {live.map(roomCard)}
                              </div>
                            </section>
                          )}
                          {own.length > 0 && (
                            <section>
                              <h2 className="room-group-title">
                                관리하는 호실{" "}
                                <CountChip>{own.length}</CountChip>
                              </h2>
                              <div className="svc-room-grid">
                                {own.map(roomCard)}
                              </div>
                            </section>
                          )}
                        </div>
                      )
                    })()}
                  {(page === "repairs" || page === "money") && (
                    <>
                      {/* 상태 탭 + 검색. 휴대폰: 탭은 화면 폭 안에서만 옆으로 밀어 보고(오른쪽 끝 흐림), 검색은 아래 전체 폭 */}
                      <div className="mb-[22px] flex min-w-0 flex-wrap items-center justify-between gap-5 max-[1000px]:flex-col max-[1000px]:items-stretch max-md:gap-3">
                        <Tabs
                          value={filter}
                          onValueChange={(v) => setFilter(String(v))}
                          className="w-full min-w-0 min-[1001px]:w-auto max-md:-mx-[18px] max-md:w-[calc(100%+36px)] max-md:overflow-x-auto max-md:px-[18px] max-md:py-0.5 max-md:[mask-image:linear-gradient(to_right,#000_calc(100%-24px),transparent)] max-md:[scrollbar-width:none] max-md:[&::-webkit-scrollbar]:hidden"
                        >
                          <TabsList className="flex h-auto w-auto flex-wrap items-center justify-start gap-2 rounded-none bg-transparent p-0 max-md:w-max max-md:flex-nowrap">
                            {(page === "money"
                              ? [
                                  "전체",
                                  "미정",
                                  "합의 대기",
                                  "이견",
                                  "합의됨",
                                  "비용 없음",
                                  "돈이 남은 건",
                                ]
                              : [
                                  "전체",
                                  "내 차례",
                                  "확인 전",
                                  "확인 완료",
                                  "처리 중",
                                  "처리 완료",
                                ]
                            ).map((v) => (
                              <TabsTrigger
                                key={v}
                                value={v}
                                data-chip
                                className="h-9 flex-none gap-1.5 rounded-full border border-line bg-white py-0 pr-3 pl-3.5 text-sm leading-none font-semibold whitespace-nowrap text-sub shadow-none hover:border-line-strong hover:text-ink data-active:border-brand data-active:bg-brand data-active:text-white group-data-[variant=default]/tabs-list:data-active:shadow-none"
                              >
                                {plainText(v)}
                                <span
                                  className={`inline-flex h-5 min-w-[22px] items-center justify-center rounded-full px-1.5 text-xs leading-none font-bold tabular-nums in-data-active:bg-white/20 in-data-active:text-white ${tabCount(v) ? "bg-chip text-sub" : "bg-[#f3f5f9] text-faint"}`}
                                  aria-hidden
                                >
                                  {tabCount(v)}
                                </span>
                              </TabsTrigger>
                            ))}
                          </TabsList>
                        </Tabs>
                        <div className="relative max-[1000px]:w-full">
                          <Search
                            size={16}
                            className="absolute top-3.5 left-2.5 text-[#9ba9bd]"
                          />
                          <Input
                            className="h-11 w-[220px] bg-white pl-8 text-base max-[1000px]:w-full"
                            aria-label="하자 기록 검색"
                            placeholder="제목 · 부위로 검색"
                            value={search}
                            onChange={(e) => setSearch(e.target.value)}
                          />
                        </div>
                      </div>
                      {/* 휴대폰에서는 카드 안쪽 여백을 줄여 목록 폭을 넓혀요(! : 아직 남은 .panel CSS보다 앞서게) */}
                      <Panel
                        className="max-md:[&>[data-slot=card-content]]:px-3.5! max-md:[&>[data-slot=card-header]]:px-3.5!"
                        title={
                          page === "money"
                            ? "수리 건별 비용 부담 현황"
                            : `수리 기록 전체 ${repairs.length}건 · 진행 중 ${openCount}건`
                        }
                      >
                        {page === "money" && (
                          <p className="mb-4 flex items-start gap-2 px-1 text-[13px] leading-[1.6] text-sub">
                            <Info size={16} aria-hidden />
                            지불한 사실, 부담하기로 한 합의, 수리 진행은 각각
                            따로 끝나요. 돈 얘기가 남아도 수리는 끝낼 수 있어요.
                          </p>
                        )}
                        {page === "money" &&
                          moneyLeft.length > 0 &&
                          filter !== "돈이 남은 건" && (
                            <button
                              type="button"
                              className="mb-4 w-full rounded-[12px] bg-warn-soft px-3.5 py-3 text-left text-sm leading-[1.6] text-warn-ink"
                              onClick={() => setFilter("돈이 남은 건")}
                            >
                              고쳤지만 돈이 남은 건이 {moneyLeft.length}건
                              있어요 · 모아 보기
                            </button>
                          )}
                        {(() => {
                          const q = search.trim()
                          const items = repairs.filter(
                            (r) =>
                              (!q ||
                                r.title.includes(q) ||
                                r.zone.includes(q)) &&
                              (filter === "전체" ||
                                (filter === "내 차례"
                                  ? Boolean(myTurn(r, role))
                                  : page === "money"
                                    ? filter === "돈이 남은 건"
                                      ? moneyLeft.some((m) => m.id === r.id)
                                      : (r.proposal?.status || "미정") ===
                                        filter
                                    : r.status === filter)),
                          )
                          return items.length ? (
                            <div className="flex flex-col gap-2">
                              {items.map((r) => (
                                <RepairListItem
                                  key={r.id}
                                  r={r}
                                  role={role}
                                  thumb={
                                    <PhotoGallery
                                      title={r.title}
                                      photos={r.photos}
                                      art={r.art}
                                      layout="compact"
                                    />
                                  }
                                  onOpen={() => detail(r.id)}
                                  extra={
                                    page === "money" && r.payments?.length ? (
                                      <Status>{`지불 기록 ${r.payments.length}건`}</Status>
                                    ) : undefined
                                  }
                                />
                              ))}
                            </div>
                          ) : (
                            <EmptyState
                              title={
                                q
                                  ? `‘${q}’에 맞는 기록이 없어요`
                                  : repairs.length
                                    ? "이 조건에 해당하는 기록이 없어요"
                                    : "아직 남긴 하자 기록이 없어요"
                              }
                              description={
                                repairs.length
                                  ? "다른 탭을 고르거나 검색어를 바꿔 보세요."
                                  : "고장이나 하자를 사진과 함께 남겨 두면 상대와 같은 기록을 볼 수 있어요."
                              }
                            />
                          )
                        })()}
                      </Panel>
                    </>
                  )}
                  {page === "detail" && repair && detailView(repair)}
                  {(page === "inspection" || page === "checkout") && (
                    <>
                      {(() => {
                        /* 점검 머리(입주·퇴거 같은 모양): 기준·상태 한 줄 + 4칸 진행 막대(화면 기본 틀 9장 '입주 점검 · 기준 · 잠김'). 퇴거는 단계 계산을 그대로 두고 거주 기간 상태·점검 시작일을 더 보여 줘요 */ const out =
                          page === "checkout"
                        const cur = out
                          ? room.status === "정산 완료"
                            ? 4
                            : room.status === "거주 종료"
                              ? 2
                              : session
                                ? 1
                                : 0
                          : moveIn === "잠김"
                            ? 4
                            : moveIn === "확인 요청"
                              ? 2
                              : 0
                        const steps = out
                          ? [
                              "퇴거 점검",
                              "거주 종료",
                              "정산 항목 · 보증금 확인",
                              "정산 완료",
                            ]
                          : [
                              "부위별 기록 작성",
                              "상대 확인 요청",
                              "확인 · 이견 정리",
                              "기준 기록 잠김",
                            ]
                        return (
                          <Card className="mb-5 min-w-0 gap-3 rounded-[18px] border border-line bg-white py-4 shadow-[0_1px_2px_#1720330a] ring-0">
                            <CardContent className="space-y-3 px-5 max-md:px-4">
                              <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                                <strong className="text-[17px] font-bold text-ink">
                                  {out ? "퇴거 점검" : "입주 점검"}
                                </strong>
                                {out && <StatusTag>{room.status}</StatusTag>}
                                <StatusTag>
                                  {moveIn === "없음"
                                    ? `${kindLabel(sessKind)} 점검 시작 전`
                                    : `${kindLabel(sessKind)} 점검 · ${session!.author} 기준 기록 · ${moveIn}`}
                                </StatusTag>
                                {inspectionLocked && (
                                  <StatusTag>
                                    {session?.noDefects
                                      ? "점검 완료 · 잠김 · 하자 기록 없음"
                                      : "점검 완료 · 잠김"}
                                  </StatusTag>
                                )}
                                {room.since && (
                                  <span className="text-[13px] text-sub">
                                    입주일 {room.since}
                                  </span>
                                )}
                                {out && session?.startedAt && (
                                  <span className="text-[13px] text-sub">
                                    · 퇴거 점검 시작 {session.startedAt}
                                  </span>
                                )}
                                {!out && session?.lateOn && (
                                  <StatusTag>{`입주 후 등록(${session.lateOn})`}</StatusTag>
                                )}
                              </div>
                              {!out && session?.lateOn && (
                                <p className="m-0 text-[13px] leading-[1.6] text-sub">
                                  입주 후에 등록된 기록이에요. 입주 당시 상태와
                                  다를 수 있어요.
                                </p>
                              )}
                              <ol
                                className="m-0 flex list-none gap-1.5 p-0"
                                aria-label={
                                  out
                                    ? "퇴거 · 정산 진행 단계"
                                    : "입주 점검 진행 단계"
                                }
                              >
                                {steps.map((x, n) => (
                                  <li
                                    key={x}
                                    aria-current={
                                      n === cur ? "step" : undefined
                                    }
                                    className="flex min-w-0 flex-1 flex-col gap-1"
                                  >
                                    <span
                                      className={
                                        n < cur || n === cur
                                          ? "h-1.5 rounded-full bg-brand"
                                          : "h-1.5 rounded-full bg-chip"
                                      }
                                      aria-hidden
                                    />
                                    <span
                                      className={
                                        n === cur
                                          ? "truncate text-xs font-bold text-brand"
                                          : n < cur
                                            ? "truncate text-xs text-ink"
                                            : "truncate text-xs text-faint"
                                      }
                                    >
                                      {x}
                                    </span>
                                  </li>
                                ))}
                              </ol>
                            </CardContent>
                          </Card>
                        )
                      })()}
                      {/* 입주 점검 시작 · 입주일이 비어 있을 때(화면 기본 틀 9-1, INSP-2b): 입주일을 받아 저장과 시작을 한 번에. 받기 전에는 점검을 시작하지 않아요 */}
                      {needMoveInDate && !readOnly && (
                        <Card className="mb-5 min-w-0 gap-3 rounded-[18px] border border-turn-line bg-turn-soft py-4 shadow-none ring-0">
                          <CardContent className="px-5 max-md:px-4">
                            <form
                              className="flex flex-col gap-2"
                              onSubmit={(e) => {
                                e.preventDefault()
                                const date = String(
                                  new FormData(e.currentTarget).get(
                                    "moveInDate",
                                  ) || "",
                                )
                                if (!date) {
                                  toast("입주일을 골라 주세요.")
                                  return
                                }
                                commit(`입주일 ${date} · 입주 점검 시작`, (d) =>
                                  startInspection(d, roomId, "in", role, date),
                                )
                              }}
                            >
                              <strong className="text-[17px] font-bold text-ink">
                                입주 점검 시작
                              </strong>
                              <label
                                htmlFor="move-in-date"
                                className="text-[15px] text-ink"
                              >
                                이 집에 들어온 날을 알려 주세요
                              </label>
                              <div className="flex gap-2 max-sm:flex-col">
                                <Input
                                  id="move-in-date"
                                  name="moveInDate"
                                  type="date"
                                  required
                                  className="h-11 bg-white text-base sm:max-w-56"
                                />
                                <Button
                                  type="submit"
                                  size="lg"
                                  className="h-11 sm:px-6"
                                >
                                  다음
                                </Button>
                              </div>
                              <small className="text-[13px] text-sub">
                                계약서의 입주일이에요. 입주일을 알려 주셔야
                                점검을 시작할 수 있어요. 입주일보다 늦게
                                시작하면 ‘입주 후 등록’이 붙어요.
                              </small>
                            </form>
                          </CardContent>
                        </Card>
                      )}
                      <Panel
                        title={
                          page === "inspection"
                            ? "입주 당시 상태 기록"
                            : "퇴거 점검 · 같은 부위 비교"
                        }
                        action={
                          inspectionLocked ? (
                            <div className="actions">
                              <Status>
                                {session?.noDefects
                                  ? "점검 완료 · 잠김 · 하자 기록 없음"
                                  : "점검 완료 · 잠김"}
                              </Status>
                              {!readOnly && (
                                <Button
                                  variant="outline"
                                  onClick={() => setMissingOpen(true)}
                                >
                                  빠진 하자 추가
                                </Button>
                              )}
                              {!readOnly && missingHint}
                            </div>
                          ) : moveIn === "확인 요청" && !moveInMine ? (
                            !readOnly && (
                              <div className="flex flex-col items-end gap-1">
                                <Button
                                  variant="outline"
                                  onClick={() => setMissingOpen(true)}
                                >
                                  빠진 하자 추가 요청
                                </Button>
                                {missingHint}
                              </div>
                            )
                          ) : (
                            !readOnly &&
                            moveInMine &&
                            !needMoveInDate && (
                              <Button
                                variant="outline"
                                onClick={() => {
                                  setKind(page === "inspection" ? "in" : "out")
                                  open("inspection")
                                }}
                              >
                                <Plus />
                                {page === "inspection"
                                  ? "입주 당시 기록 남기기"
                                  : "점검 기록 추가"}
                              </Button>
                            )
                          )
                        }
                      >
                        {/* 상태 글은 위 점검 머리에 있어요. 여기에는 버튼과 안내만 */}
                        {
                          <div className="mb-4 flex flex-wrap items-center gap-2 empty:hidden [&>small]:basis-full [&>small]:rounded-[12px] [&>small]:bg-soft [&>small]:px-3.5 [&>small]:py-2.5">
                            {!readOnly &&
                              ((moveIn === "없음" && !needMoveInDate) ||
                                (moveIn === "작성 중" && moveInMine)) && (
                                <Button onClick={requestMoveInCheck}>
                                  점검 확인 요청
                                </Button>
                              )}
                            {!readOnly &&
                              room.connection === "연결 전" &&
                              ((moveIn === "없음" && !needMoveInDate) ||
                                (moveIn === "작성 중" && moveInMine)) && (
                                <Button
                                  variant={
                                    page === "inspection" ? "link" : "outline"
                                  }
                                  onClick={() =>
                                    commit(
                                      `${kindLabel(sessKind)} 점검 혼자 마치기 · 혼자 남긴 기록(상대 없음)`,
                                      (d) =>
                                        finishSoloInspection(
                                          d,
                                          roomId,
                                          sessKind,
                                          role,
                                        ),
                                    )
                                  }
                                >
                                  혼자 마치기
                                </Button>
                              )}
                            {session?.soloLocked && (
                              <small className="muted">
                                상대 없이 혼자 마친 기록이에요. 상대가 연결되면
                                확인 요청이 한 번에 가요.
                              </small>
                            )}
                            {moveIn === "작성 중" && !moveInMine && (
                              <small className="muted">
                                {sideText(session!.author)}이 기준 기록을 쓰고
                                있어요.
                              </small>
                            )}
                            {((moveIn === "없음" && !needMoveInDate) ||
                              (moveIn === "작성 중" && moveInMine)) && (
                              <small className="muted">
                                하자와 부위 사진을 계속 등록할 수 있어요. 하자를
                                등록해도 확인 요청은 가지 않고, ‘점검 확인
                                요청’을 눌러야 점검 전체가 상대에게 보여요.
                                {moveIn === "없음"
                                  ? " 먼저 작성한 쪽이 기준 기록을 써요."
                                  : ""}
                              </small>
                            )}
                            {!readOnly &&
                              moveIn === "확인 요청" &&
                              session?.noDefects &&
                              !moveInMine && (
                                <Button
                                  onClick={() =>
                                    commit(
                                      `${kindLabel(sessKind)} 점검 · 하자 기록 없음 확인`,
                                      (d) =>
                                        confirmNoDefects(
                                          d,
                                          roomId,
                                          sessKind,
                                          role,
                                        ),
                                    )
                                  }
                                >
                                  하자 기록 없음 확인
                                </Button>
                              )}
                            {moveIn === "확인 요청" && session?.noDefects && (
                              <small className="muted">
                                {moveInMine
                                  ? "하자 기록 없음으로 확인을 요청했어요. 상대가 확인하면 잠겨요."
                                  : "상대가 하자 기록 없음으로 확인을 요청했어요. 확인하면 점검이 잠겨요."}
                              </small>
                            )}
                            {moveIn === "확인 요청" && !session?.noDefects && (
                              <small className="muted">
                                {moveInMine
                                  ? "상대가 항목마다 확인·이견을 남기면 점검이 잠겨요. 하자는 이제 고치기로 덧붙이고, 부위 사진은 계속 올릴 수 있어요."
                                  : "항목마다 확인·이견을 남기거나 ‘남은 항목 모두 확인’을 눌러 주세요. 빠진 하자는 [빠진 하자 추가]로 덧붙여요."}
                              </small>
                            )}
                          </div>
                        }
                        <p className="muted mb-5">
                          하자는 상대가 확인하고, 부위 사진은 등록 시각만
                          남아요. 적지 않은 부위는 ‘기록 없음’이에요.
                        </p>
                        {page === "inspection" ? (
                          <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,340px),1fr))] items-start gap-4">
                            {partPhotos
                              .filter((p) => p.kind === "in")
                              .map((p) => (
                                <div className={inspectionCard} key={p.id}>
                                  {photoContent(p)}
                                </div>
                              ))}
                            {visibleInspections
                              .filter(
                                (i) => i.roomId === roomId && i.kind === "in",
                              )
                              .map((i) => (
                                <div className={inspectionCard} key={i.id}>
                                  {inspectionContent(i)}
                                </div>
                              ))}
                            {/* 적지 않은 부위는 '기록 없음'(문서 9장 [반드시]) — 목록 끝 얇은 한 줄 */}
                            <div className="col-span-full flex flex-wrap items-center gap-x-2 gap-y-0.5 rounded-[12px] border border-dashed border-[#dce5f1] px-4 py-3 text-sm text-[#91a6c3]">
                              <Camera size={16} aria-hidden />
                              <strong className="font-semibold">
                                그 밖의 부위
                              </strong>
                              <span>입주 점검에 기록 없음</span>
                            </div>
                          </div>
                        ) : (
                          <div className="flex flex-col gap-2">
                            {/* 기록이 있는 부위마다 입주 · 수리 · 퇴거를 한 줄로(기획서 283·473, S-14). 적지 않은 칸은 '기록 없음'(상태와 규칙 H) */}
                            {(() => {
                              const zones = Array.from(
                                new Set([
                                  ...visibleInspections
                                    .filter((i) => i.roomId === roomId)
                                    .map((i) => i.zone),
                                  ...partPhotos.map((p) => p.zone),
                                  ...repairs.map((x) => x.zone),
                                ]),
                              )
                              if (!zones.length)
                                return (
                                  <EmptyState
                                    title="아직 나란히 볼 기록이 없어요"
                                    description="입주 점검·수리·퇴거 점검에 기록한 부위가 여기에 한 줄씩 나란히 보여요."
                                  />
                                )
                              const records = (k: "in" | "out", z: string) => [
                                ...partPhotos
                                  .filter((p) => p.kind === k && p.zone === z)
                                  .map((p) => (
                                    <div key={p.id}>{photoContent(p)}</div>
                                  )),
                                ...visibleInspections
                                  .filter(
                                    (i) =>
                                      i.roomId === roomId &&
                                      i.kind === k &&
                                      i.zone === z,
                                  )
                                  .map((i) => (
                                    <div key={i.id}>{inspectionContent(i)}</div>
                                  )),
                              ]
                              return zones.map((z) => (
                                <ComparisonRow
                                  key={z}
                                  zone={z}
                                  columns={[
                                    {
                                      key: "in",
                                      label: "입주",
                                      hint: "입주 기존 하자",
                                      items: records("in", z),
                                      empty: "입주 점검에 기록 없음",
                                    },
                                    {
                                      key: "repair",
                                      label: "수리",
                                      hint: "거주 중 하자",
                                      items: repairs
                                        .filter((x) => x.zone === z)
                                        .map((x) => (
                                          <RepairListItem
                                            key={x.id}
                                            r={x}
                                            role={role}
                                            onOpen={() => detail(x.id)}
                                          />
                                        )),
                                      empty: "수리 기록 없음",
                                    },
                                    {
                                      key: "out",
                                      label: "퇴거",
                                      items: records("out", z),
                                      empty: "퇴거 점검에 기록 없음",
                                    },
                                  ]}
                                />
                              ))
                            })()}
                          </div>
                        )}

                        {!readOnly && (
                          <div className="actions mt-5">
                            <ConfirmAllItems
                              items={inspectionItems}
                              db={db}
                              role={role}
                              onAct={inspectionAct}
                            />
                            {inspectionLocked && (
                              <small className="muted">
                                잠긴 점검에는 고치기만 덧붙여요. 빠진 하자는
                                ‘빠진 하자 추가’, 기존 항목의 고치기와 삭제
                                요청은 각 항목 카드에서 해요.
                              </small>
                            )}
                          </div>
                        )}
                        <MissingDefectDialog
                          open={missingOpen}
                          kind={page === "inspection" ? "in" : "out"}
                          db={db}
                          room={room}
                          role={role}
                          onClose={() => setMissingOpen(false)}
                          onAct={inspectionAct}
                        />

                        {page === "checkout" && room.status === "거주 중" && (
                          <div className="actions mt-5">
                            <Button
                              disabled={role === "임대인" && room.endRequested}
                              onClick={() =>
                                commit(
                                  role === "임차인"
                                    ? "퇴거 점검 마침 · 거주 종료"
                                    : "거주 종료 확인 요청",
                                  (d) => {
                                    const r = d.rooms.find(
                                      (r) => r.id === roomId,
                                    )!
                                    if (
                                      role === "임차인" ||
                                      r.connection === "연결 전"
                                    )
                                      r.status = "거주 종료"
                                    else r.endRequested = true
                                  },
                                )
                              }
                            >
                              {role === "임차인"
                                ? "점검 마침 · 이사했어요"
                                : room.endRequested
                                  ? "세입자의 답을 기다려요"
                                  : "점검 마침 · 거주 종료 요청"}
                            </Button>
                            {room.endRequested && role === "임차인" && (
                              <>
                                <Notice>
                                  집주인이 이사했다고 남겼어요. 맞으면 거주
                                  종료가 돼요.
                                </Notice>
                                <Button
                                  onClick={() =>
                                    commit(
                                      "거주 종료 요청 확인 · 맞아요",
                                      (d) => {
                                        const r = d.rooms.find(
                                          (r) => r.id === roomId,
                                        )!
                                        r.status = "거주 종료"
                                        r.endWait = undefined
                                      },
                                    )
                                  }
                                >
                                  맞아요
                                </Button>
                                <Button
                                  variant="outline"
                                  onClick={() =>
                                    commit(
                                      "거주 종료 요청 · 아직 살고 있어요",
                                      (d) => {
                                        const r = d.rooms.find(
                                          (r) => r.id === roomId,
                                        )!
                                        r.endRequested = false
                                        r.endWait = undefined
                                      },
                                    )
                                  }
                                >
                                  아직 살고 있어요
                                </Button>
                              </>
                            )}
                            {room.endRequested && role === "임대인" && (
                              <small className="muted">
                                {room.endWait === "미확인"
                                  ? "세입자 확인 없음 · 72시간 동안 답이 오지 않았어요"
                                  : `세입자의 답을 기다려요${room.endWait ? " · 다시 알렸어요" : ""}`}
                              </small>
                            )}
                            {room.endRequested &&
                              role === "임대인" &&
                              room.endWait === "미확인" && (
                                <Button
                                  onClick={() =>
                                    commit(
                                      "거주 종료 요청 적용 · 세입자 미확인",
                                      (d) => {
                                        const r = d.rooms.find(
                                          (r) => r.id === roomId,
                                        )!
                                        r.status = "거주 종료"
                                        r.endUnconfirmed = true
                                      },
                                    )
                                  }
                                >
                                  혼자 거주 종료
                                </Button>
                              )}
                            <small className="muted">
                              거주 종료와 점검 항목 확인·정산 완료는 별개예요.
                            </small>
                          </div>
                        )}
                      </Panel>
                      {page === "checkout" &&
                        room.status === "거주 종료" &&
                        room.connection !== "연결됨" &&
                        room.creator === role && (
                          <Panel className="mb-5" title="정산 초대 링크">
                            <p className="muted mb-3">
                              거주 중에 연결되지 않은 상대에게 정산 확인을
                              요청해요. 링크에는 금액·항목 없이 ‘퇴거 정산 확인
                              요청이 있어요’와 가입하기만 보여요.
                            </p>
                            {activeSettlementLink(room) && (
                              <p className="muted mb-2">
                                보낸 정산 초대 링크 ·{" "}
                                {new Date(
                                  activeSettlementLink(room)!.issuedAt,
                                ).toLocaleString("ko-KR", {
                                  timeZone: "Asia/Seoul",
                                })}{" "}
                                · 받는 번호 {room.phone}
                                {(room.settlementLinks?.length ?? 0) > 1
                                  ? ` · 이전 링크 ${room.settlementLinks!.length - 1}건 무효`
                                  : ""}
                              </p>
                            )}
                            {!readOnly && (
                              <div className="actions">
                                <Input
                                  className="max-w-56"
                                  type="tel"
                                  aria-label="정산 초대 받을 번호"
                                  value={settlePhone || room.phone || ""}
                                  onChange={(e) =>
                                    setSettlePhone(e.target.value)
                                  }
                                  placeholder="상대 휴대폰 번호"
                                />
                                <Button
                                  variant="outline"
                                  onClick={() => {
                                    const phone = (
                                      settlePhone ||
                                      room.phone ||
                                      ""
                                    ).replace(/\D/g, "")
                                    if (!/^010\d{8}$/.test(phone)) {
                                      toast(
                                        "010으로 시작하는 11자리 번호를 입력해 주세요.",
                                      )
                                      return
                                    }
                                    if (
                                      sessionPhone &&
                                      phone === sessionPhone
                                    ) {
                                      toast("내 번호는 상대로 넣을 수 없어요.")
                                      return
                                    }
                                    commit(
                                      "정산 초대 링크 발송 요청 · 퇴거 정산 확인 요청",
                                      (d) =>
                                        sendSettlementLink(
                                          d.rooms.find((x) => x.id === roomId)!,
                                          role,
                                          phone,
                                        ),
                                    )
                                    setSettlePhone("")
                                  }}
                                >
                                  {activeSettlementLink(room)
                                    ? "번호 확인 · 다시 보내기"
                                    : "정산 확인 요청 보내기"}
                                </Button>
                              </div>
                            )}
                          </Panel>
                        )}
                      {page === "checkout" && room.endUnconfirmed && (
                        <Notice className="mb-5">
                          거주 종료 · 세입자 확인 없음 · 72시간 동안 답이 오지
                          않아 집주인 쪽이 혼자 남겼어요.
                        </Notice>
                      )}
                      {page === "checkout" && (
                        <RoomFinancials
                          room={room}
                          role={role}
                          items={settlements}
                          onAction={commit}
                          onAdd={() => open("settlement")}
                          candidates={
                            <RepairCandidates
                              repairs={repairs}
                              settlements={settlements}
                              role={role}
                              readOnly={readOnly}
                              onOpen={detail}
                              onAct={commit}
                            />
                          }
                        />
                      )}
                    </>
                  )}
                  {page === "connection" && (
                    <Panel title="호실의 두 사람">
                      <div className="connection-flow">
                        <div className="person-card">
                          <span className="avatar">나</span>
                          <h3>{sideText(role)} (나)</h3>
                          <Status>연결됨</Status>
                          <p>인증한 휴대폰 · {maskPhone(sessionPhone)}</p>
                        </div>
                        <div className="connection-line">
                          <Link2 size={26} />
                          <Status>{room.connection}</Status>
                        </div>
                        <div className="person-card">
                          <span className="avatar counterpart">상대</span>
                          <h3>{sideText(other(role))}</h3>
                          <Status>{room.connection}</Status>
                          <p>
                            {room.connection === "연결됨"
                              ? "휴대폰 인증 후 연결된 계정"
                              : "초대 후 가입·연결 확인이 필요해요"}
                          </p>
                        </div>
                      </div>
                      <p className="muted my-5">
                        휴대폰 번호 인증으로 연결했다는 사실만 남아요.
                        집주인인지는 따로 확인하지 않아요.
                      </p>
                      {!readOnly && (
                        <div className="actions">
                          {room.connection !== "연결됨" && (
                            <Button onClick={() => open("invite")}>
                              <Link2 />
                              {room.connection === "거절됨"
                                ? "번호 변경 후 다시 초대"
                                : room.connection === "초대됨"
                                  ? "번호 확인 · 다시 초대"
                                  : "상대 초대하기"}
                            </Button>
                          )}
                          {canEditNumberOnlyPhone(room, role) && (
                            <Button
                              variant="outline"
                              onClick={() => open("phone")}
                            >
                              상대 번호 수정 · 초대 전
                            </Button>
                          )}
                          {room.connection === "거절됨" &&
                            room.creator === role && (
                              <p className="muted">
                                상대가 ‘제 집이 아니에요’로 거절했어요. 같은
                                번호로는 다시 초대할 수 없으니 번호를 변경한 뒤
                                초대해 주세요.
                              </p>
                            )}
                          {(room.inviteLinks?.length ?? 0) > 0 && (
                            <p className="muted">
                              보낸 초대 링크 · 링크로 봤어요{" "}
                              {activeInviteLinks(room).reduce(
                                (n, l) => n + (l.views || 0),
                                0,
                              )}
                              회
                            </p>
                          )}
                          {room.connection === "초대됨" && (
                            <p className="muted">
                              초대 번호 {maskPhone(room.phone || "")} · 상대가
                              이 번호로 가입·인증한 계정에서 연결을 수락하면
                              연결돼요.
                            </p>
                          )}
                          {room.connection === "연결됨" && (
                            <Button
                              variant="outline"
                              onClick={() => open("room")}
                            >
                              <Plus />
                              상대 없이 새 호실 시작하기
                            </Button>
                          )}
                        </div>
                      )}
                    </Panel>
                  )}
                  {page === "timeline" && (
                    <Panel
                      title={
                        <>
                          행동 기록 <CountChip>{events.length}</CountChip>
                        </>
                      }
                    >
                      <Timeline events={events} />
                    </Panel>
                  )}
                  {page === "notifications" && (
                    <NotificationsPage
                      accountId={accountId ?? ""}
                      rooms={db.rooms}
                      version={db.notices}
                      onOpen={(n) => {
                        setRoomId(n.roomId)
                        if (n.repairId) detail(n.repairId)
                        else go("timeline")
                      }}
                    />
                  )}
                  <footer className="main-footer">
                    <span>
                      HomeStamp <span>·</span> 입주부터 퇴거까지, 함께 확인한 집
                      기록
                    </span>
                    <span>HomeStamp는 누가 낼지 판단하지 않아요</span>
                  </footer>
                </>
              ) : !routeRoom ? (
                <Welcome
                  name={account?.name ?? ""}
                  invitations={receivedInvitations.length}
                  onCreate={() => open("room")}
                />
              ) : (
                <Panel title="호실 연결이 필요해요">
                  <p>
                    이 계정은 아직 해당 호실에 연결되지 않았어요. 초대만으로는
                    전체 기록을 볼 수 없습니다.
                  </p>
                  {room.connection === "초대됨" &&
                    !canReceiveInvitation(room, role, sessionPhone) && (
                      <p className="muted">
                        이 계정의 인증 번호로 받은 초대가 아니에요. 초대받은
                        번호로 가입한 계정에서 연결을 수락할 수 있어요.
                      </p>
                    )}
                  {room.connection === "거절됨" && (
                    <Notice>
                      거절됨 · 초대한 상대가 번호를 확인하고 다시 초대해야
                      연결할 수 있어요.
                    </Notice>
                  )}
                  <div className="actions mt-4">
                    {canReceiveInvitation(room, role, sessionPhone) && (
                      <Button
                        onClick={() => {
                          setInviteRoomId(room.id)
                          if (isSettlementInvitation(room)) {
                            setSharedKind("settlement")
                            setGuest(true)
                            return
                          }
                          act(
                            "링크 열람",
                            (d) => recordLinkView(d, room.id),
                            true,
                            room.id,
                            true,
                          )
                          const s = room.shareTarget
                          if (s) showShared(s.kind, s.id)
                          else {
                            setSharedKind("invite")
                            setGuest(true)
                          }
                        }}
                      >
                        공유받은 기록 보기
                      </Button>
                    )}
                    {accessibleRooms.length > 0 && (
                      <Button
                        variant="outline"
                        onClick={() =>
                          navigate((r) => ({
                            ...r,
                            roomId: accessibleRooms[0].id,
                            page: "rooms",
                          }))
                        }
                      >
                        연결된 내 호실 목록
                      </Button>
                    )}
                    <Button variant="outline" onClick={() => open("room")}>
                      새 호실 기록 만들기
                    </Button>
                  </div>
                </Panel>
              )}
            </main>
          </div>
        </div>
      )}
      <Dialog
        open={modal !== null}
        onOpenChange={(v) => {
          if (!v) setModal(null)
        }}
      >
        <DialogContent className="app-dialog sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {modal === "account"
                ? "내 계정"
                : modal === "connect"
                  ? "호실 연결 확인"
                  : modal === "repair"
                    ? "새 하자 기록"
                    : modal === "room"
                      ? "호실 기록 만들기"
                      : modal === "inspection"
                        ? kind === "in"
                          ? "입주 당시 기록 남기기"
                          : "퇴거 점검 기록"
                        : modal === "invite"
                          ? `이 호실 기록을 ${other(role) === "임대인" ? "집주인" : "세입자"}에게 보여 줄까요?`
                          : modal === "phone"
                            ? "상대 번호 수정 · 초대 전"
                            : modal === "proposal"
                              ? "비용 부담 제안"
                              : modal === "settlement"
                                ? "정산 항목 추가"
                                : modal === "payment"
                                  ? "지불 사실 남기기"
                                  : modal === "dispute"
                                    ? "이견 보내기"
                                    : modal === "after"
                                      ? "수리 후 사진 기록"
                                      : modal === "reconfirm"
                                        ? "설명·사진 보충 후 다시 확인 요청"
                                        : "글 수정"}
            </DialogTitle>
            <DialogDescription>
              {basicPhotoMode
                ? "입주 당시 부위 사진을 남기고, 퇴거할 때 같은 부위와 비교해요."
                : modal === "account"
                  ? "세입자 쪽·집주인 쪽은 호실마다 연결로 정해져요."
                  : modal === "room"
                    ? "한 거주 기간의 입주 점검, 수리, 정산이 이 기록 하나에 모여요."
                    : "사실을 기록하고, 상대의 응답을 기다려요. 원래 기록은 그대로 남습니다."}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={submit} className="modal-form">
            {modal === "connect" && (
              <>
                <p className="connect-ask">
                  {phoneMatched
                    ? `${sharedRoom?.address.split(" ").at(-1) ?? ""} ${sharedRoom?.name ?? ""}`
                    : "초대받은 호실"}
                  에 <strong>{sideText(role)}</strong>으로 연결할까요?
                </p>
                {!sharedRoom?.phone && (
                  <p className="muted">
                    이 기록에는 등록된 초대 번호가 없습니다. 초대한 쪽에 번호를
                    확인해 다시 초대해 달라고 요청해 주세요.
                  </p>
                )}
                {phoneMatched ? (
                  <ConsentList
                    rows={[
                      [
                        "함께 볼 사람",
                        `${sharedRoom?.creator === "임대인" ? "집주인" : "세입자"} (초대한 분)`,
                      ],
                      [
                        "함께 보는 것",
                        "이 호실의 사진·설명·행동 기록 (이름은 가려서)",
                      ],
                      ["목적", "집 상태와 수리 기록을 함께 확인하려고"],
                      ["기간", "정산 완료 뒤 1년까지"],
                      [
                        "동의하지 않으면",
                        "연결되지 않고, 받은 기록만 볼 수 있어요",
                      ],
                    ]}
                  />
                ) : (
                  <p className="form-error">
                    초대받은 번호와 내 인증 번호가 달라요. 초대한 분께 번호
                    확인을 부탁해 주세요.
                  </p>
                )}
                {sharedRoom && myOwnRooms.length > 0 && (
                  <div className="field">
                    <label>같은 집에 대해 내가 만든 기록</label>
                    <Choice
                      label="같은 집의 내 기록"
                      value={
                        myOwnRooms.find((x) => x.id === sameHouseId)?.name ||
                        "없음 · 바로 연결"
                      }
                      values={[
                        "없음 · 바로 연결",
                        ...myOwnRooms.map((x) => x.name),
                      ]}
                      onChange={(v) => {
                        setSameHouseId(
                          myOwnRooms.find((x) => x.name === v)?.id || "",
                        )
                        setJoinChoice("")
                        setFormError("")
                      }}
                    />
                    <small className="muted">
                      같은 집을 기록한 내 호실이 있으면 골라 주세요. 없으면 바로
                      연결해요.
                    </small>
                  </div>
                )}
                {sharedRoom && sameHouseRooms.length > 0 && (
                  <div className="info-box">
                    <div className="space-y-2">
                      <strong>같은 집의 기록이 두 개예요</strong>
                      {[sharedRoom, ...sameHouseRooms].map((x) => (
                        <p key={x.id}>
                          {x.id === sharedRoom.id ? "상대 기록" : "내 기록"} ·{" "}
                          {x.name} · 기록 {recordCount(x.id)}건
                          {recordCount(x.id) ===
                          Math.max(
                            ...[sharedRoom, ...sameHouseRooms].map((y) =>
                              recordCount(y.id),
                            ),
                          )
                            ? " · 추천(기록이 많은 쪽)"
                            : ""}
                        </p>
                      ))}
                      <div className="actions">
                        <Button
                          type="button"
                          variant={
                            joinChoice === "join" ? "default" : "outline"
                          }
                          onClick={() => setJoinChoice("join")}
                        >
                          ① 상대 기록에 합류
                        </Button>
                        <Button
                          type="button"
                          variant={joinChoice === "own" ? "default" : "outline"}
                          onClick={() => setJoinChoice("own")}
                        >
                          ② 거절하고 내 기록으로 초대
                        </Button>
                      </div>
                      {joinChoice === "join" && (
                        <p className="muted">
                          연결을 수락하면 내 기록은 ‘합류 전 기록’으로 남고
                          읽기만 할 수 있어요.
                        </p>
                      )}
                      {joinChoice === "own" && (
                        <Button
                          type="button"
                          variant="outline"
                          onClick={() => {
                            const own = sameHouseRooms[0]
                            act(
                              "같은 집 기록 · 거절하고 내 기록으로 상대 초대 선택",
                              (d) => {
                                declineInvitation(
                                  d.rooms.find((x) => x.id === sharedRoom.id)!,
                                  role,
                                  sessionPhone,
                                )
                              },
                              true,
                              sharedRoom.id,
                              true,
                            )
                            setGuest(false)
                            replaceUrl(null, "", "/")
                            navigate((r) => ({
                              ...r,
                              page: "connection",
                              roomId: own.id,
                            }))
                            setInvitePhone(own.phone || "")
                            setChangePhone(false)
                            setInviteTarget(undefined)
                            setFormError("")
                            setModal("invite")
                          }}
                        >
                          거절하고 내 기록({sameHouseRooms[0].name})으로
                          초대하기
                        </Button>
                      )}
                    </div>
                  </div>
                )}
                {sharedRoom &&
                  canReceiveInvitation(sharedRoom, role, sessionPhone) && (
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={rejectReceivedInvite}
                    >
                      제 집이 아니에요
                    </Button>
                  )}
              </>
            )}

            {modal === "inspection" && kind === "in" && (
              <>
                {partField}
                <p className="muted">
                  퇴거 사진과 비교하도록 구역과 부위를 선택해 주세요.
                </p>
                {isLateMoveInInspection(room) && (
                  <Notice>
                    입주 후 등록 · 입주일({room.since})보다 늦게 남기는
                    기록이에요. 입주 당시 상태와 다를 수 있어요.
                  </Notice>
                )}
                {fileField}
                <div className="field">
                  <label htmlFor="photo-memo">사진 메모 · 선택</label>
                  <Textarea
                    id="photo-memo"
                    name="photoMemo"
                    maxLength={2000}
                    placeholder="사진과 함께 남길 내용이 있으면 적어 주세요."
                  />
                </div>
                <label className="consent">
                  <Checkbox
                    checked={hasInspectionDefect}
                    disabled={inspectionLocked || moveInDefectClosed}
                    onCheckedChange={(checked) => {
                      setHasInspectionDefect(Boolean(checked))
                      setShareChoice(null)
                      setFormError("")
                    }}
                  />
                  입주 당시 발견한 하자가 있어요
                </label>
                {hasInspectionDefect ? (
                  <>
                    <div className="field">
                      <label htmlFor="title">기록 제목</label>
                      <Input
                        id="title"
                        name="title"
                        placeholder="하자 제목을 적어 주세요"
                        maxLength={80}
                        required
                      />
                    </div>
                    <div className="field">
                      <label htmlFor="description">상태 설명</label>
                      <Textarea
                        id="description"
                        name="description"
                        placeholder="입주 당시 발견한 하자를 구체적으로 남겨 주세요."
                        maxLength={2000}
                        required
                      />
                    </div>
                    <p className="muted">
                      기존 하자 기록으로 저장해요. 입주 점검 작성을 마친 뒤
                      ‘점검 확인 요청’을 누르면 점검 전체가 상대에게 보여요.
                    </p>
                  </>
                ) : (
                  <p className="muted">
                    부위 사진으로 저장해요. 하자 기록이 아니라서 상대 확인 없이
                    등록 시각만 남아요.
                  </p>
                )}
                {(inspectionLocked || moveInDefectClosed) && (
                  <p className="muted">
                    {inspectionLocked
                      ? "하자 점검은 잠겼어요. 하자 변경은 고치기로 덧붙여 주세요."
                      : "확인 요청을 보낸 뒤라 하자는 [빠진 하자 추가]로 덧붙여요. 부위 사진은 올릴 수 있어요."}
                  </p>
                )}
              </>
            )}
            {(modal === "repair" ||
              (modal === "inspection" && kind === "out")) && (
              <>
                {modal === "repair" && recurrenceOf && (
                  <Notice>
                    이전 수리 건 「
                    {db.repairs.find((x) => x.id === recurrenceOf)?.title}」
                    이후 재발로 새 수리 건을 등록해요. 이전 건은 다시 열지 않고
                    참조만 해요.
                  </Notice>
                )}
                {partField}
                <div className="field">
                  <label htmlFor="title">기록 제목</label>
                  <Input
                    id="title"
                    name="title"
                    placeholder="예: 욕실 천장에 물 자국이 있어요"
                    maxLength={80}
                    required
                  />
                </div>
                <div className="field">
                  <label htmlFor="description">상태 설명</label>
                  <Textarea
                    id="description"
                    name="description"
                    placeholder="직접 확인한 상태를 구체적으로 남겨 주세요."
                    maxLength={2000}
                    required
                  />
                </div>
                {fileField}
                {modal === "inspection" &&
                  kind === "in" &&
                  isLateMoveInInspection(room) && (
                    <Notice>
                      입주 후 등록 · 입주 당시 상태와 다를 수 있어요.
                    </Notice>
                  )}
                {modal === "repair" && room.connection === "연결 전" && (
                  <div className="space-y-3">
                    <p className="muted">
                      상대에게 이 기록을 알릴까요? 기본 선택은 없어요.
                    </p>
                    {room.phone && (
                      <p className="muted">
                        이 호실에 등록한 상대 연락처 {room.phone}로 초대를
                        준비해요.
                      </p>
                    )}
                    <div className="actions">
                      <Button
                        type="button"
                        variant={shareChoice === "now" ? "default" : "outline"}
                        onClick={() => setShareChoice("now")}
                      >
                        알리기 · 번호와 공유 확인
                      </Button>
                      <Button
                        type="button"
                        variant={
                          shareChoice === "later" ? "default" : "outline"
                        }
                        onClick={() => setShareChoice("later")}
                      >
                        나중에 · 혼자 기록
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )}
            {modal === "room" && (
              <>
                {/* S-05: 꼭 적을 것(내 쪽·주소·별칭) 위, 선택(입주일·상대 번호)은 아래 묶음. 휴대폰은 한 줄에 한 칸 */}
                <div className="field">
                  <label id="room-side-label">내 쪽</label>
                  <RadioGroup
                    aria-labelledby="room-side-label"
                    value={roomSide}
                    onValueChange={(v) => setRoomSide(v as Role)}
                    className="grid grid-cols-2 gap-2"
                  >
                    {(["임차인", "임대인"] as const).map((s) => (
                      <label
                        key={s}
                        className={`flex min-h-[72px] min-w-0 cursor-pointer items-start gap-2.5 rounded-[12px] border p-3 transition-colors ${roomSide === s ? "border-brand bg-brand-soft ring-1 ring-brand ring-inset" : "border-line bg-white hover:border-line-strong"}`}
                      >
                        <RadioGroupItem
                          value={s}
                          className="mt-0.5"
                          aria-label={
                            s === "임차인"
                              ? "이 집에 살아요 (임차인)"
                              : "이 집을 빌려줬어요 (임대인)"
                          }
                        />
                        <span className="min-w-0">
                          <strong className="block text-[15px] leading-snug font-semibold text-ink">
                            {s === "임차인"
                              ? "이 집에 살아요"
                              : "이 집을 빌려줬어요"}
                          </strong>
                          <small className="block text-[13px] text-sub">
                            {s}
                          </small>
                        </span>
                      </label>
                    ))}
                  </RadioGroup>
                  <small className="muted">
                    처음 알리거나 초대하기 전에는 호실 설정에서 고칠 수 있어요.
                  </small>
                </div>
                <div className="field">
                  <label
                    htmlFor={API_MODE === "http" ? "address-search" : "address"}
                  >
                    주소 · 시·구·동까지
                  </label>
                  {/* 서버 연결 시: 도로명주소 검색으로 시·도·시·군·구·읍·면·동만 채워요. 개발 환경(서버 없음): 가짜 결과 없이 직접 입력 */}
                  {API_MODE === "http" ? (
                    <AddressSearch name="address" inputId="address-search" />
                  ) : (
                    <>
                      <Notice tone="amber">
                        개발 환경 · 서버가 연결되지 않아 주소 검색을 쓸 수
                        없어요. 시·구·동을 직접 적어 주세요.
                      </Notice>
                      <Input
                        id="address"
                        name="address"
                        placeholder="예: 서울시 마포구 연남동"
                        required
                      />
                    </>
                  )}
                  <small className="muted">
                    번지·건물명은 적지 않아요. 공유 링크에는 동까지만 보여요.
                  </small>
                </div>
                <div className="field">
                  <label htmlFor="title">호실 별칭</label>
                  <Input
                    id="title"
                    name="title"
                    placeholder="예: 302호"
                    maxLength={30}
                    required
                  />
                  <small className="muted">
                    상대가 문자로 알아보는 이름이에요. 처음 알린 뒤에는 바꿀 수
                    없어요.
                  </small>
                </div>
                <section
                  aria-labelledby="room-optional"
                  className="flex flex-col gap-4 rounded-[14px] border border-line bg-soft p-4 max-sm:p-3.5"
                >
                  <p
                    id="room-optional"
                    className="m-0 text-[13px] font-bold text-sub"
                  >
                    선택 · 나중에 넣어도 돼요
                  </p>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="field">
                      <label htmlFor="date">입주일 · 선택</label>
                      <Input
                        id="date"
                        name="date"
                        type="date"
                        className="bg-white"
                      />
                      <small className="muted">
                        비워 두면 입주 점검을 시작할 때 물어요.
                      </small>
                    </div>
                    <div className="field">
                      <label htmlFor="room-phone">
                        {roomSide === "임차인" ? "집주인" : "세입자"} 휴대폰
                        번호 · 선택
                      </label>
                      <Input
                        id="room-phone"
                        name="phone"
                        type="tel"
                        inputMode="numeric"
                        autoComplete="off"
                        className="bg-white"
                        placeholder="초대할 때 넣어도 돼요"
                      />
                      <small className="muted">
                        번호를 넣어도 바로 초대되지 않아요. 초대할 때 이 번호를
                        써요.
                      </small>
                    </div>
                  </div>
                </section>
              </>
            )}
            {modal === "phone" && (
              <>
                <Notice>
                  지금 저장된 번호: {maskPhone(room.phone || "")} · 아직 초대
                  전이라 번호만 바뀌고, 초대 문자나 링크는 만들어지지 않아요.
                </Notice>
                <div className="field">
                  <label htmlFor="edit-phone">새 상대 휴대폰 번호</label>
                  <Input
                    id="edit-phone"
                    name="phone"
                    type="tel"
                    placeholder="010으로 시작하는 번호"
                    required
                  />
                </div>
              </>
            )}
            {modal === "invite" && (
              <>
                {canChangeInvitePhone(room, role) && (
                  <label className="consent">
                    <Checkbox
                      checked={changePhone}
                      onCheckedChange={(v) => {
                        setChangePhone(Boolean(v))
                        setInvitePhone(v ? "" : room.phone || "")
                        setFormError("")
                      }}
                    />
                    번호를 바꿔서 다시 초대할게요
                  </label>
                )}
                {room.connection === "거절됨" && !changePhone && (
                  <Notice>
                    상대가 ‘제 집이 아니에요’로 거절한 번호예요. 번호를 바꿔서
                    초대해 주세요.
                  </Notice>
                )}
                {changePhone && (
                  <p className="muted">
                    번호를 바꾸면 이전 초대 링크는 더 이상 열리지 않아요.
                  </p>
                )}
                <div className="field">
                  <label htmlFor="phone">
                    {changePhone
                      ? "새 휴대폰 번호"
                      : `${other(role) === "임대인" ? "집주인" : "세입자"} 휴대폰 번호`}
                  </label>
                  <Input
                    id="phone"
                    name="phone"
                    type="tel"
                    inputMode="numeric"
                    value={
                      changePhone ? invitePhone : room.phone || invitePhone
                    }
                    readOnly={Boolean(room.phone) && !changePhone}
                    onChange={(e) => setInvitePhone(e.target.value)}
                    placeholder="01012345678"
                    required
                  />
                </div>
                <ConsentList
                  rows={[
                    [
                      "보여 줄 사람",
                      `${other(role) === "임대인" ? "집주인" : "세입자"} · ${maskPhone((changePhone ? invitePhone : room.phone || invitePhone) || "") || "번호 입력 후"}`,
                    ],
                    [
                      "보여 주는 것",
                      "이 호실의 사진·설명·행동 기록 (내 이름은 가려서)",
                    ],
                    ["목적", "집 상태와 수리 기록을 함께 확인하려고"],
                    ["기간", "정산 완료 뒤 1년까지"],
                    ["동의하지 않으면", "나만 보는 기록으로 남아요"],
                  ]}
                />
                <p className="muted">언제든 공유를 그만둘 수 있어요.</p>
                {API_MODE === "mock" && (
                  <Notice tone="amber">
                    개발 환경 · 서버가 연결되지 않아 초대 문자는 보내지 않아요.
                  </Notice>
                )}
              </>
            )}
            {modal === "proposal" && (
              <>
                <div className="field">
                  <label htmlFor="amount">제안 금액 (원)</label>
                  <MoneyInput
                    id="amount"
                    name="amount"
                    defaultValue={repair?.proposal?.amount || 65000}
                    required
                  />
                </div>
                <div className="field">
                  <label>부담할 쪽</label>
                  <Choice
                    value={payer}
                    values={["임대인", "임차인", "나눠서"]}
                    onChange={setPayer}
                    label="부담할 쪽"
                  />
                </div>
                <div className="field">
                  {payer === "나눠서" && (
                    <>
                      <label htmlFor="tenantAmount">
                        임차인 부담 금액 (원) · 나머지는 임대인 부담
                      </label>
                      <MoneyInput
                        id="tenantAmount"
                        name="tenantAmount"
                        defaultValue="32500"
                        required
                      />
                    </>
                  )}
                </div>
                <div className="field">
                  <label htmlFor="proposal-reason">제안·다른 안 사유</label>
                  <Textarea
                    id="proposal-reason"
                    name="proposalReason"
                    required={counterProposal}
                  />
                </div>
                <Notice>
                  상대가 같은 안에 동의해야 합의됩니다. 0원 제안에 동의하면
                  ‘비용 없음’으로 표시해요.
                </Notice>
              </>
            )}
            {modal === "settlement" && (
              <>
                <div className="field">
                  <label htmlFor="title">정산 항목</label>
                  <Input
                    id="title"
                    name="title"
                    placeholder="예: 퇴거 월 관리비"
                    required
                  />
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label htmlFor="amount">금액 (원)</label>
                    <MoneyInput id="amount" name="amount" required />
                  </div>
                  <div className="field">
                    <label>계산 방향</label>
                    <Choice
                      value={direction}
                      values={["차감", "가산"]}
                      onChange={setDirection}
                      label="계산 방향"
                    />
                  </div>
                </div>
                <div className="field">
                  <label htmlFor="description">제안 사유</label>
                  <Textarea id="description" name="description" required />
                </div>
                <div className="field">
                  <label>원상복구 부위 연결 · 선택</label>
                  <Choice
                    label="원상복구 부위"
                    value={settlementZone}
                    values={[
                      "연결 안 함",
                      ...Array.from(
                        new Set([
                          ...db.inspections
                            .filter((i) => i.roomId === roomId)
                            .map((i) => i.zone),
                          ...db.partPhotos
                            .filter((p) => p.roomId === roomId)
                            .map((p) => p.zone),
                          ...repairs.map((x) => x.zone),
                        ]),
                      ),
                    ]}
                    onChange={setSettlementZone}
                  />
                </div>
                <Notice>
                  상대가 동의하기 전에는 계산한 금액에 반영하지 않아요.
                </Notice>
              </>
            )}
            {modal === "payment" && (
              <>
                <div className="field">
                  <label>지불 종류</label>
                  <Choice
                    label="지불 종류"
                    value={paymentKind}
                    values={["업체 지불", "당사자 간 상환"]}
                    onChange={setPaymentKind}
                  />
                </div>
                {paymentKind === "당사자 간 상환" && (
                  <div className="field">
                    <label>기록 방향</label>
                    <Choice
                      label="기록 방향"
                      value={paymentDirection}
                      values={["보냈어요", "받았어요"]}
                      onChange={setPaymentDirection}
                    />
                  </div>
                )}
                <div className="field">
                  <label htmlFor="amount">지불·상환 금액 (원)</label>
                  <MoneyInput id="amount" name="amount" required />
                </div>
                <Notice>
                  {paymentKind === "업체 지불"
                    ? role + " → 업체"
                    : paymentDirection === "받았어요"
                      ? other(role) + " → " + role
                      : role + " → " + other(role)}
                  의 지불 사실을 남깁니다. 상대 확인 전까지 ‘한쪽 기록’이며
                  순지출에 반영됩니다. 실제 송금·결제를 수행하지 않습니다.
                </Notice>
              </>
            )}
            {modal === "after" && (
              <div className="field">
                <label htmlFor="after-title">수리 후 기록 제목</label>
                <Input
                  id="after-title"
                  name="title"
                  defaultValue="수리 후 상태"
                  required
                />
              </div>
            )}
            {(modal === "dispute" ||
              modal === "after" ||
              modal === "reconfirm") && (
              <>
                <div className="field">
                  <label htmlFor="description">
                    {modal === "dispute" ? "이견 사유 · 필수" : "보충 설명"}
                  </label>
                  <Textarea
                    id="description"
                    name="description"
                    placeholder={
                      modal === "dispute"
                        ? "다르게 확인한 내용과 이유를 적어 주세요."
                        : "이전 기록에 덧붙일 내용을 적어 주세요."
                    }
                    required
                    maxLength={2000}
                  />
                </div>
                {(modal === "after" || modal === "reconfirm") && fileField}
                {modal === "dispute" && target.type === "money" && (
                  <Notice>
                    이견을 보낸 뒤 ‘새 부담 제안’으로 다른 안을 제안할 수
                    있어요.
                  </Notice>
                )}
              </>
            )}
            {formError && (
              <p className="form-error" role="alert">
                {formError}
              </p>
            )}
            {modal === "account" && account && (
              <div className="space-y-4">
                <dl className="svc-facts">
                  <div>
                    <dt>이름</dt>
                    <dd>{account.name}</dd>
                  </div>
                  <div>
                    <dt>이메일</dt>
                    <dd>{account.email}</dd>
                  </div>
                  <div>
                    <dt>인증한 휴대폰</dt>
                    <dd>{maskPhone(account.phone)}</dd>
                  </div>
                </dl>
                <p className="muted">
                  연결된 호실 {accessibleRooms.length}곳 · 받은 초대{" "}
                  {receivedInvitations.length}건
                </p>
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  onClick={() => {
                    setModal(null)
                    onLogout()
                  }}
                >
                  <LogOut />
                  로그아웃
                </Button>
              </div>
            )}
            {modal !== "account" && (
              <div className="modal-footer">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setModal(null)}
                >
                  {modal === "invite"
                    ? "나만 보기"
                    : modal === "connect"
                      ? "연결하지 않기"
                      : "취소"}
                </Button>
                <Button
                  type="submit"
                  disabled={modal === "connect" && !canConnect}
                >
                  {modal === "connect"
                    ? "동의하고 연결하기"
                    : modal === "room"
                      ? "만들기"
                      : modal === "invite"
                        ? "동의하고 보여 주기"
                        : modal === "phone"
                          ? "번호 저장"
                          : modal === "proposal" || modal === "settlement"
                            ? "제안 보내기"
                            : modal === "note"
                              ? "수정 반영"
                              : "기록 남기기"}
                  <ArrowRight />
                </Button>
              </div>
            )}
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={Boolean(pending)}
        onOpenChange={(v) => {
          if (!v) setPending(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>기록을 남길까요?</DialogTitle>
            <DialogDescription>{pending?.text}</DialogDescription>
          </DialogHeader>
          <p className="muted">
            거주 종료 뒤에는 새 수리 건을 추가할 수 없어요. 정산 완료 뒤에는
            모든 기록이 읽기 전용으로 보존됩니다. 이견과 이전 기록은 그대로
            남아요.
          </p>
          <div className="actions">
            <Button variant="outline" onClick={() => setPending(null)}>
              취소
            </Button>
            <Button
              onClick={() => {
                if (pending) {
                  act(pending.text, pending.update)
                  setPending(null)
                  toast("행동 기록에 추가했어요.")
                }
              }}
            >
              확인하고 기록
            </Button>
          </div>
        </DialogContent>
      </Dialog>
      {message && (
        <div className="toast" role="status">
          <CircleCheck size={19} />
          {message}
          <Button
            variant="ghost"
            size="icon-xs"
            aria-label="알림 닫기"
            onClick={() => setMessage("")}
          >
            <X />
          </Button>
        </div>
      )}
    </>
  )
}

// 첫 화면(S-03 빈 화면): 호실 기록이 없을 때는 만들기 하나만 크게
function Welcome({
  name,
  invitations,
  onCreate,
}: {
  name: string
  invitations: number
  onCreate: () => void
}) {
  return (
    <div className="welcome">
      <Card className="svc-card">
        <CardContent className="svc-card-body welcome-body">
          <div>
            <p className="eyebrow">시작하기</p>
            <h1 className="welcome-title">{name}님, 환영해요</h1>
            <p className="muted mt-2">
              호실 기록을 만들면 입주 점검, 거주 중 수리, 퇴거 정산이 그 기록
              하나에 이어져요. 상대가 없어도 혼자 시작할 수 있어요.
            </p>
          </div>
          <ol className="welcome-steps">
            {[
              [
                "호실 기록 만들기",
                "이 집에 살아요(임차인) 또는 빌려줬어요(임대인)를 골라요.",
              ],
              [
                "입주 점검",
                "부위별 사진을 남기고 상대 확인을 받아 기준 기록으로 잠가요.",
              ],
              [
                "하자 · 수리",
                "하자를 사진과 함께 알리고 수리와 비용 합의를 따로 정리해요.",
              ],
              [
                "퇴거 · 정산",
                "같은 부위 사진을 비교하고 합의한 항목으로 보증금을 정산해요.",
              ],
            ].map(([t, d], i) => (
              <li key={t}>
                <span aria-hidden>{i + 1}</span>
                <div>
                  <strong>{t}</strong>
                  <small>{d}</small>
                </div>
              </li>
            ))}
          </ol>
          <div className="welcome-cta">
            <Button size="lg" className="w-full sm:w-auto" onClick={onCreate}>
              <Plus />
              호실 기록 만들기
            </Button>
          </div>
          {invitations > 0 && (
            <Notice>
              받은 초대가 {invitations}건 있어요. 위의 ‘받은 초대’에서 연결할 수
              있어요.
            </Notice>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
// 기록 공유 동의 다섯 칸(S-07·S-08): 보여 줄 사람 · 보여 주는 것 · 목적 · 기간 · 동의하지 않으면
function ConsentList({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="consent-list">
      {rows.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  )
}
