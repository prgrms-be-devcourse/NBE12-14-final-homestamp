import { useEffect, useRef, useState, useSyncExternalStore } from "react"
import { clearTakenAt } from "@/lib/photos"
import { Plus, ShieldCheck } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog"
import { Panel } from "@/components/common/blocks"
import {
  type Role,
  type Repair,
  type DemoState,
  type Inspection,
  type Room,
  type PartPhoto,
  type RecordTarget,
} from "@/data/demo"
import { useRouter, type Page } from "@/lib/router"
import { roomOptions } from "@/lib/room-options"
import {
  ErrorState,
  LoadingState,
  NotFoundState,
} from "@/components/common/states"
import { NotificationsPage } from "@/pages/notifications-page"
import { RepairDetailPage } from "@/pages/repair-detail-page"
import { InspectionPage } from "@/pages/inspection-page"
import { ConnectionPage } from "@/pages/connection-page"
import { TimelinePage } from "@/pages/timeline-page"
import { HomePage } from "@/pages/home-page"
import { CounterpartNotice } from "@/components/common/counterpart-notice"
import { RoomsPage } from "@/pages/rooms-page"
import { RepairsPage } from "@/pages/repairs-page"
import { SharePage, type SharedKind } from "@/pages/share-page"
import { LinkViewPage } from "@/features/link/link-view-page"
import { isLinkViewPath } from "@/api/link-view"
import { hasAction, type RoomAction } from "@/api/actions"
import { ApiProvider } from "@/api"
import { useApi } from "@/api/hooks"
import { repairStub } from "@/api/http-repair-view"
import { errorMessage, shouldReload } from "@/api/errors"
import { createApi } from "@/api/create-api"
import {
  inspectionIdIn,
  noRequest,
  notInSpec,
  post,
  type ActionOptions,
  type RecordRequest,
} from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { RoomEndCard } from "@/features/room/room-end-card"
import { moveOutEndsNow, roomEndView } from "@/lib/room-end"
import { createAuth, type Account, type AuthApi } from "@/api/auth"
import { AuthScreen } from "@/features/auth/auth-screen"
import { TermsReconfirmDialog } from "@/features/member/terms-reconfirm-dialog"
import { PhotoGallery } from "@/features/photo/photo-gallery"
import { ReceivedInvitations } from "@/features/room/received-invitations"
import { RoomDownload } from "@/features/download/room-download"
import { PartPhotoContent } from "@/features/photo/part-photo-content"
import { maskOf } from "@/lib/photo-privacy"
import { MoneyLeftNotice } from "@/features/payment/money-left"
import { RepairAfterForm } from "@/features/repair/repair-after-form"
import { PhoneForm } from "@/features/room/phone-form"
import { ConnectForm } from "@/features/room/connect-form"
import { InviteForm } from "@/features/room/invite-form"
import { SettlementItemForm } from "@/features/settlement/settlement-item-form"
import { RoomForm } from "@/features/room/room-form"
import { InspectionItemForm } from "@/features/inspection/inspection-item-form"
import {
  RepairCreateForm,
  type RepairPrefill,
} from "@/features/repair/repair-create-form"
import { AccountPanel } from "@/features/member/account-panel"
import { syncRepairSettlements } from "@/lib/repair-rules"
import {
  inspectionStatus,
  sessionOf,
  kindLabel,
  requestInspectionAll,
} from "@/lib/inspection-rules"
import {
  canAccessRoom,
  isPreJoin,
  canSeeInspection,
  declineInvitation,
  canReceiveInvitation,
  isSettlementInvitation,
  recordLinkView,
  sideOf,
  inviteeSide,
} from "@/lib/workflows"
import { myTurn } from "@/lib/turn"
import { hasUnsettledMoney } from "@/lib/service-rules"
import "./App.css"
import "./readability.css"
import "./service.css"
import { sideText } from "@/lib/words"
import { Notice } from "@/components/common/notice"
import { Welcome } from "@/features/room/welcome"
import { SideMenu } from "@/components/common/side-menu"
import { AppTopbar } from "@/components/common/app-topbar"
import { PageHeading } from "@/components/common/page-heading"
import { MobileTabbar } from "@/components/common/mobile-tabbar"
import { SubTabs } from "@/components/common/sub-tabs"
import { Toast } from "@/components/common/toast"
import {
  activeTabOf,
  pageAfterRoomChange,
  pageIntro,
  pageTitleOf,
  subTabsByGroup,
} from "@/components/common/app-nav"

// 브라우저가 주소 변경을 막는 환경에서도 화면 동작은 계속되게 합니다.
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
  | "settlement"
  | "account"
  | "after"
  | "connect"
  | "phone"
  | null

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
const isShareLink = () => {
  const q = new URLSearchParams(window.location.search)
  return q.has("share") || q.has("inspection") || q.has("photo")
}

