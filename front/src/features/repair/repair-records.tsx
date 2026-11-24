import { notInSpec, photoIdOf, post, type RecordRequest } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { useImperativeHandle, useState, type Ref } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Choice } from "@/components/common/blocks"
import { ActionDialog } from "@/components/common/action-dialog"
import { tryOn } from "@/lib/try-on"
import { CorrectionPanel } from "@/components/common/correction-box"
import {
  other,
  partGroups,
  type FactCorrection,
  type PhotoMask,
  type Repair,
  type Role,
} from "@/data/demo"
import { readPhotos } from "@/lib/photos"
import {
  acceptAllCorrections,
  answerCorrection,
  isHidden,
  repairStamped,
  resubmitRepairCorrection,
  requestRepairEdits,
  togglePhoto,
  toggleWrongPhoto,
  withdrawCorrection,
} from "@/lib/repair-rules"
import { pendingValue } from "@/lib/corrections"
import { versionOf } from "@/lib/versions"
import type { RepairCommand } from "@/features/repair/use-repair-case"
import type { CorrectionAction } from "@/api/actions"
import { sideText } from "@/lib/words"
import { Fold } from "@/components/common/fold"
import { MaskedPhotoNotes, PhotoManageList } from "@/features/photo/photo-masks"
import {
  maskOf,
  maskPhoto,
  objectToMask,
  uploaderOf,
} from "@/lib/photo-privacy"
import { Image as ImageIcon } from "lucide-react"

// request는 06 4장의 주소(api/records), text·update는 목업이 서버 대신 쓰는 값이에요
type OnAction = (
  request: RecordRequest,
  text: string,
  update: (r: Repair) => void,
) => void

