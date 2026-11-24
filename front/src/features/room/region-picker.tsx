import { useEffect, useState } from "react"
import { Loader2, MapPin, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Choice } from "@/components/common/blocks"
import { useApi } from "@/api/hooks"
import { errorMessage } from "@/api/errors"
import type { Region, RegionOption } from "@/api/types"
import { regionName } from "@/lib/region"

// 호실 주소(06 ROOM-1, 4-3): 법정동 코드표에서 동까지 골라요. 동 이름으로 찾거나 시·도 → 시·군·구 → 읍·면·동으로 고르고,
// 저장하는 값은 법정동 코드(regionCode) 하나예요. 번지·건물명은 받지 않아요(decisions 2026-11-13).
// form에는 숨은 칸 두 개가 들어가요: regionCode(보낼 값), address(화면에 보일 이름)
export function RegionPicker({ inputId }: { inputId: string }) {
  const api = useApi()
  const [picked, setPicked] = useState<Region | null>(null)
  const [mode, setMode] = useState<"search" | "step">("search")
  const [q, setQ] = useState("")
  const [results, setResults] = useState<Region[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  // 단계 선택: 고른 시·도·시·군·구 코드와 그 아래 목록
  const [sidos, setSidos] = useState<RegionOption[]>([])
  const [sido, setSido] = useState<RegionOption>()
  const [sigungus, setSigungus] = useState<RegionOption[]>([])
  const [sigungu, setSigungu] = useState<RegionOption>()
  const [dongs, setDongs] = useState<RegionOption[]>([])

  useEffect(() => {
    if (mode !== "step" || sidos.length) return
    api.listRegions().then(setSidos, (e) => setError(errorMessage(e)))
  }, [api, mode, sidos.length])

  async function search() {
    if (!q.trim()) {
      setError("동 이름을 적어 주세요. 예: 연남동")
      return
    }
    setBusy(true)
    setError("")
    setResults(null)
    try {
      setResults(await api.searchRegions(q.trim()))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }
  async function pickSido(name: string) {
    const o = sidos.find((x) => x.name === name)
    setSido(o)
    setSigungu(undefined)
    setDongs([])
    setSigungus(o ? await api.listRegions(o.code).catch(() => []) : [])
  }
  async function pickSigungu(name: string) {
    const o = sigungus.find((x) => x.name === name)
    setSigungu(o)
    setDongs(o ? await api.listRegions(o.code).catch(() => []) : [])
  }
  function pickDong(name: string) {
    const o = dongs.find((x) => x.name === name)
    if (!o || !sido || !sigungu) return
    setPicked({
      code: o.code,
      sido: sido.name,
      // 시·군·구가 없는 곳(세종)은 단계 목록에 시·도 이름이 한 번 더 나와요
      sigungu: sigungu.name === sido.name ? null : sigungu.name,
      dong: o.name,
    })
  }

  return (
    <div className="flex flex-col gap-2">
      <input type="hidden" name="regionCode" value={picked?.code ?? ""} />
      <input
        type="hidden"
        name="address"
        value={picked ? regionName(picked) : ""}
      />
      {picked ? (
        <div className="flex flex-wrap items-center gap-3 rounded-[12px] border border-line bg-soft px-4 py-3">
          <MapPin size={18} className="flex-none text-brand" aria-hidden />
          <div className="min-w-0 flex-1">
            <strong className="block text-base text-ink">
              {regionName(picked)}
            </strong>
            <small className="text-[13px] text-sub">
              번지·건물명은 저장하지 않아요.
            </small>
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setPicked(null)}
          >
            다시 고르기
          </Button>
        </div>
      ) : (
        <>
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              variant={mode === "search" ? "default" : "outline"}
              onClick={() => setMode("search")}
            >
              동 이름으로 찾기
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "step" ? "default" : "outline"}
              onClick={() => setMode("step")}
            >
              시·도부터 고르기
            </Button>
          </div>
          {mode === "search" ? (
            <div className="flex gap-2">
              <Input
                id={inputId}
                value={q}
                placeholder="예: 연남동"
                onChange={(e) => setQ(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault()
                    void search()
                  }
                }}
              />
              <Button
                type="button"
                variant="outline"
                className="shrink-0"
                disabled={busy}
                onClick={() => void search()}
              >
                {busy ? (
                  <Loader2 className="animate-spin" aria-hidden />
                ) : (
                  <Search aria-hidden />
                )}
                찾기
              </Button>
            </div>
          ) : (
            <div className="grid gap-2 sm:grid-cols-3">
              <Choice
                label="시·도"
                value={sido?.name ?? ""}
                values={sidos.map((x) => x.name)}
                onChange={(v) => void pickSido(v)}
              />
              <Choice
                label="시·군·구"
                value={sigungu?.name ?? ""}
                values={sigungus.map((x) => x.name)}
                onChange={(v) => void pickSigungu(v)}
              />
              <Choice
                label="읍·면·동"
                value=""
                values={dongs.map((x) => x.name)}
                onChange={pickDong}
              />
            </div>
          )}
          {mode === "search" && results && (
            <ul className="flex flex-col gap-1">
              {results.length === 0 && (
                <li className="muted">
                  찾은 동이 없어요. 시·도부터 골라 보세요.
                </li>
              )}
              {results.map((r) => (
                <li key={r.code}>
                  <Button
                    type="button"
                    variant="ghost"
                    className="w-full justify-start"
                    onClick={() => setPicked(r)}
                  >
                    <MapPin aria-hidden />
                    {regionName(r)}
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
    </div>
  )
}
