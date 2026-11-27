import { useState } from "react"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Checkbox } from "@/components/ui/checkbox"
import { Notice } from "@/components/common/notice"
import { FormDialog } from "@/components/common/form-dialog"
import { PartField } from "@/components/common/part-field"
import { PhotoField } from "@/components/common/photo-field"
import { inspectionIdOf, post } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { uid, type Role, type Room } from "@/data/demo"
import { ensureAuthor, kindLabel, sessionOf } from "@/lib/inspection-rules"
import { isLateMoveInInspection } from "@/lib/workflows"
import { sideText } from "@/lib/words"
import { ZONE_PART_ERROR, type Commit } from "@/lib/record-commands"
import { partLabel, type RoomPart } from "@/lib/room-parts"

// 점검 기록 남기기(S-14, INSP).
// 입주 점검: 하자가 없으면 '부위 사진'(상대 확인 없이 등록 시각만), 하자가 있으면 '하자 항목'.
// 퇴거 점검: 하자 항목만. 하자 항목만으로는 확인 요청이 가지 않아요(점검 전체의 '점검 확인 요청'으로만).
export function InspectionItemForm({
  kind,
  room,
  role,
  locked,
  defectClosed,
  commit,
  onCancel,
}: {
  kind: "in" | "out"
  room: Room
  role: Role
  // 점검이 잠김 / 확인 요청을 보낸 뒤(하자는 [빠진 하자 추가]로만)
  locked: boolean
  defectClosed: boolean
  commit: Commit
  onCancel: () => void
}) {
  const roomId = room.id
  const [part, setPart] = useState<RoomPart>()
  const [photos, setPhotos] = useState<string[]>([])
  const [hasDefect, setHasDefect] = useState(false)
  const [error, setError] = useState("")
  // 입주 점검에서 하자를 고르지 않으면 부위 사진으로 남겨요
  const basicPhotoMode = kind === "in" && !hasDefect
  const partField = (
    <PartField
      roomId={roomId}
      value={part?.id ?? ""}
      pickPart={kind !== "in"}
      onChange={setPart}
    />
  )
  const photoField = (
    <PhotoField
      label="사진 기록"
      required={basicPhotoMode}
      photos={photos}
      onChange={setPhotos}
      onError={setError}
    />
  )
  const submit = (text: (name: string) => string) => {
    if (!part) {
      setError(ZONE_PART_ERROR)
      return
    }
    // 부위는 roomPartId로 보내고(06 4-7), 화면 글자는 그 부위에서 만들어요
    const roomPartId = part.id
    const where = partLabel(part)
    if (basicPhotoMode) {
      if (!photos.length) {
        setError("부위 사진을 한 장 이상 첨부해 주세요.")
        return
      }
      const title = text("title") || where + " 입주 부위 사진"
      if (room.moveIn && room.moveIn.author !== role) {
        setError(
          `입주 점검은 호실에 하나예요. ${sideText(room.moveIn.author)}이 기준 기록을 쓰고 있어요.`,
        )
        return
      }
      commit(
        post(
          endpoints.inspectionAction(
            inspectionIdOf(roomId, "in"),
            "part-photos",
          ),
          {
            roomPartId,
            title,
            description: text("photoMemo"),
            photos,
          },
        ),
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
            roomPartId,
            zone: where,
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
    if (locked) {
      setError("점검이 잠겨 있어요. 기존 하자 기록은 고치기로 덧붙여 주세요.")
      return
    }
    const kindSession = sessionOf(room, kind)
    if (
      kindSession &&
      (kindSession.author !== role || kindSession.status !== "작성 중")
    ) {
      setError(
        kindSession.author !== role
          ? `${kindLabel(kind)} 점검은 호실에 하나예요. ${sideText(kindSession.author)}이 기준 기록을 쓰고 있어요.`
          : "확인 요청을 보낸 뒤에는 하자를 [빠진 하자 추가]로 덧붙여요.",
      )
      return
    }
    const inspectionId = uid()
    commit(
      post(endpoints.inspectionAction(inspectionIdOf(roomId, kind), "items"), {
        title: text("title"),
        description: text("description"),
        photoMemo: text("photoMemo") || undefined,
        roomPartId,
        photos,
      }),
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
          roomPartId,
          zone: where,
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
  }
  return (
    <FormDialog
      title={kind === "in" ? "입주 당시 기록 남기기" : "퇴거 점검 기록"}
      description={
        basicPhotoMode
          ? "입주 당시 부위 사진을 남기고, 퇴거할 때 같은 부위와 비교해요."
          : undefined
      }
      error={error}
      onError={setError}
      onCancel={onCancel}
      onSubmit={submit}
    >
      {kind === "in" ? (
        <>
          {partField}
          <p className="muted">
            퇴거 사진과 비교하도록 구역과 부위를 선택해 주세요.
          </p>
          {isLateMoveInInspection(room) && (
            <Notice>
              입주 후 등록 · 입주일({room.since})보다 늦게 남기는 기록이에요.
              입주 당시 상태와 다를 수 있어요.
            </Notice>
          )}
          {photoField}
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
              checked={hasDefect}
              disabled={locked || defectClosed}
              onCheckedChange={(checked) => {
                setHasDefect(Boolean(checked))
                setError("")
              }}
            />
            입주 당시 발견한 하자가 있어요
          </label>
          {hasDefect ? (
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
                기존 하자 기록으로 저장해요. 입주 점검 작성을 마친 뒤 ‘점검 확인
                요청’을 누르면 점검 전체가 상대에게 보여요.
              </p>
            </>
          ) : (
            <p className="muted">
              부위 사진으로 저장해요. 하자 기록이 아니라서 상대 확인 없이 등록
              시각만 남아요.
            </p>
          )}
          {(locked || defectClosed) && (
            <p className="muted">
              {locked
                ? "하자 점검은 잠겼어요. 하자 변경은 고치기로 덧붙여 주세요."
                : "확인 요청을 보낸 뒤라 하자는 [빠진 하자 추가]로 덧붙여요. 부위 사진은 올릴 수 있어요."}
            </p>
          )}
        </>
      ) : (
        <>
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
          {photoField}
        </>
      )}
    </FormDialog>
  )
}
