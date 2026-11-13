import { useRef, type ReactNode } from "react"
import { Link2, ShieldCheck } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Empty, Panel, Status, Timeline } from "@/components/prototype"
import { Notice } from "@/components/notice"
import { PhotoGallery } from "@/components/photo-gallery"
import { RecordDescription } from "@/components/record-description"
import type {
  DemoState,
  Inspection,
  PartPhoto,
  Repair,
  Role,
  Room,
} from "@/data/demo"
import { hasAction, type RepairAction } from "@/api/actions"
import { repairAvailableActions } from "@/api/mock/repair-actions"
import { RepairDetail } from "@/features/repair/repair-detail"
import { RepairActions, RepairBadges } from "@/features/repair/repair-status"
import {
  CorrectionHistory,
  RepairCorrections,
  type RepairCorrectionsHandle,
  PhotoVisibility,
  visibleBefore,
  visibleAfter,
  beforePhotos,
  WrongPhotos,
  RepairMaskedNotes,
} from "@/features/repair/repair-records"
import { CauseNotes, CostAgreement } from "@/features/repair/repair-discussions"
import { RepairPayments } from "@/features/repair/repair-payments"
import { afterMasks, beforeMasks, type maskOf } from "@/lib/photo-privacy"
import { safeSharedText } from "@/lib/service-rules"
import { plainText } from "@/lib/plain-words"
import { sideName } from "@/lib/words"

type OnAction = (text: string, update: (r: Repair) => void) => void
const afterConfirmPhotos = (r: Repair) =>
  (r.notes || [])
    .filter((n) => n.kind.includes("확인 뒤 추가"))
    .reduce((s, n) => s + Number(n.text.match(/사진 (\d+)장/)?.[1] || 0), 0)

export interface RepairDetailPageProps {
  r: Repair
  room: Room
  role: Role
  db: DemoState
  isGuest?: boolean
  readOnly: boolean
  onBack: () => void
  onAction: OnAction
  onOpenModal: (m: "note" | "reconfirm" | "after" | "repair") => void
  onRecur: (r: Repair) => void
  onOpenRepair: (id: string) => void
  onCopyShareLink: (kind: "repair" | "inspection", id: string) => void
  // 호실 행동(초대)은 아직 호실의 availableActions로 옮기기 전이라 App이 정해서 넘겨요.
  onInvite?: () => void
  canAddRepair: boolean
  partPhotos: PartPhoto[]
  inspections: Inspection[]
  renderPartPhoto: (p: PartPhoto) => ReactNode
  itemMasks: (i: Inspection) => ReturnType<typeof maskOf>[]
  moneyPrefill?: { from: Role; to: Role; amount: number }
  onPrefillUsed: () => void
}

// S-12 수리 건 상세. 버튼은 서버가 준 availableActions로만 그려요(08 0장 원칙 6, 09 5장).
export function RepairDetailPage({
  r,
  room,
  role,
  db,
  isGuest = false,
  readOnly,
  onBack,
  onAction,
  onOpenModal,
  onRecur,
  onOpenRepair,
  onCopyShareLink,
  onInvite,
  canAddRepair,
  partPhotos,
  inspections,
  renderPartPhoto,
  itemMasks,
  moneyPrefill,
  onPrefillUsed,
}: RepairDetailPageProps) {
  const correctionsRef = useRef<RepairCorrectionsHandle>(null)
  // 서버가 연결되면 GET /api/repair-cases/{id} 응답의 availableActions를 그대로 써요.
  const actions = repairAvailableActions(r, room, role, {
    guest: isGuest,
    readOnly,
  })
  const has = (a: RepairAction) => hasAction(actions, a)
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
  const sameZoneItems = inspections.filter(
    (i) => i.roomId === room.id && i.kind === "in" && i.zone === r.zone,
  )
  const sameZonePhotos = partPhotos.filter(
    (p) => p.kind === "in" && p.zone === r.zone,
  )
  return (
    <RepairDetail
      r={r}
      room={room}
      role={role}
      events={evs}
      isGuest={isGuest}
      backLabel={`${room.name} 수리 기록`}
      onBack={onBack}
      notices={
        <>
          <RepairBadges
            compact
            r={r}
            room={db.rooms.find((x) => x.id === r.roomId) || room}
            repairs={
              isGuest ? [] : db.repairs.filter((x) => x.roomId === r.roomId)
            }
            onOpenRepair={isGuest ? undefined : onOpenRepair}
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
          (!r.hidden && !(r.photos.length > 0 && !visibleBefore(r).length)) ? (
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
                        masks={afterMasks(r, record.id, record.photos, isGuest)}
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
            onAction={onAction}
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
        actions.length ? (
          <RepairActions
            r={r}
            room={room}
            role={role}
            actions={actions}
            onAction={onAction}
            onOpen={onOpenModal}
            onRecur={onRecur}
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
          onAction={onAction}
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
            onAction={onAction}
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
            onAction={onAction}
            prefill={moneyPrefill}
            onPrefillUsed={onPrefillUsed}
          />
        ) : undefined
      }
      causes={
        !isGuest ? (
          <CauseNotes
            repair={r}
            role={role}
            readOnly={readOnly || room.status !== "거주 중"}
            onAction={onAction}
          />
        ) : undefined
      }
      sameZone={
        !isGuest && (
          <Panel title="같은 부위의 입주 기록">
            {sameZonePhotos.map((p) => (
              <div key={p.id}>{renderPartPhoto(p)}</div>
            ))}
            {sameZoneItems.map((i) => (
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
                      onClick={() => onCopyShareLink("inspection", i.id)}
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
            {!sameZoneItems.length && !sameZonePhotos.length && (
              <p className="muted">입주 점검에 같은 부위의 기록이 없어요.</p>
            )}
          </Panel>
        )
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
              onClick={() => onCopyShareLink("repair", r.id)}
            >
              <Link2 />
              공유 링크 복사
            </Button>
          )}
        </>
      }
      photoSettings={
        canAct ? (
          <PhotoVisibility r={r} role={role} onAction={onAction} />
        ) : undefined
      }
      onEdit={
        has("corrections")
          ? () => correctionsRef.current?.openEdit()
          : undefined
      }
      onAddPhotos={
        has("photos") && r.author === role
          ? () => correctionsRef.current?.openPhotos()
          : undefined
      }
      onNewRepair={canAddRepair ? () => onOpenModal("repair") : undefined}
      onShare={onInvite}
    />
  )
}
