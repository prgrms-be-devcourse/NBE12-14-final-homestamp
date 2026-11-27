import { useEffect, useState, useSyncExternalStore } from "react"
import { ImageOff, Link2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Logo, Panel } from "@/components/common/blocks"
import { ErrorState, LoadingState } from "@/components/common/states"
import { ApiError } from "@/api/errors"
import {
  readLinkToken,
  viewLink,
  type LinkPhoto,
  type LinkRecord,
  type LinkSide,
  type LinkView,
} from "@/api/link-view"
import { withSubject } from "@/lib/words"

// 문자 링크로 여는 화면(S-16, 06 LINK-1·2, 08 11장). 가입 전에도 링크가 가리킨 것 하나만 읽기로 보여요.
// - 보이는 범위·상태 문구는 서버(#303)가 정해서 줘요. 화면은 다시 고르거나 문구를 만들지 않아요
// - 이름(가린 이름 포함)·호실 별칭·번호·금액은 응답에 없고 화면도 보이지 않아요(03 6-1). 사람은 측 말(세입자 / 집주인)로만 가리켜요
// - 링크를 보낸 측(머리 문구)과 기록·경과를 쓴 측(서버가 준 문구)은 따로예요
// - 답하기·고치기 버튼은 없어요(LINK-1b). 답하려면 가입·로그인한 뒤 초대받은 번호로 연결해요(03 B "연결 확인")

const subscribeHash = (onChange: () => void) => {
  window.addEventListener("hashchange", onChange)
  return () => window.removeEventListener("hashchange", onChange)
}

type Outcome =
  | { state: "ok"; view: LinkView }
  | { state: "invalid" }
  | { state: "error"; message: string }

// 어느 토큰·몇 번째 시도의 결과인지 함께 둬요. 토큰이 바뀌면 앞 링크의 결과를 보이지 않아요
interface Loaded {
  token: string
  attempt: number
  outcome: Outcome
}

const TOO_MANY =
  "잠깐 사이에 너무 많이 열었어요. 1분쯤 뒤에 다시 시도해 주세요."
const SERVER_ERROR =
  "잠시 문제가 생겨 링크를 열지 못했어요. 잠시 뒤 다시 시도해 주세요."

function outcomeOf(e: unknown): Outcome {
  if (e instanceof ApiError) {
    // 못 여는 이유는 모두 같은 LINK_INVALID예요(없는 토큰·무효·기록 없음, 06 LINK-2). 이유를 짐작해 보이지 않아요
    if (e.code === "LINK_INVALID") return { state: "invalid" }
    if (e.status === 0) return { state: "error", message: e.message }
    // 조회 한도(06 LINK-2c). nginx가 본문 없이 줄 수도 있어 문구를 화면이 정해요
    if (e.status === 429) return { state: "error", message: TOO_MANY }
  }
  // 서버 내부 오류는 무효 링크가 아니에요. 다시 시도할 수 있게 둬요
  return { state: "error", message: SERVER_ERROR }
}

export function LinkViewPage({
  member,
  onSignup,
  onLogin,
  onContinue,
}: {
  // 로그인한 회원이 연 경우. 가입·로그인 대신 받은 초대로 이어 가요
  member: boolean
  onSignup: () => void
  onLogin: () => void
  onContinue: () => void
}) {
  const token = useSyncExternalStore(subscribeHash, readLinkToken)
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState<Loaded>()

  useEffect(() => {
    if (!token) return
    let alive = true
    viewLink(token).then(
      (view) => {
        if (alive) setLoaded({ token, attempt, outcome: { state: "ok", view } })
      },
      (e: unknown) => {
        if (alive) setLoaded({ token, attempt, outcome: outcomeOf(e) })
      },
    )
    return () => {
      alive = false
    }
  }, [token, attempt])

  const current =
    loaded && loaded.token === token && loaded.attempt === attempt
      ? loaded.outcome
      : undefined
  const retry = () => setAttempt((n) => n + 1)

  return (
    <div className="guest-shell">
      <header>
        <Logo />
      </header>
      {!token ? (
        <Invalid />
      ) : !current ? (
        <LoadingState label="링크를 여는 중이에요" />
      ) : current.state === "invalid" ? (
        <Invalid />
      ) : current.state === "error" ? (
        <ErrorState message={current.message} onRetry={retry} />
      ) : (
        <Shown
          view={current.view}
          member={member}
          onSignup={onSignup}
          onLogin={onLogin}
          onContinue={onContinue}
          onReload={retry}
        />
      )}
    </div>
  )
}