// 수리 건의 고치기(제목·설명·부위)와 사진 추가 창. 버튼은 상세 화면의 행동 영역에 하나씩만 두고 여기서는 창과 확인 대기 요약만 그려요.
export interface RepairCorrectionsHandle {
  openEdit: () => void
  openPhotos: () => void
}
export function RepairCorrections({
  r,
  role,
  readOnly,
  guest,
  onAction,
  onCommand,
  correctionActionsOf,
  ref,
}: {
  r: Repair
  room?: unknown
  role: Role
  readOnly: boolean
  guest?: boolean
  onAction: OnAction
  // 수리 건 API로 보내는 행동(사진 추가)
  onCommand: (call: RepairCommand) => Promise<string | undefined>
  // 고친 내용마다 서버가 준 지금 누를 수 있는 행동(availableActions)
  correctionActionsOf: (
    c: FactCorrection<string | number>,
    list: readonly FactCorrection<string | number>[],
  ) => readonly CorrectionAction[]
  ref?: Ref<RepairCorrectionsHandle>
}) {
  const [mode, setMode] = useState<"edit" | "photos" | null>(null)
  const [zone, setZone] = useState("욕실")
  const [part, setPart] = useState("천장")
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  const run = (
    request: RecordRequest,
    text: string,
    update: (x: Repair) => void,
  ) => {
    const e = tryOn(r, update)
    if (e) {
      setError(e)
      return e
    }
    setError("")
    onAction(request, text, update)
  }
  const list = r.corrections || []
  const openEdit = () => {
    const [z, p] = String(pendingValue(list, "부위") ?? r.zone).split(" · ")
    setZone(partGroups[z] ? z : "기타")
    setPart(p || "기타")
    setError("")
    setMode("edit")
  }
  const openPhotos = () => {
    setPhotos([])
    setError("")
    setMode("photos")
  }
  useImperativeHandle(ref, () => ({ openEdit, openPhotos }))
  const them = other(role)
  // 도장 전이면 바로 고쳐지고, 도장 뒤면 상대 확인을 받아요(상태와 규칙 G).
  const stamped = repairStamped(r)
  const afterConfirm =
    r.ack === "확인됨" || (r.status === "처리 완료" && !r.soloClosed)
  const summary = (
    <CorrectionPanel
      list={list}
      stageOf={(c) => r.waits?.[`fix:${c.id}`]}
      role={role}
      guest={guest}
      readOnly={readOnly}
      actionsOf={(c) => correctionActionsOf(c, list)}
      onRespond={(c, answer, reason) =>
        answer === "ACK"
          ? run(
              post(endpoints.correctionAction(c.id, "respond"), {
                answer: "ACK",
                seenVersionNo: versionOf(r),
              }),
              `고친 내용 확인(${c.field}) · 본 모습 ${versionOf(r)}번`,
              (x) => answerCorrection(x, role, c.id, true),
            )
          : run(
              post(endpoints.correctionAction(c.id, "respond"), {
                answer,
                reason,
              }),
              `고친 내용 ${answer === "NEED_MORE" ? "보충 요청" : "이견"}(${c.field}) · ${reason}`,
              (x) =>
                answerCorrection(
                  x,
                  role,
                  c.id,
                  false,
                  reason,
                  answer === "NEED_MORE" ? "보류" : "이견",
                ),
            )
      }
      onResubmit={(c, data) =>
        run(
          post(endpoints.correctionAction(c.id, "resubmit"), data),
          `고친 내용 사진 더해서 다시 요청(${c.field})${data.text ? ` · ${data.text}` : ""}${data.photos.length ? ` · 사진 ${data.photos.length}장` : ""}`,
          (x) => resubmitRepairCorrection(x, role, c.id, data),
        )
      }
      onWithdraw={(c) =>
        run(
          post(endpoints.correctionAction(c.id, "withdraw")),
          `고친 내용 거둠(${c.field})`,
          (x) => withdrawCorrection(x, role, c.id),
        )
      }
      onAcceptAll={() =>
        run(
          post(endpoints.acceptAllCorrections, {
            target: { kind: "repair", id: r.id },
            seenVersionNo: versionOf(r),
          }),
          "고친 내용 모두 확인",
          (x) => acceptAllCorrections(x, role),
        )
      }
    />
  )
  return (
    <>
      {summary}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <ActionDialog
        open={mode === "edit"}
        title="고치기"
        description="바꿀 칸만 고쳐 주세요. 사진은 [사진 추가]로 더하고, 수리비 부담은 수리비 카드의 새 제안으로 바꿔요."
        submitLabel={stamped ? "확인 요청" : "고치기"}
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const z =
            zone === "기타" ? String(f.get("customZone") || "").trim() : zone
          const p =
            part === "기타" ? String(f.get("customPart") || "").trim() : part
          if (!z || !p) return "구역과 부위를 입력해 주세요."
          const next = {
            title: String(f.get("title") || ""),
            description: String(f.get("description") || ""),
            zone: `${z} · ${p}`,
          }
          return run(
            post(endpoints.corrections, {
              target: { kind: "repair", id: r.id },
              fields: next,
              reason: String(f.get("reason") || ""),
            }),
            stamped ? "고친 내용 남김 · 상대 확인 전" : "고쳤어요",
            (x) => {
              requestRepairEdits(x, role, next, String(f.get("reason") || ""))
            },
          )
        }}
      >
        <div className="field">
          <label htmlFor="fix-title">제목 · 필수</label>
          <Input
            id="fix-title"
            name="title"
            maxLength={80}
            defaultValue={String(pendingValue(list, "제목") ?? r.title)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="fix-desc">설명</label>
          <Textarea
            id="fix-desc"
            name="description"
            defaultValue={String(pendingValue(list, "설명") ?? r.description)}
            maxLength={2000}
          />
        </div>
        <div className="form-grid">
          <div className="field">
            <label>구역</label>
            <Choice
              label="구역"
              value={zone}
              values={Object.keys(partGroups)}
              onChange={(v) => {
                setZone(v)
                setPart(partGroups[v][0])
              }}
            />
          </div>
          <div className="field">
            <label>부위</label>
            <Choice
              label="부위"
              value={part}
              values={Array.from(
                new Set([...(partGroups[zone] || []), "기타"]),
              )}
              onChange={setPart}
            />
          </div>
        </div>
        {zone === "기타" && (
          <div className="field">
            <label htmlFor="fix-zone">구역 이름 · 필수</label>
            <Input id="fix-zone" name="customZone" required />
          </div>
        )}
        {part === "기타" && (
          <div className="field">
            <label htmlFor="fix-part">부위 이름 · 필수</label>
            <Input id="fix-part" name="customPart" required />
          </div>
        )}
        <div className="field">
          <label htmlFor="fix-reason">
            왜 바꾸나요{stamped ? " · 필수" : " · 선택"}
          </label>
          <Textarea
            id="fix-reason"
            name="reason"
            required={stamped}
            placeholder="예: 다시 보니 천장이 아니라 벽 위쪽에서 새고 있었어요."
          />
        </div>
        {list.some((c) => c.status === "확인 대기") && (
          <p className="fix-note">
            확인을 기다리는 고친 내용이 채워져 있어요. 다시 고친 칸은 이전 고친
            내용이 ‘새로 고쳐서 바뀜’으로 닫히고, 확인 요청은 하나로 묶여요.
          </p>
        )}
        <p className="fix-notice">
          {stamped
            ? `고친 내용을 남겨요. ${sideText(them)}이 확인하면 바뀌어요.`
            : `${sideText(them)}이 아직 확인하기 전이라 바로 고쳐져요.`}
        </p>
      </ActionDialog>
      <ActionDialog
        open={mode === "photos"}
        title="사진 추가"
        description="사진은 바꾸지 않고 더하기만 해요. 기존 사진과 하자 내용·확인 상태는 그대로예요. 엉뚱한 사진은 지우지 않고 ‘잘못 올린 사진’ 표시를 붙여 주세요."
        submitLabel="사진 추가"
        onClose={() => setMode(null)}
        onSubmit={async () => {
          // 사진 추가는 수리 건 API로 보내요(POST /repair-cases/{id}/photos)
          const e = await onCommand((api, sess) =>
            api.addPhotos(sess, r.id, { photos }),
          )
          setError(e ?? "")
          return e
        }}
      >
        <div className="field">
          <label htmlFor="add-photos">
            사진 · 계약서·신분증·통장 사진은 올리지 마세요
          </label>
          <Input
            id="add-photos"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            onChange={(e) =>
              readPhotos(e.target.files, photos.length)
                .then((p) => setPhotos((o) => [...o, ...p]))
                .catch((err) => setError(err.message))
            }
          />
          {photos.length > 0 && (
            <small className="muted">{photos.length}장 선택됨</small>
          )}
        </div>
        {afterConfirm && (
          <p className="fix-notice">
            상대가 확인한 뒤라 ‘확인 뒤 추가’로 표시돼요.
          </p>
        )}
      </ActionDialog>
    </>
  )
}

