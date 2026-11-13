import { useState, type ReactNode } from "react"
import { Camera, Plus } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { ActionDialog } from "@/components/action-dialog"
import { Panel, Status } from "@/components/prototype"
import { EmptyState } from "@/components/states"
import { Notice } from "@/components/notice"
import { PhotoGallery } from "@/components/photo-gallery"
import { RecordDescription } from "@/components/record-description"
import { RepairListItem, StatusTag } from "@/components/home-dashboard"
import { RoomFinancials } from "@/features/settlement/room-financials"
import { RepairCandidates } from "@/features/settlement/repair-candidates"
import {
  ConfirmAllItems,
  InspectionItemCorrections,
  MissingDefectDialog,
} from "@/features/inspection/inspection-corrections"
import { InspectionDisputes } from "@/features/inspection/inspection-disputes"
import { ComparisonRow } from "@/features/inspection/comparison-row"
import { splitWrong, WrongPhotos } from "@/features/repair/repair-records"
import type {
  DemoState,
  Inspection,
  PartPhoto,
  RecordTarget,
  Repair,
  Role,
  Room,
  Settlement,
} from "@/data/demo"
import {
  confirmItem,
  respondItem,
  supplementItem,
  confirmNoDefects,
  finishSoloInspection,
  inspectionStatus,
  kindLabel,
  sessionOf,
  startInspection,
} from "@/lib/inspection-rules"
import { activeSettlementLink, sendSettlementLink } from "@/lib/workflows"
import type { maskOf } from "@/lib/photo-privacy"
import { sideName, sideText } from "@/lib/words"
import { reaskWaitDays } from "@/lib/reask"
import {
  hasAction,
  type InspectionAction,
  type RoomAction,
} from "@/api/actions"
import {
  inspectionAvailableActions,
  inspectionItemAvailableActions,
} from "@/api/mock/inspection-actions"
import { correctionAvailableActions } from "@/api/mock/correction-actions"
import {
  settlementAvailableActions,
  settlementItemAvailableActions,
  transferAvailableActions,
} from "@/api/mock/settlement-actions"
import { RespondButtons } from "@/components/common/respond-buttons"
import { SupplementRequest } from "@/components/common/supplement-request"

type Commit = (
  text: string,
  update: (d: DemoState) => void,
  eventRoomId?: string,
  privateTo?: Role,
  recordTarget?: RecordTarget,
) => void

export interface InspectionPageProps {
  page: "inspection" | "checkout"
  room: Room
  roomId: string
  role: Role
  db: DemoState
  readOnly: boolean
  session: ReturnType<typeof sessionOf>
  moveIn: ReturnType<typeof inspectionStatus>
  sessKind: "in" | "out"
  moveInMine: boolean
  inspectionLocked: boolean
  needMoveInDate: boolean
  partPhotos: PartPhoto[]
  visibleInspections: Inspection[]
  inspectionItems: Inspection[]
  repairs: Repair[]
  settlements: Settlement[]
  sessionPhone: string
  commit: Commit
  toast: (text: string) => void
  open: (m: "repair" | "inspection" | "settlement") => void
  setKind: (k: "in" | "out") => void
  detail: (id: string) => void
  copyShareLink: (kind: "inspection", id: string) => void
  requestMoveInCheck: () => void
  inspectionAct: (
    text: string,
    update: (d: DemoState) => void,
    targetId: string,
  ) => void
  itemMasks: (i: Inspection, wrong?: boolean) => ReturnType<typeof maskOf>[]
  photoContent: (p: PartPhoto) => ReactNode
  // 호실 기록에서 서버가 준 지금 누를 수 있는 행동(거주 종료 등)
  roomActions: readonly RoomAction[]
}