function Invalid() {
  return (
    <Card className="guest-cta" role="alert">
      <h2>더 이상 열 수 없는 링크예요</h2>
    </Card>
  )
}

const sideWord = (side: LinkSide) => (side === "TENANT" ? "세입자" : "집주인")
const otherSide = (side: LinkSide): LinkSide =>
  side === "TENANT" ? "LANDLORD" : "TENANT"
const placeOf = (v: LinkView) =>
  [v.sido, v.sigungu, v.dong].filter(Boolean).join(" ")

const dayTime = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
})
const day = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  month: "numeric",
  day: "numeric",
})

// 서버는 Instant를 주지만, 읽을 수 없는 값이 와도 화면 전체가 멈추지 않게 날짜만 비워요
const formatted = (f: Intl.DateTimeFormat, at: string) => {
  const date = new Date(at)
  return Number.isNaN(date.getTime()) ? "" : f.format(date)
}

function Shown({
  view,
  member,
  onSignup,
  onLogin,
  onContinue,
  onReload,
}: {
  view: LinkView
  member: boolean
  onSignup: () => void
  onLogin: () => void
  onContinue: () => void
  onReload: () => void
}) {
  if (view.kind === "SETTLEMENT_INVITE")
    return (
      <Card className="guest-cta">
        <h2>퇴거 정산 확인 요청이 있어요</h2>
        <p>금액과 항목은 가입하고 호실에 연결한 뒤에 볼 수 있어요.</p>
        <Join
          member={member}
          signup="가입하고 확인하기"
          login="이미 회원이에요: 로그인하고 확인하기"
          onSignup={onSignup}
          onLogin={onLogin}
          onContinue={onContinue}
        />
      </Card>
    )
  const yourSide = view.yourSide
  // 호실 초대·기록 링크는 받는 측을 늘 줘요(06 4-4). 없으면 측을 짐작하지 않고 서버 오류로 보여요
  if (!yourSide || (view.kind === "RECORD" && !view.record))
    return <ErrorState message={SERVER_ERROR} onRetry={onReload} />
  if (view.kind === "ROOM_INVITE" || !view.record)
    return (
      <Card className="guest-cta">
        <div className="guest-banner">
          <Link2 size={22} aria-hidden />
          <div>
            <h1>
              {placeOf(view)}의 한 호실에{" "}
              {yourSide === "TENANT" ? "세입자로" : "집주인으로"} 초대받았어요
            </h1>
            <p>
              가입한 뒤 초대받은 번호로 인증하면 이 호실에 연결할지 고를 수
              있어요.
            </p>
          </div>
        </div>
        <Join
          member={member}
          signup="가입하고 연결하기"
          login="이미 회원이에요: 로그인하고 연결하기"
          onSignup={onSignup}
          onLogin={onLogin}
          onContinue={onContinue}
        />
      </Card>
    )
  const record = view.record
  return (
    <>
      <div className="guest-banner">
        <Link2 size={22} aria-hidden />
        <div>
          <h1>
            {placeOf(view)}의 한 호실 ·{" "}
            {withSubject(sideWord(otherSide(yourSide)))} 보낸 기록이에요
          </h1>
          <p>이 링크가 가리킨 기록 하나만 보여요.</p>
        </div>
      </div>
      <RecordView record={record} onReload={onReload} />
      {view.hasMoney && (
        <p className="muted">
          이 건에 수리비 기록이 있어요 · 가입하면 볼 수 있어요
        </p>
      )}
      <Card className="guest-cta">
        <p>여기서 보기만 해서는 답한 것으로 남지 않아요</p>
        <Join
          member={member}
          signup="가입하고 응답하기"
          login="이미 회원이에요: 로그인하고 응답하기"
          signupNote="이메일과 휴대폰 인증, 약 2분"
          onSignup={onSignup}
          onLogin={onLogin}
          onContinue={onContinue}
        />
      </Card>
    </>
  )
}