export function WrongPhotos({
  photos,
  masks,
}: {
  photos: string[]
  masks?: (PhotoMask | undefined)[]
}) {
  if (!photos.length) return null
  return (
    <div className="mt-2">
      <small className="muted block">
        잘못 올린 사진 표시 · {photos.length}장 (지우지 않고 흐리게 맨 뒤에
        둬요)
      </small>
      <div className="flex flex-wrap gap-2 mt-1">
        {photos.map((src, n) =>
          masks?.[n] ? (
            <span
              key={n}
              className="photo-masked masked-note-thumb"
              title="개인정보 이유로 가림"
            >
              <img src={src} alt="" />
              <span className="sr-only">
                잘못 올린 사진 {n + 1} · 개인정보 이유로 가림
              </span>
            </span>
          ) : (
            <img
              key={n}
              src={src}
              alt={`잘못 올린 사진 ${n + 1}`}
              className="h-12 w-12 rounded object-cover opacity-40"
            />
          ),
        )}
      </div>
    </div>
  )
}

/* ── 개인정보 이유로 가린 사진(팀 회의 결정: 삭제 없음, 흐리게 가리고 상대는 이견) ── */
// 가린 사진 목록(누가·언제·이견)
export function RepairMaskedNotes({
  r,
  role,
  readOnly,
  onAction,
}: {
  r: Repair
  role: Role
  readOnly?: boolean
  onAction: OnAction
}) {
  const items = (r.privacyMasks || []).map((m) => {
    if (m.key.startsWith("b:")) {
      const n = Number(m.key.slice(2))
      return { mask: m, src: r.photos[n], label: `수리 전 사진 ${n + 1}` }
    }
    const [, id, i] = m.key.split(":")
    const a = r.afterRecords?.find((x) => x.id === id)
    return {
      mask: m,
      src: a?.photos[Number(i)],
      label: `${a?.title ?? "수리 후"} 사진 ${Number(i) + 1}`,
    }
  })
  return (
    <MaskedPhotoNotes
      items={items}
      role={role}
      readOnly={readOnly}
      onObject={(key, reason) => {
        const label = items.find((it) => it.mask.key === key)?.label ?? "사진"
        const update = (x: Repair) => objectToMask(x, key, role, reason)
        const e = tryOn(r, update)
        if (e) return e
        onAction(
          notInSpec("가린 사진에 이견 달기"),
          `가린 사진에 이견 · ${label}`,
          update,
        )
      }}
    />
  )
}
export function PhotoVisibility({
  r,
  role,
  onAction,
}: {
  r: Repair
  role: Role
  onAction: OnAction
}) {
  const items: { key: string; src: string; label: string }[] = []
  if (r.author === role)
    r.photos.forEach((src, i) =>
      items.push({ key: `b:${i}`, src, label: `수리 전 ${i + 1}` }),
    )
  r.afterRecords
    ?.filter((a) => a.by === role)
    .forEach((a) =>
      a.photos.forEach((src, i) =>
        items.push({
          key: `a:${a.id}:${i}`,
          src,
          label: `${a.title} ${i + 1}`,
        }),
      ),
    )
  const labelOf = (keys: string[]) =>
    keys.map((k) => items.find((it) => it.key === k)?.label).join(", ")
  return (
    <Fold title="내 사진 관리" icon={<ImageIcon size={16} />}>
      {r.hidden && (
        <div className="info-box mt-2">
          <p>이전 방식으로 이 건의 사진 전체가 링크에서 숨겨져 있어요.</p>
          {r.author === role && (
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                onAction(
                  items.map((it) =>
                    post(
                      endpoints.photoAction(
                        photoIdOf(r.id, it.key),
                        "show-on-link",
                      ),
                    ),
                  ),
                  "링크 사진 전체 숨김 해제",
                  (x) => {
                    x.hidden = false
                  },
                )
              }
            >
              전체 숨김 해제
            </Button>
          )}
        </div>
      )}
      {items.length ? (
        <div className="mt-2">
          <PhotoManageList
            items={items.map((it) => {
              const b = it.key.startsWith("b:") ? Number(it.key.slice(2)) : -1
              return {
                ...it,
                canWrong: b >= 0,
                wrong: b >= 0 && Boolean(r.wrongPhotos?.includes(b)),
                masked: Boolean(maskOf(r, it.key)),
                note: isHidden(r, it.key) ? "링크에서 숨김" : undefined,
                extra: maskOf(r, it.key) ? undefined : (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() =>
                      onAction(
                        post(
                          endpoints.photoAction(
                            photoIdOf(r.id, it.key),
                            isHidden(r, it.key)
                              ? "show-on-link"
                              : "hide-on-link",
                          ),
                        ),
                        `사진 ${isHidden(r, it.key) ? "링크 공개" : "링크에서 숨김"} · ${it.label}`,
                        (x) => togglePhoto(x, it.key),
                      )
                    }
                  >
                    {r.hiddenPhotos?.includes(it.key)
                      ? "다시 공개"
                      : "링크에서 숨김"}
                  </Button>
                ),
              }
            })}
            onWrong={(keys, mark) => {
              const e = tryOn(r, (x) =>
                keys.forEach((k) =>
                  toggleWrongPhoto(x, role, Number(k.slice(2))),
                ),
              )
              if (e) return e
              onAction(
                keys.map((k) =>
                  post(
                    endpoints.photoAction(
                      photoIdOf(r.id, k),
                      mark ? "mark-wrong" : "unmark-wrong",
                    ),
                  ),
                ),
                `${mark ? "잘못 올린 사진 표시" : "잘못 올린 사진 표시 떼기"} · ${labelOf(keys)}`,
                (x) =>
                  keys.forEach((k) =>
                    toggleWrongPhoto(x, role, Number(k.slice(2))),
                  ),
              )
            }}
            onMask={(keys) => {
              const update = (x: Repair) =>
                keys.forEach((k) => maskPhoto(x, k, role, uploaderOf(x, k)))
              const e = tryOn(r, update)
              if (e) return e
              onAction(
                notInSpec(
                  "개인정보 사진 가림(06은 지우기·가린 사본 올리기 PHO-5·7)",
                ),
                `개인정보 이유로 사진 가림 · ${labelOf(keys)}`,
                update,
              )
            }}
          />
        </div>
      ) : (
        <p className="muted mt-2">내가 올린 사진이 없어요.</p>
      )}
    </Fold>
  )
}