// S-14 점검(입주·퇴거, 나란히 보기). 퇴거 화면에는 거주 종료와 정산(S-15)이 함께 있어요.
export function InspectionPage({
  page,
  room,
  roomId,
  role,
  db,
  readOnly,
  session,
  moveIn,
  sessKind,
  moveInMine,
  inspectionLocked,
  needMoveInDate,
  partPhotos,
  visibleInspections,
  inspectionItems,
  repairs,
  settlements,
  sessionPhone,
  commit,
  toast,
  open,
  setKind,
  detail,
  copyShareLink,
  requestMoveInCheck,
  inspectionAct,
  itemMasks,
  photoContent,
  roomActions,
}: InspectionPageProps) {
  const [missingOpen, setMissingOpen] = useState(false)
  const [settlePhone, setSettlePhone] = useState("")
  const [stillLiving, setStillLiving] = useState(false)
  const roomHas = (a: RoomAction) => hasAction(roomActions, a)
  // 버튼은 서버가 준 availableActions로만 그려요(08 0장 원칙 6). 서버가 연결되면 GET /api/inspections/{id} 응답 값을 그대로 써요.
  const actions = inspectionAvailableActions(db, room, sessKind, role, {
    readOnly,
  })
  const has = (a: InspectionAction) => hasAction(actions, a)
  const itemActions = (i: Inspection) =>
    inspectionItemAvailableActions(i, role, { readOnly })
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
        {hasAction(itemActions(i), "respond") && (
          <RespondButtons
            size="sm"
            answered={
              i.ack === "보충 요청"
                ? "NEED_MORE"
                : i.ack === "이견"
                  ? "DISPUTE"
                  : undefined
            }
            onRespond={(answer, reason) =>
              answer === "ACK"
                ? inspectionAct(
                    `${i.title} · 점검 확인 · 본 모습 ${i.version ?? 1}번`,
                    (d) => confirmItem(d, role, i.id, i.version ?? 1),
                    i.id,
                  )
                : inspectionAct(
                    `${i.title} · 점검 ${answer === "NEED_MORE" ? "보충 요청" : "이견"} · ${reason}`,
                    (d) =>
                      respondItem(
                        d,
                        role,
                        i.id,
                        answer === "NEED_MORE" ? "보류" : "이견",
                        reason,
                      ),
                    i.id,
                  )
            }
          />
        )}
        {hasAction(itemActions(i), "supplement") && (
          <SupplementRequest
            waitDays={reaskWaitDays(i.reaskedAt)}
            onSupplement={(data) =>
              inspectionAct(
                `${i.title} · 보충하고 다시 확인 요청${data.text ? ` · ${data.text}` : ""}${data.photos.length ? ` · 사진 ${data.photos.length}장` : ""}`,
                (d) => supplementItem(d, role, i.id, data),
                i.id,
              )
            }
          />
        )}
        <InspectionItemCorrections
          i={i}
          db={db}
          role={role}
          readOnly={readOnly}
          actions={itemActions(i)}
          correctionActionsOf={(c, list) =>
            correctionAvailableActions(c, role, list, { readOnly })
          }
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
  return (
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
          ? ["퇴거 점검", "거주 종료", "정산 항목 · 보증금 확인", "정산 완료"]
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
                  입주 후에 등록된 기록이에요. 입주 당시 상태와 다를 수 있어요.
                </p>
              )}
              <ol
                className="m-0 flex list-none gap-1.5 p-0"
                aria-label={
                  out ? "퇴거 · 정산 진행 단계" : "입주 점검 진행 단계"
                }
              >
                {steps.map((x, n) => (
                  <li
                    key={x}
                    aria-current={n === cur ? "step" : undefined}
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
      {has("start") && (
        <Card className="mb-5 min-w-0 gap-3 rounded-[18px] border border-turn-line bg-turn-soft py-4 shadow-none ring-0">
          <CardContent className="px-5 max-md:px-4">
            <form
              className="flex flex-col gap-2"
              onSubmit={(e) => {
                e.preventDefault()
                const date = String(
                  new FormData(e.currentTarget).get("moveInDate") || "",
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
              <label htmlFor="move-in-date" className="text-[15px] text-ink">
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
                <Button type="submit" size="lg" className="h-11 sm:px-6">
                  다음
                </Button>
              </div>
              <small className="text-[13px] text-sub">
                계약서의 입주일이에요. 입주일을 알려 주셔야 점검을 시작할 수
                있어요. 입주일보다 늦게 시작하면 ‘입주 후 등록’이 붙어요.
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
              {has("items") && (
                <Button variant="outline" onClick={() => setMissingOpen(true)}>
                  <Plus />
                  빠뜨린 하자 더하기
                </Button>
              )}
              {has("items") && missingHint}
            </div>
          ) : moveIn === "확인 요청" && !moveInMine ? (
            has("items") && (
              <div className="flex flex-col items-end gap-1">
                <Button variant="outline" onClick={() => setMissingOpen(true)}>
                  <Plus />
                  빠뜨린 하자 더하기
                </Button>
                {missingHint}
              </div>
            )
          ) : (
            has("items") && (
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
            {has("request-confirm") && (
              <Button onClick={requestMoveInCheck}>점검 확인 요청</Button>
            )}
            {has("finish-alone") && (
              <Button
                variant={page === "inspection" ? "link" : "outline"}
                onClick={() =>
                  commit(
                    `${kindLabel(sessKind)} 점검 혼자 마치기 · 혼자 남긴 기록(상대 없음)`,
                    (d) => finishSoloInspection(d, roomId, sessKind, role),
                  )
                }
              >
                혼자 마치기
              </Button>
            )}
            {session?.soloLocked && (
              <small className="muted">
                상대 없이 혼자 마친 기록이에요. 상대가 연결되면 확인 요청이 한
                번에 가요.
              </small>
            )}
            {moveIn === "작성 중" && !moveInMine && (
              <small className="muted">
                {sideText(session!.author)}이 기준 기록을 쓰고 있어요.
              </small>
            )}
            {((moveIn === "없음" && !needMoveInDate) ||
              (moveIn === "작성 중" && moveInMine)) && (
              <small className="muted">
                하자와 부위 사진을 계속 등록할 수 있어요. 하자를 등록해도 확인
                요청은 가지 않고, ‘점검 확인 요청’을 눌러야 점검 전체가 상대에게
                보여요.
                {moveIn === "없음" ? " 먼저 작성한 쪽이 기준 기록을 써요." : ""}
              </small>
            )}
            {has("confirm-all") && session?.noDefects && (
              <Button
                onClick={() =>
                  commit(
                    `${kindLabel(sessKind)} 점검 · 하자 기록 없음 확인`,
                    (d) => confirmNoDefects(d, roomId, sessKind, role),
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
                  : "항목마다 확인·이견을 남기거나 ‘남은 항목 모두 확인’을 눌러 주세요. 빠진 하자는 [+ 빠뜨린 하자 더하기]로 더해요."}
              </small>
            )}
          </div>
        }
        <p className="muted mb-5">
          하자는 상대가 확인하고, 부위 사진은 등록 시각만 남아요. 적지 않은
          부위는 ‘기록 없음’이에요.
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
              .filter((i) => i.roomId === roomId && i.kind === "in")
              .map((i) => (
                <div className={inspectionCard} key={i.id}>
                  {inspectionContent(i)}
                </div>
              ))}
            {/* 적지 않은 부위는 '기록 없음'(문서 9장 [반드시]) — 목록 끝 얇은 한 줄 */}
            <div className="col-span-full flex flex-wrap items-center gap-x-2 gap-y-0.5 rounded-[12px] border border-dashed border-[#dce5f1] px-4 py-3 text-sm text-[#91a6c3]">
              <Camera size={16} aria-hidden />
              <strong className="font-semibold">그 밖의 부위</strong>
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
                  .map((p) => <div key={p.id}>{photoContent(p)}</div>),
                ...visibleInspections
                  .filter(
                    (i) => i.roomId === roomId && i.kind === k && i.zone === z,
                  )
                  .map((i) => <div key={i.id}>{inspectionContent(i)}</div>),
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
            {has("confirm-all") && !session?.noDefects && (
              <ConfirmAllItems
                items={inspectionItems}
                db={db}
                role={role}
                onAct={inspectionAct}
              />
            )}
            {inspectionLocked && (
              <small className="muted">
                잠긴 점검의 항목은 고치기로 바꿔요. 빠진 하자는 ‘빠뜨린 하자
                더하기’, 기존 항목의 고치기와 삭제 요청은 각 항목 카드에서 해요.
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
            {roomHas("move-out") && (
              <Button
                onClick={() =>
                  commit(
                    role === "임차인"
                      ? "퇴거 점검 마침 · 거주 종료"
                      : "거주 종료 확인 요청",
                    (d) => {
                      const r = d.rooms.find((r) => r.id === roomId)!
                      if (role === "임차인" || r.connection === "연결 전")
                        r.status = "거주 종료"
                      else r.endRequested = true
                    },
                  )
                }
              >
                {role === "임차인"
                  ? "점검 마침 · 이사했어요"
                  : "점검 마침 · 거주 종료 요청"}
              </Button>
            )}
            {roomHas("move-out-confirm") && (
              <>
                <Notice>
                  집주인이 이사했다고 남겼어요. 맞으면 거주 종료가 돼요.
                </Notice>
                <Button
                  onClick={() =>
                    commit("거주 종료 요청 확인 · 맞아요", (d) => {
                      const r = d.rooms.find((r) => r.id === roomId)!
                      r.status = "거주 종료"
                      r.endWait = undefined
                    })
                  }
                >
                  맞아요
                </Button>
              </>
            )}
            {roomHas("move-out-reject") && (
              <Button variant="outline" onClick={() => setStillLiving(true)}>
                아직 살고 있어요
              </Button>
            )}
            {room.endRequested && role === "임대인" && (
              <small className="muted">
                {room.endWait === "미확인"
                  ? "세입자 확인 없음 · 72시간 동안 답이 오지 않았어요"
                  : `세입자의 답을 기다려요${room.endWait ? " · 다시 알렸어요" : ""}`}
              </small>
            )}
            {roomHas("move-out-alone") && (
              <Button
                onClick={() =>
                  commit("거주 종료 요청 적용 · 세입자 미확인", (d) => {
                    const r = d.rooms.find((r) => r.id === roomId)!
                    r.status = "거주 종료"
                    r.endUnconfirmed = true
                  })
                }
              >
                혼자 거주 종료
              </Button>
            )}
            <small className="muted">
              거주 종료와 점검 항목 확인·정산 완료는 별개예요.
            </small>
            {/* [아직 살고 있어요]는 사유와 함께 답해요(06 S-06, move-out/reject REASON_REQUIRED) */}
            <ActionDialog
              open={stillLiving}
              title="아직 살고 있어요"
              description="집주인의 거주 종료 요청에 답해요. 사유가 함께 남아요."
              submitLabel="보내기"
              onClose={() => setStillLiving(false)}
              onSubmit={(f) => {
                const reason = String(f.get("reason") || "").trim()
                if (!reason) return "사유를 입력해 주세요."
                commit(`거주 종료 요청 · 아직 살고 있어요 · ${reason}`, (d) => {
                  const r = d.rooms.find((r) => r.id === roomId)!
                  r.endRequested = false
                  r.endWait = undefined
                })
              }}
            >
              <div className="field">
                <label htmlFor="still-living-reason">사유 · 필수</label>
                <Textarea
                  id="still-living-reason"
                  name="reason"
                  required
                  placeholder="예: 이사는 다음 달 15일이에요"
                />
              </div>
            </ActionDialog>
          </div>
        )}
      </Panel>
      {page === "checkout" &&
        room.status === "거주 종료" &&
        room.connection !== "연결됨" &&
        room.creator === role && (
          <Panel className="mb-5" title="정산 초대 링크">
            <p className="muted mb-3">
              거주 중에 연결되지 않은 상대에게 정산 확인을 요청해요. 링크에는
              금액·항목 없이 ‘퇴거 정산 확인 요청이 있어요’와 가입하기만 보여요.
            </p>
            {activeSettlementLink(room) && (
              <p className="muted mb-2">
                보낸 정산 초대 링크 ·{" "}
                {new Date(activeSettlementLink(room)!.issuedAt).toLocaleString(
                  "ko-KR",
                  {
                    timeZone: "Asia/Seoul",
                  },
                )}{" "}
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
                  onChange={(e) => setSettlePhone(e.target.value)}
                  placeholder="상대 휴대폰 번호"
                />
                <Button
                  variant="outline"
                  onClick={() => {
                    const phone = (settlePhone || room.phone || "").replace(
                      /\D/g,
                      "",
                    )
                    if (!/^010\d{8}$/.test(phone)) {
                      toast("010으로 시작하는 11자리 번호를 입력해 주세요.")
                      return
                    }
                    if (sessionPhone && phone === sessionPhone) {
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
          거주 종료 · 세입자 확인 없음 · 72시간 동안 답이 오지 않아 집주인 쪽이
          혼자 남겼어요.
        </Notice>
      )}
      {page === "checkout" && (
        <RoomFinancials
          room={room}
          role={role}
          items={settlements}
          onAction={commit}
          onAdd={() => open("settlement")}
          actions={settlementAvailableActions(room, role, { readOnly })}
          itemActionsOf={(s) =>
            settlementItemAvailableActions(s, role, { readOnly })
          }
          transferActionsOf={(t) =>
            transferAvailableActions(t, role, { readOnly })
          }
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
  )
}