function Join({
  member,
  signup,
  login,
  signupNote,
  onSignup,
  onLogin,
  onContinue,
}: {
  member: boolean
  signup: string
  login: string
  signupNote?: string
  onSignup: () => void
  onLogin: () => void
  onContinue: () => void
}) {
  if (member)
    return (
      <div className="flex flex-col gap-2">
        <p className="muted">
          가입할 때 인증한 번호가 초대받은 번호와 같으면 앱의 받은 초대에서
          연결할 수 있어요.
        </p>
        <Button onClick={onContinue}>앱으로 가기</Button>
      </div>
    )
  return (
    <div className="flex flex-col gap-2">
      <Button onClick={onSignup}>{signup}</Button>
      {signupNote && <p className="muted">{signupNote}</p>}
      <Button variant="ghost" onClick={onLogin}>
        {login}
      </Button>
    </div>
  )
}

function RecordView({
  record,
  onReload,
}: {
  record: LinkRecord
  onReload: () => void
}) {
  return (
    <Panel title={record.title ?? record.partName ?? "공유한 기록"}>
      {record.statusLines.length > 0 && (
        <ul
          aria-label="전체 상태"
          className="mb-3 text-sm text-muted-foreground"
        >
          {record.statusLines.map((line, i) => (
            <li key={i}>{line}</li>
          ))}
        </ul>
      )}
      {record.description && <p>{record.description}</p>}
      {record.photos.length > 0 && (
        <Photos photos={record.photos} onReload={onReload} />
      )}
      {record.items.length > 0 && (
        <ol aria-label="항목" className="flex flex-col gap-3">
          {record.items.map((item, i) => (
            <li key={i} className="flex flex-col gap-1 rounded-lg border p-3">
              <h3 className="font-semibold">{item.partName}</h3>
              {item.description && <p>{item.description}</p>}
              <p>
                <strong>{item.statusLine}</strong>
              </p>
              {item.photos.length ? (
                <Photos photos={item.photos} onReload={onReload} />
              ) : (
                <p className="muted">사진 없음</p>
              )}
            </li>
          ))}
        </ol>
      )}
      {record.history.length > 0 && (
        <section aria-label="경과" className="mt-4 text-sm">
          <h3 className="mb-1 font-semibold">경과</h3>
          <ul className="flex flex-col gap-1 text-muted-foreground">
            {record.history.map((h, i) => (
              <li key={i}>
                <time dateTime={h.at}>{formatted(dayTime, h.at)}</time>{" "}
                {h.label}
              </li>
            ))}
          </ul>
        </section>
      )}
    </Panel>
  )
}

// 사진 주소는 5분만 열려요. 못 불러온 사진은 사진이 없는 것과 다르게 알리고, 링크를 다시 열어 새 주소를 받아요
function Photos({
  photos,
  onReload,
}: {
  photos: LinkPhoto[]
  onReload: () => void
}) {
  const [failed, setFailed] = useState<ReadonlySet<string>>(new Set())
  return (
    <div className="flex flex-wrap gap-2" aria-label="사진">
      {photos.map((photo) =>
        photo.erased || !photo.thumbUrl ? (
          <div key={photo.id} className="muted" data-photo="erased">
            지운 사진이에요
            {photo.erasedAt && ` · ${formatted(day, photo.erasedAt)}`}
          </div>
        ) : failed.has(photo.id) ? (
          <div
            key={photo.id}
            className="muted"
            data-photo="failed"
            role="alert"
          >
            <ImageOff size={16} aria-hidden /> 사진을 불러오지 못했어요{" "}
            <Button size="sm" variant="outline" onClick={onReload}>
              다시 불러오기
            </Button>
          </div>
        ) : (
          <a
            key={photo.id}
            href={photo.originalUrl ?? photo.thumbUrl}
            target="_blank"
            rel="noopener noreferrer"
            referrerPolicy="no-referrer"
          >
            <img
              src={photo.thumbUrl}
              alt="기록 사진"
              width={96}
              height={96}
              referrerPolicy="no-referrer"
              onError={() => setFailed((prev) => new Set(prev).add(photo.id))}
            />
          </a>
        ),
      )}
    </div>
  )
}