// 앱 진입점: 로그인 전에는 로그인·가입, 로그인 뒤에는 내 호실. 공유 링크(?share= 등)는 가입 전에도 그 기록 한 건만 읽어요.
// 기록은 api(목업 또는 서버, api/create-api가 고름)로, 계정·인증은 api/auth(VITE_API_MODE=http면 서버)로 처리해요.
export default function App() {
  const [api] = useState(() => createApi())
  const [auth] = useState(() => createAuth())
  const [account, setAccount] = useState<Account | null>(null)
  const [restoring, setRestoring] = useState(true)
  // 새로고침 뒤 로그인 복원이 연결 오류·서버 오류로 실패했을 때의 안내. 로그아웃하지 않고 다시 시도하게 해요(#191)
  const [restoreError, setRestoreError] = useState<string>()
  const [restoreTry, setRestoreTry] = useState(0)
  const [authOpen, setAuthOpen] = useState(false)
  // 문자 링크(/l#토큰, S-16)로 들어왔는지. 가입·로그인을 마치거나 [받은 초대 보기]를 누르면 링크 화면을 닫고 홈으로 가요
  const [linkOpen, setLinkOpen] = useState(isLinkViewPath)
  const [authMode, setAuthMode] = useState<"signup" | "login">("signup")
  // 쓰는 중에 세션이 끝나 로그인 화면으로 보낼 때의 안내(LOGIN_REQUIRED·REFRESH_REUSED 문구)
  const [ended, setEnded] = useState<string>()
  useEffect(() => {
    let alive = true
    auth.restore().then(
      (a) => {
        if (!alive) return
        setAccount(a)
        setRestoring(false)
      },
      (e: unknown) => {
        if (alive) setRestoreError(errorMessage(e))
      },
    )
    return () => {
      alive = false
    }
  }, [auth, restoreTry])
  // 토큰 만료 뒤 재발급도 실패하면 로그인 화면으로 보내요. 주소는 그대로 두어 다시 로그인하면 보던 화면으로 돌아와요
  useEffect(
    () =>
      auth.onSessionEnd((e) => {
        setAccount(null)
        setAuthOpen(true)
        setEnded(e.message)
      }),
    [auth],
  )
  const link = isShareLink()
  // 링크 화면을 떠나면 주소에서 토큰(#)을 지워요. 연결은 토큰이 아니라 인증한 번호로 받은 초대에서 해요(06 4-3 invitations)
  const leaveLink = () => {
    replaceUrl(null, "", "/")
    setLinkOpen(false)
  }
  const retryRestore = () => {
    setRestoreError(undefined)
    setRestoreTry((n) => n + 1)
  }
  // 문자 링크는 로그인과 상관없는 공개 열람이라 로그인 복원을 기다리지 않고, 복원이 실패해도 열어요(#411 리뷰).
  // 복원이 끝나기 전에는 회원인지 몰라 가입·로그인 대신 확인 중이나 다시 불러오기를 보여요(누르면 복원 화면에 갇히지 않게)
  if (linkOpen && !authOpen)
    return (
      <LinkViewPage
        member={Boolean(account)}
        session={
          restoring ? { error: restoreError, onRetry: retryRestore } : undefined
        }
        onSignup={() => {
          setAuthMode("signup")
          setAuthOpen(true)
        }}
        onLogin={() => {
          setAuthMode("login")
          setAuthOpen(true)
        }}
        onContinue={leaveLink}
      />
    )
  if (restoring)
    return (
      <div className="auth-loading">
        {restoreError ? (
          <ErrorState message={restoreError} onRetry={retryRestore} />
        ) : (
          <LoadingState label="HomeStamp를 여는 중이에요" />
        )}
      </div>
    )
  if (!account && (!(link || linkOpen) || authOpen))
    return (
      <AuthScreen
        key={linkOpen ? `link-${authMode}` : "auth"}
        auth={auth}
        initialMode={
          ended ? "login" : linkOpen ? authMode : link ? "signup" : "login"
        }
        onDone={(a) => {
          setAccount(a)
          setAuthOpen(false)
          setEnded(undefined)
          if (linkOpen) leaveLink()
        }}
        onBack={
          link || linkOpen
            ? () => {
                setAuthOpen(false)
                setEnded(undefined)
              }
            : undefined
        }
        notice={
          ended ??
          (linkOpen
            ? "링크로 받은 기록에 답하거나 호실에 연결하려면 가입한 뒤, 초대받은 번호로 인증해 주세요."
            : link
              ? "공유받은 기록에 응답하려면 가입한 뒤, 초대받은 번호로 호실에 연결해 주세요."
              : undefined)
        }
      />
    )
  return (
    <ApiProvider api={api} accountId={account?.id}>
      <HomeStampApp
        key={account?.id ?? "guest"}
        account={account}
        auth={auth}
        onAccountChange={setAccount}
        onRequireAuth={() => setAuthOpen(true)}
        // 로그아웃이 서버에서 끝났을 때만 로그인 화면으로 가요. 실패하면 오류를 내 계정 창에 알리고 로그인 상태를 지켜요
        onLogout={() =>
          auth.logout().then(() => {
            replaceUrl(null, "", "/")
            setAccount(null)
            // 만료된 세션을 로그아웃하다 재발급이 401이면 세션 끝 안내가 함께 오지만, 사람이 고른 로그아웃이라 안내하지 않아요
            setEnded(undefined)
          })
        }
      />
    </ApiProvider>
  )
}

