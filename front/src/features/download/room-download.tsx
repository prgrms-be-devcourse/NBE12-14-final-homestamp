import { useState } from "react"
import { Download, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Panel } from "@/components/common/blocks"
import { useApi } from "@/api/hooks"
import { errorMessage } from "@/api/errors"
import type { RoomDownloadBody } from "@/api/downloads"
import {
  buildRoomZip,
  exportFileName,
  type ExportProgress,
  type ExportResult,
} from "@/lib/room-export"

type Step =
  | { kind: "idle" }
  | { kind: "preparing" }
  | { kind: "ready"; download: RoomDownloadBody }
  | { kind: "running"; progress: ExportProgress }
  | { kind: "done"; result: Omit<ExportResult, "zip">; fileName: string }

// 브라우저에 파일로 저장해요(서버를 거치지 않음)
function saveFile(bytes: Uint8Array, fileName: string) {
  const url = URL.createObjectURL(
    new Blob([bytes as BlobPart], { type: "application/zip" }),
  )
  const a = document.createElement("a")
  a.href = url
  a.download = fileName
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

// S-19 내려받기(DL-1 최소판): 받기 전에 사진 장 수를 보이고 → 진행 막대(50장씩, 받지 못한 사진만 다시) → ZIP 저장.
// 화면에는 "서비스에 남긴 기록의 사본"이라고만 써요(03 6-3). 진입점은 06의 S-18(내 정보)이 아직 없어 호실 화면에 둬요(#321)
export function RoomDownload({
  roomId,
  roomName,
}: {
  roomId: string
  roomName: string
}) {
  const api = useApi()
  const [step, setStep] = useState<Step>({ kind: "idle" })
  const [error, setError] = useState<string>()
  const prepare = async () => {
    setError(undefined)
    setStep({ kind: "preparing" })
    try {
      setStep({ kind: "ready", download: await api.downloads.get(roomId) })
    } catch (e) {
      setError(errorMessage(e))
      setStep({ kind: "idle" })
    }
  }
  const run = async (download: RoomDownloadBody) => {
    setError(undefined)
    setStep({
      kind: "running",
      progress: { done: 0, total: download.photoCount },
    })
    try {
      const { zip, ...result } = await buildRoomZip(download, {
        api: api.downloads,
        onProgress: (progress) => setStep({ kind: "running", progress }),
      })
      const fileName = exportFileName(roomName, new Date())
      saveFile(zip, fileName)
      setStep({ kind: "done", result, fileName })
    } catch (e) {
      // 사진 주소를 받지 못하면(서버가 볼 수 없는 사진이 섞였다며 전체를 거절하는 등) 저장하지 않고 멈춰요
      setError(`사진을 받지 못해 내려받기를 멈췄어요. ${errorMessage(e)}`)
      setStep({ kind: "ready", download })
    }
  }
  return (
    <Panel className="mt-5" title="기록 내려받기">
      <p className="muted">
        서비스에 남긴 기록의 사본이에요. 내가 볼 수 있는 행동 기록(기록.json)과
        원본 사진을 ZIP 파일 하나로 받아요.
      </p>
      {error && (
        <p role="alert" className="mt-3 text-[13px] text-destructive">
          {error}
        </p>
      )}
      <div className="actions mt-4">
        {(step.kind === "idle" || step.kind === "preparing") && (
          <Button
            variant="outline"
            disabled={step.kind === "preparing"}
            onClick={prepare}
          >
            {step.kind === "preparing" ? (
              <Loader2 className="animate-spin" />
            ) : (
              <Download />
            )}
            내려받기 준비
          </Button>
        )}
        {step.kind === "ready" && (
          <>
            <p className="muted">
              {`행동 기록 ${step.download.record.actions.length}건 · 사진 ${step.download.photoCount}장`}
            </p>
            <Button onClick={() => run(step.download)}>
              <Download />
              ZIP으로 받기
            </Button>
          </>
        )}
        {step.kind === "running" && (
          <div className="w-full">
            <p className="muted">
              {step.progress.total
                ? `사진 받는 중 · ${step.progress.done} / ${step.progress.total}장`
                : "기록을 묶는 중이에요"}
            </p>
            <progress
              className="mt-2 w-full"
              max={step.progress.total || 1}
              value={step.progress.total ? step.progress.done : 0}
              aria-label="내려받기 진행"
            />
          </div>
        )}
        {step.kind === "done" && (
          <div>
            <p>{`${step.fileName}을 저장했어요.`}</p>
            {step.result.erased.length > 0 && (
              <p className="muted">{`받는 도중 지워진 사진 ${step.result.erased.length}장은 파일 없이 기록에만 남아요.`}</p>
            )}
            {step.result.missing.length > 0 && (
              <p className="text-[13px] text-destructive">{`사진 ${step.result.missing.length}장을 받지 못했어요. 잠시 뒤 다시 받아 주세요.`}</p>
            )}
            {step.result.mismatched.length > 0 && (
              <p className="text-[13px] text-destructive">{`사진 ${step.result.mismatched.length}장의 지문값이 기록과 달라요. 다시 받아 주세요.`}</p>
            )}
            <Button
              className="mt-3"
              variant="outline"
              onClick={() => setStep({ kind: "idle" })}
            >
              다시 받기
            </Button>
          </div>
        )}
      </div>
    </Panel>
  )
}