function HomeStampApp({
  account,
  auth,
  onAccountChange,
  onRequireAuth,
  onLogout,
}: {
  account: Account | null
  auth: AuthApi
  onAccountChange: (a: Account) => void
  onRequireAuth: () => void
  onLogout: () => Promise<void>
}) {
  const query = new URLSearchParams(window.location.search)
  const accountId = account?.id
  // 바뀐 약관 다시 확인(CONS-4a). 이 화면은 계정마다 새로 그려져(key) 로그인·복원 직후 확인할 문구가 있으면 먼저 열어요(03 6-2)
  const [termsOpen, setTermsOpen] = useState(() =>
    Boolean(account?.pendingAcknowledgements.length),
  )
  // 상태를 바꾸는 요청이 TERMS_UPDATE_REQUIRED로 거절되면 확인할 문구를 다시 받아 확인 창을 열어요.
  // 거절된 요청은 그 화면이 오류로 안내하고, 확인한 뒤에도 다시 보내지 않아요(사람이 다시 눌러요)
  useEffect(
    () =>
      auth.onTermsRequired(() => {
        auth.reload().then(
          (a) => {
            onAccountChange(a)
            if (a.pendingAcknowledgements.length) setTermsOpen(true)
          },
          () => undefined,
        )
      }),
    [auth, onAccountChange],
  )
  // 계정이 바뀌면 앞 계정이 고른 사진의 촬영 시각을 비워요(lib/photos, #371 리뷰)
  useEffect(() => clearTakenAt(), [accountId])
  const api = useApi()
  // 공용 입력 창이 수리 건을 바꾸면 상세 화면이 다시 불러오게 올리는 번호
  const [repairTick, setRepairTick] = useState(0)
  // 화면·호실·수리 건은 주소(/rooms/:roomId/...)에 둡니다. 뒤로 가기·새로고침·주소 공유가 그대로 동작해요.
  const [route, navigate] = useRouter({
    page: "home",
    roomId: "",
    repairId: query.get("share") || "",
  })
  const { page, repairId } = route
  // 화면이 읽는 호실 기록은 기록 API에서 받아요(목업은 저장소, HTTP는 서버에서 받아 둔 것)
  const db = useSyncExternalStore(
    api.records.subscribe,
    api.records.getSnapshot,
  )
  useEffect(() => {
    if (accountId)
      api.records.load({ accountId }).catch((e) => toast(errorMessage(e)))
  }, [api, accountId])
  // 호실에 들어가면 그 호실 상세(입주일·availableActions)와 수리 건 목록을 새로 받아요(GET /api/rooms/{id}, /repair-cases).
  // 같은 호실 안에서는 수리 건 목록·홈에 들어갈 때만 다시 받아, 상세에서 행동한 뒤 돌아오면 바뀐 상태가 보여요.
  // 수리 상세·돈·점검 화면은 자기 데이터를 따로 받거나 아직 목록을 쓰지 않아 다시 받지 않아요(#293 리뷰). 목업은 할 일이 없어요
  const listing = page === "repairs" || page === "home"
  const inRoom = page !== "rooms" && page !== "notifications"
  const loadedRoom = useRef("")
  useEffect(() => {
    // 호실 밖(내 호실 목록·알림)으로 나가면 비워, 같은 호실에 다시 들어올 때(상세로 바로 들어와도) 새로 받아요.
    // 라우터는 호실 밖 화면에서도 앞서 본 roomId를 이어 두므로(지금 보는 호실) roomId만으로는 알 수 없어요
    if (!accountId || !route.roomId || !inRoom) {
      loadedRoom.current = ""
      return
    }
    const key = `${accountId}:${route.roomId}`
    if (loadedRoom.current === key && !listing) return
    loadedRoom.current = key
    api.records
      .loadRoom({ accountId }, route.roomId)
      .catch((e) => toast(errorMessage(e)))
  }, [api, accountId, route.roomId, listing, inRoom])
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
  const [sharedKind, setSharedKind] = useState<SharedKind>(
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
  // 재발 등록: 양측 확인으로 끝난 수리 건을 참조하는 새 수리 건(상태와 규칙 D 재발)
  const [repairPrefill, setRepairPrefill] = useState<RepairPrefill>()
  const [moneyPrefill, setMoneyPrefill] = useState<{
    from: Role
    to: Role
    amount: number
  }>()
  // 지금 열린 입력 창(입력 상태는 창 부품마다 따로 가져요)
  const [modal, setModal] = useState<Modal>(null)
  const [message, setMessage] = useState("")
  const [filter, setFilter] = useState("전체")
  const [search, setSearch] = useState("")
  // 점검 창이 입주(in)·퇴거(out) 중 어느 쪽인지. 화면 이동과 점검 창이 같이 써요
  const [kind, setKind] = useState<"in" | "out">("in")
  const [pending, setPending] = useState<{
    request: RecordRequest
    text: string
    update: (d: DemoState) => void
  } | null>(null)
  // 없는 호실 주소면 첫 호실 화면 대신 '찾을 수 없는 기록'을 보여 줘요(남의 호실도 같은 응답, 명세 4-3).
  const routeRoom = db.rooms.find((r) => r.id === roomId)
  const room = routeRoom ?? NO_ROOM
  const repairListed = db.repairs.some(
    (r) => r.id === repairId && r.roomId === roomId,
  )
  // http 모드는 목록을 받기 전(주소로 바로 연 상세)이나 목록을 다시 받기 전(방금 등록한 건)에도 상세 주소로 열고,
  // 상세 화면이 서버에서 받아요(#179, #290). 남의 수리 건·없는 건이면 상세 화면이 서버의 404를 받아 '찾을 수 없음'을 보여요.
  // 빼면 그 두 경우에 '찾을 수 없음'이 잠깐 떴다가 바뀌어서 남겨요(#364). 그동안 '내 차례'는 waiting 없이 상태로 셉니다(lib/turn)
  const serverOnlyRepair =
    api.mode === "http" &&
    page === "detail" &&
    Boolean(routeRoom) &&
    repairId !== "" &&
    !repairListed
  const routeMissing =
    Boolean(route.notFound) ||
    (route.roomId !== "" && !routeRoom) ||
    (page === "detail" && !repairListed && !serverOnlyRepair)
  const repair =
    db.repairs.find((r) => r.id === repairId) ??
    (serverOnlyRepair ? repairStub(repairId, roomId) : undefined)
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
  // 기록 바꾸기는 모두 기록 API로 보내요. request는 06 4장의 주소, 나머지는 목업이 서버 대신 쓰는 값이에요.
  // 실패하면 한 줄 안내를 띄우고 false를 돌려줘요.
  const send = (
    request: RecordRequest,
    by: Role,
    text: string,
    update: (d: DemoState) => void,
    options: ActionOptions,
  ) =>
    accountId
      ? api.records
          .send({ accountId }, { request, role: by, text, update, options })
          .then(
            (made): { id?: string } => made,
            (e): false => {
              toast(errorMessage(e))
              return false
            },
          )
      : Promise.resolve<false>(false)
  const act = (
    request: RecordRequest,
    text: string,
    update: (d: DemoState) => void,
    notify = true,
    eventRoomId = roomId,
    connecting = false,
    privateTo?: Role,
    target?: RecordTarget,
  ) =>
    send(request, role, text, update, {
      notify,
      roomId: eventRoomId,
      connecting,
      privateTo,
      target,
    })
  const sessionPhone = account?.phone ?? ""
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
  const readOnly = room.status === "정산 완료" || isPreJoin(room)
  // 합류한 상대 기록의 이름(서버는 어느 기록에 합류했는지 주지 않아 HTTP에서는 비어 있어요)
  const joinedName = db.rooms.find((x) => x.id === room.preJoinOf)?.name
  // 호실 단위 버튼(초대·하자 등록·거주 종료)은 서버가 준 availableActions로만 그려요(GET /api/rooms/{id}).
  // 서버가 연결되면 응답 값을 그대로 쓰고, 그 전에는 Mock이 서버 대신 계산해요.
  const roomActions = api.actions.room(room, role, { guest, readOnly })
  const roomHas = (a: RoomAction) => hasAction(roomActions, a)
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
    // HTTP: 서버가 고른 코드에 문구만 붙여요(06 4-3 nextStep). 초대·입주 점검·수리 건수 코드는 서버가 그 기능에서 더해요(#190)
    const code = r.server?.nextStep
    if (code)
      return code === "SETTLE"
        ? "퇴거 정산을 마무리해 주세요"
        : code === "READ_ONLY"
          ? r.server?.joinedAway
            ? "합류 전 기록이에요 · 읽기 전용"
            : "정산이 끝난 기록이에요 · 읽기 전용"
          : "새로 답할 일이 없어요"
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
              : "새로 답할 일이 없어요"
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
          : v === "진행 중"
            ? repairs.filter((r) => r.status !== "처리 완료").length
            : repairs.filter((r) => r.status === v).length
  function go(p: Page) {
    if (p === "inspection") setKind("in")
    else if (p === "checkout") setKind("out")
    navigate((r) => ({ ...r, page: p, roomId: r.roomId || roomId }))
    setSearch("")
    setFilter("전체")
    window.scrollTo({ top: 0 })
  }
  // 입력 창 열기. 창마다 칸 상태를 따로 가져서, 열 때마다 처음 값으로 시작해요.
  function open(m: Modal) {
    if (m === "repair") setRepairPrefill(undefined)
    setModal(m)
  }
  function toast(text: string) {
    setMessage(text)
    window.setTimeout(() => setMessage(""), 4500)
  }
  function commit(
    request: RecordRequest,
    text: string,
    update: (d: DemoState) => void,
    eventRoomId = roomId,
    privateTo?: Role,
    recordTarget?: RecordTarget,
  ): Promise<boolean> {
    const targetRoom = db.rooms.find((r) => r.id === eventRoomId)
    if (
      targetRoom &&
      !(sideOf(targetRoom, accountId) && canAccessRoom(targetRoom, role))
    ) {
      toast("호실 연결 후에 기록할 수 있어요.")
      return Promise.resolve(false)
    }
    if (/거주 종료$|정산 끝내기/.test(text)) {
      setPending({ request, text, update })
      return Promise.resolve(true)
    }
    // 저장 결과(성공 true, 실패 false)를 돌려줘요. 창이 기다렸다가 성공일 때만 닫게요(실패면 쓴 내용을 그대로 둬요)
    return act(
      request,
      text,
      update,
      !privateTo,
      eventRoomId,
      false,
      privateTo,
      recordTarget,
    ).then((ok) => {
      if (!ok) return false
      toast("기록했어요.")
      setModal(null)
      return true
    })
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
        void act(
          notInSpec("부위 사진 한 건 공유 링크 만들기"),
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
    void send(
      noRequest("받은 초대 열람(GET /api/invitations 때 서버가 남겨요)"),
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
    void act(
      post(endpoints.rejectInvitation(sharedRoom.id)),
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
      post(
        endpoints.inspectionAction(
          inspectionIdIn(room, sessKind),
          "request-confirm",
        ),
      ),
      items.length
        ? `${kindLabel(sessKind)} 점검 확인 요청 · 하자 ${items.length}개`
        : `${kindLabel(sessKind)} 점검 확인 요청 · 하자 기록 없음`,
      (d) => requestInspectionAll(d, roomId, sessKind, role),
    )
  }
  // 점검 정정·확인 행동: 점검 항목 ID로 행동 기록을 남깁니다.
  function inspectionAct(
    request: RecordRequest,
    text: string,
    update: (d: DemoState) => void,
    targetId: string,
  ) {
    commit(
      request,
      text,
      update,
      roomId,
      undefined,
      targetId ? { kind: "inspection", id: targetId } : undefined,
    )
  }
  // 같은 곳 재발: 이전 수리 건과 그 부위로 하자 등록 창을 열어요
  function recur(prev: Repair) {
    open("repair")
    setRepairPrefill({
      recurrenceOf: prev.id,
      recurrenceTitle: prev.title,
      roomPartId: prev.roomPartId,
      zone: prev.zone,
    })
  }
  function patchRepair(
    request: RecordRequest,
    text: string,
    update: (r: Repair) => void,
  ) {
    commit(
      request,
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

  // 입력 창: 창마다 자기 칸 상태·확인·보내기를 가진 부품이에요(features/*/…-form). App은 무엇을 열지와 끝난 뒤 이동만 정해요.
  const closeModal = () => setModal(null)
  // 호실 만들기의 내 쪽 기본값 = 마지막에 고른 쪽(S-05): 내가 만든 가장 최근 호실의 쪽
  const lastRoomSide =
    db.rooms
      .filter((r) => r.creator && r.members?.[r.creator] === accountId)
      .sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? ""))
      .at(-1)?.creator ?? "임차인"
  const forms = (
    <>
      {modal === "connect" && (
        <ConnectForm
          sharedRoom={sharedRoom}
          role={role}
          account={account}
          guest={guest}
          db={db}
          myRooms={myRooms}
          act={act}
          onReject={rejectReceivedInvite}
          onCancel={closeModal}
          onConnected={(r) => {
            setModal(null)
            setGuest(false)
            replaceUrl(null, "", "/")
            navigate((x) => ({ ...x, page: "home", roomId: r.id }))
            window.scrollTo({ top: 0 })
            toast(`${r.name}에 ${sideText(role)}으로 연결했어요.`)
          }}
          onRejectToOwn={(own) => {
            setGuest(false)
            replaceUrl(null, "", "/")
            navigate((x) => ({ ...x, page: "connection", roomId: own.id }))
            setInviteTarget(undefined)
            setModal("invite")
          }}
        />
      )}
      {modal === "inspection" && (
        <InspectionItemForm
          kind={kind}
          room={room}
          role={role}
          locked={inspectionLocked}
          defectClosed={moveInDefectClosed}
          commit={commit}
          onCancel={closeModal}
        />
      )}
      {modal === "repair" && (
        <RepairCreateForm
          room={room}
          accountId={accountId}
          prefill={repairPrefill}
          onCancel={closeModal}
          onCreated={(id, share) => {
            toast("기록했어요.")
            setModal(null)
            setRepairPrefill(undefined)
            setRepairId(id)
            go("detail")
            if (share === "now") {
              setInviteTarget({ kind: "repair", id })
              open("invite")
            }
          }}
        />
      )}
      {modal === "account" && account && (
        <AccountPanel
          account={account}
          roomCount={accessibleRooms.length}
          invitationCount={receivedInvitations.length}
          onCancel={closeModal}
          onLogout={onLogout}
          onOpenTerms={() => setTermsOpen(true)}
          onUpdate={async (patch) => {
            try {
              onAccountChange(await auth.updateAccount(patch))
            } catch (e) {
              // 그사이 다른 곳에서 바뀌었으면(VERSION_CONFLICT) 내 정보를 다시 불러 보여 줘요
              if (shouldReload(e))
                auth.reload().then(onAccountChange, () => undefined)
              throw e
            }
          }}
          onChangePassword={(current, next) =>
            auth.changePassword(current, next)
          }
        />
      )}
      {modal === "room" && account && (
        <RoomForm
          account={account}
          initialSide={lastRoomSide}
          send={send}
          onCancel={closeModal}
          onCreated={(id) => {
            setModal(null)
            navigate((r) => ({ ...r, page: "home", roomId: id }))
            window.scrollTo({ top: 0 })
            toast(
              "호실 기록을 만들었어요. 입주 점검을 시작하거나 하자를 등록해 보세요.",
            )
          }}
        />
      )}
      {modal === "settlement" && (
        <SettlementItemForm
          roomId={roomId}
          role={role}
          zones={Array.from(
            new Set([
              ...db.inspections
                .filter((i) => i.roomId === roomId)
                .map((i) => i.zone),
              ...db.partPhotos
                .filter((p) => p.roomId === roomId)
                .map((p) => p.zone),
              ...repairs.map((x) => x.zone),
            ]),
          )}
          commit={commit}
          onCancel={closeModal}
        />
      )}
      {modal === "invite" && (
        <InviteForm
          room={room}
          role={role}
          sessionPhone={sessionPhone}
          target={inviteTarget}
          commit={commit}
          toast={toast}
          onCancel={closeModal}
        />
      )}
      {modal === "phone" && (
        <PhoneForm
          room={room}
          role={role}
          sessionPhone={sessionPhone}
          commit={commit}
          onCancel={closeModal}
        />
      )}
      {modal === "after" && (
        <RepairAfterForm
          repairId={repairId}
          accountId={accountId}
          onCancel={closeModal}
          onDone={() => {
            toast("기록했어요.")
            setModal(null)
            setRepairTick((t) => t + 1)
          }}
        />
      )}
    </>
  )

  // 수리 건 상세(S-12)는 pages/repair-detail-page. 링크 열람자(isGuest)에게는 돈 관련 경과와 행동 버튼을 보이지 않아요.
  const detailView = (r: Repair, isGuest = false) => (
    <RepairDetailPage
      accountId={accountId}
      refreshTick={repairTick}
      onNotify={toast}
      r={r}
      room={room}
      role={role}
      db={db}
      isGuest={isGuest}
      readOnly={readOnly}
      onBack={() => go("repairs")}
      onAction={patchRepair}
      onOpenModal={open}
      onRecur={recur}
      onOpenRepair={detail}
      onCopyShareLink={copyShareLink}
      onInvite={
        // 처음 알리기: 아직 초대 전인 호실에서 이 기록을 보여 주며 초대해요
        !isGuest &&
        roomHas("invite") &&
        room.status === "거주 중" &&
        room.connection === "연결 전"
          ? () => {
              setInviteTarget({ kind: "repair", id: r.id })
              open("invite")
            }
          : undefined
      }
      canAddRepair={!isGuest && roomHas("repair-cases")}
      partPhotos={partPhotos}
      inspections={visibleInspections}
      renderPartPhoto={photoContent}
      itemMasks={itemMasks}
      moneyPrefill={moneyPrefill}
      onPrefillUsed={() => setMoneyPrefill(undefined)}
      serverOnly={!isGuest && serverOnlyRepair}
    />
  )
  // 호실 이름은 겹칠 수 있어서 ID로 골라요
  const roomChoices = roomOptions(accessibleRooms)
  const selectRoom = (id: string) => {
    const found = db.rooms.find((r) => r.id === id)
    if (!found) return
    setRoomId(found.id)
    go(pageAfterRoomChange(page))
  }
  const showFirstSteps =
    roomHas("repair-cases") &&
    !sessionOf(room, "in") &&
    !repairs.length &&
    !partPhotos.length
  const subTabs =
    page !== "detail" && page !== "notifications"
      ? subTabsByGroup[activeTabOf(page)]
      : undefined
  // PC 전용 좌측 사이드바(모바일은 하단 고정 MobileTabbar 사용)
  const sideMenu = (
    <SideMenu
      page={page}
      openCount={openCount}
      invitations={receivedInvitations.length}
      room={hasRoomAccess ? room : undefined}
      roomOptions={roomChoices}
      account={account}
      roleText={sideText(role)}
      onRoom={selectRoom}
      onGo={(p) => go(p)}
      onAccount={() => open("account")}
    />
  )
  return (
    <>
      {guest ? (
        <SharePage
          account={account}
          sharedKind={sharedKind}
          sharedRoom={sharedRoom}
          sharedRepair={sharedRepair}
          sharedInspection={sharedInspection}
          sharedPhoto={sharedPhoto}
          db={db}
          role={role}
          renderRepair={(r) => detailView(r, true)}
          itemMasks={itemMasks}
          onAct={inspectionAct}
          onHome={() => {
            setGuest(false)
            replaceUrl(null, "", "/")
            navigate((r) => ({ ...r, page: "home", roomId: "" }))
          }}
          onConnect={() => open("connect")}
          onRequireAuth={onRequireAuth}
        />
      ) : (
        <div className="app-shell">
          <aside className="sidebar">{sideMenu}</aside>
          <div className="main-shell">
            <AppTopbar
              roomId={hasRoomAccess ? room.id : undefined}
              roomName={hasRoomAccess ? room.name : undefined}
              roomOptions={roomChoices}
              invitations={receivedInvitations.length}
              unread={unread}
              accountInitial={account?.name.slice(0, 1) || "나"}
              onRoom={selectRoom}
              onInvitations={() => go("connection")}
              onNotifications={() => go("notifications")}
              onAccount={() => open("account")}
            />
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
                    <PageHeading
                      title={
                        page === "home"
                          ? `${account?.name ?? ""}님, 안녕하세요`
                          : pageTitleOf(page)
                      }
                      greeting={page === "home"}
                      intro={
                        page === "home"
                          ? `${room.name} · 답할 일과 진행 중인 기록을 모았어요.`
                          : pageIntro[page]
                      }
                      action={
                        roomHas("repair-cases") &&
                        (page === "repairs" ||
                          (page === "home" && !showFirstSteps)) && (
                          <Button onClick={() => open("repair")}>
                            <Plus />
                            하자 등록
                          </Button>
                        )
                      }
                    />
                  )}
                  {subTabs && (
                    <SubTabs
                      items={subTabs}
                      value={page}
                      badges={{
                        money: moneyLeft.length,
                        connection: receivedInvitations.length,
                      }}
                      onChange={(next) => go(next)}
                    />
                  )}
                  {readOnly && (
                    <Notice className="mb-5" icon={<ShieldCheck aria-hidden />}>
                      정산 완료 · 읽기 전용으로 보관 중이에요. 정산 완료 뒤
                      1년이 지나면 삭제돼요.
                    </Notice>
                  )}
                  {isPreJoin(room) && (
                    <Notice className="mb-5">
                      합류 전 기록이에요. 같은 집의 상대 기록
                      {joinedName ? `(${joinedName})` : ""}에 합류해서 이 기록은
                      읽기만 할 수 있어요.
                    </Notice>
                  )}
                  {page !== "rooms" &&
                    page !== "notifications" &&
                    page !== "connection" && (
                      <CounterpartNotice
                        room={room}
                        role={role}
                        moneyPage={page === "money" || page === "checkout"}
                        onInvite={
                          roomHas("invite") ? () => open("invite") : undefined
                        }
                      />
                    )}
                  {page === "home" && (
                    <RoomEndCard
                      view={roomEndView(room, role)}
                      role={role}
                      canMoveOut={roomHas("move-out")}
                      canConfirm={roomHas("move-out-confirm")}
                      canReject={roomHas("move-out-reject")}
                      onMoveOut={() => {
                        // 바로 거주 종료가 되면 확인 창을 먼저 띄워요(되돌릴 수 없어서). 판단은 lib/room-end(moveOutEndsNow)
                        const direct = moveOutEndsNow(room, role)
                        commit(
                          post(endpoints.moveOut(roomId)),
                          role === "임차인"
                            ? "이사했어요 · 거주 종료"
                            : direct
                              ? "이사함 · 거주 종료"
                              : "거주 종료 요청",
                          (d) => {
                            const r = d.rooms.find((r) => r.id === roomId)!
                            if (direct) r.status = "거주 종료"
                            else r.endRequested = true
                          },
                        )
                      }}
                      onConfirm={() =>
                        commit(
                          post(endpoints.moveOutAnswer(roomId, "confirm")),
                          "거주 종료 요청에 맞아요 · 거주 종료",
                          (d) => {
                            const r = d.rooms.find((r) => r.id === roomId)!
                            r.status = "거주 종료"
                            r.endRequested = false
                            r.endWait = undefined
                          },
                        )
                      }
                      onReject={(reason) =>
                        commit(
                          post(endpoints.moveOutAnswer(roomId, "reject"), {
                            reason,
                          }),
                          `거주 종료 요청 · 아직 살고 있어요 · ${reason}`,
                          (d) => {
                            const r = d.rooms.find((r) => r.id === roomId)!
                            r.endRequested = false
                            r.endWait = undefined
                          },
                        )
                      }
                    />
                  )}
                  {page === "home" && (
                    <HomePage
                      showFirstSteps={showFirstSteps}
                      onStartInspection={() => go("inspection")}
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
                  {page === "rooms" && (
                    <RoomsPage
                      db={db}
                      role={role}
                      roomId={roomId}
                      accountId={accountId}
                      accessibleRooms={accessibleRooms}
                      nextStep={nextStep}
                      setRoomId={setRoomId}
                      go={go}
                      open={open}
                    />
                  )}
                  {(page === "repairs" || page === "money") && (
                    <RepairsPage
                      page={page}
                      role={role}
                      repairs={repairs}
                      moneyLeft={moneyLeft}
                      openCount={openCount}
                      filter={filter}
                      setFilter={setFilter}
                      search={search}
                      setSearch={setSearch}
                      tabCount={tabCount}
                      detail={detail}
                      events={events}
                      asOf={db.repairListAsOf?.[room.id]}
                    />
                  )}
                  {page === "detail" && repair && detailView(repair)}
                  {(page === "inspection" || page === "checkout") && (
                    <InspectionPage
                      page={page}
                      room={room}
                      roomId={roomId}
                      role={role}
                      db={db}
                      readOnly={readOnly}
                      session={session}
                      moveIn={moveIn}
                      sessKind={sessKind}
                      moveInMine={moveInMine}
                      inspectionLocked={inspectionLocked}
                      needMoveInDate={needMoveInDate}
                      partPhotos={partPhotos}
                      visibleInspections={visibleInspections}
                      inspectionItems={inspectionItems}
                      repairs={repairs}
                      settlements={settlements}
                      sessionPhone={sessionPhone}
                      commit={commit}
                      toast={toast}
                      open={open}
                      setKind={setKind}
                      detail={detail}
                      copyShareLink={copyShareLink}
                      requestMoveInCheck={requestMoveInCheck}
                      inspectionAct={inspectionAct}
                      itemMasks={itemMasks}
                      photoContent={photoContent}
                    />
                  )}
                  {page === "connection" && (
                    <>
                      <ConnectionPage
                        room={room}
                        role={role}
                        readOnly={readOnly}
                        sessionPhone={sessionPhone}
                        roomHas={roomHas}
                        open={open}
                      />
                      {/* 내려받기(S-19)는 읽기만 하는 호실도 돼요. 목업은 서버 기록이 없어 HTTP에서만 보여요.
                          호실을 바꾸면 앞 호실의 준비 상태(받은 목록)가 남지 않게 새로 그려요(#323 리뷰) */}
                      {api.mode === "http" && (
                        <RoomDownload
                          key={room.id}
                          roomId={room.id}
                          roomName={room.name}
                        />
                      )}
                    </>
                  )}
                  {page === "timeline" && (
                    <TimelinePage
                      events={events}
                      asOf={db.timelineAsOf?.[room.id]}
                    />
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
                          void act(
                            noRequest(
                              "받은 초대 열람(GET /api/invitations 때 서버가 남겨요)",
                            ),
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
          {hasRoomAccess && (
            <MobileTabbar
              page={page}
              openRepairCount={openCount}
              invitations={receivedInvitations.length}
              onGo={(p) => go(p)}
            />
          )}
        </div>
      )}
      <Dialog
        open={modal !== null}
        onOpenChange={(v) => {
          if (!v) setModal(null)
        }}
      >
        <DialogContent className="app-dialog sm:max-w-lg">
          {forms}
        </DialogContent>
      </Dialog>
      {account && (
        <TermsReconfirmDialog
          open={termsOpen}
          pending={account.pendingAcknowledgements}
          onClose={() => setTermsOpen(false)}
          onAcknowledge={async (items) =>
            onAccountChange(await auth.acknowledge(items))
          }
          onReload={async () => onAccountChange(await auth.reload())}
          onDone={(message) => {
            setTermsOpen(false)
            toast(message)
          }}
        />
      )}
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
                  void act(pending.request, pending.text, pending.update)
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
      <Toast message={message} onClose={() => setMessage("")} />
    </>
  )
}
