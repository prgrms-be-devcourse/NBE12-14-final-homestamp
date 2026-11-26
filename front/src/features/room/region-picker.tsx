import { useEffect, useRef, useState } from "react"
import { Loader2, MapPin, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Choice } from "@/components/common/blocks"
import { useApi } from "@/api/hooks"
import { errorMessage } from "@/api/errors"
import type { Region, RegionOption } from "@/api/types"
import {
  REGION_QUERY_MAX,
  regionName,
  skippedSigungu,
  steppedRegion,
} from "@/lib/region"

// 단계 목록에서 고를 수 있는 이름만(이름 없는 칸은 저절로 고르므로 목록에 넣지 않아요)
const names = (list: RegionOption[]) =>
  list.flatMap((x) => (x.name ? [x.name] : []))

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
  // 시·도·시·군·구를 고를 때마다 늘어나는 번호. 늦게 온 목록 응답이 새로 고른 칸을 덮지 않게 해요.
  const pickTicket = useRef(0)

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
  // 단계 목록을 못 불러오면 빈 목록으로 두지 않고 까닭을 보여 줘요(로그인 만료·요청 제한 등).
  // 그사이 다른 칸을 다시 골랐으면(늦게 온 응답) null을 돌려주고 화면을 바꾸지 않아요.
  async function load(parent: string, ticket: number) {
    setError("")
    try {
      const list = await api.listRegions(parent)
      return ticket === pickTicket.current ? list : null
    } catch (e) {
      if (ticket !== pickTicket.current) return null
      setError(errorMessage(e))
      return []
    }
  }
  async function pickSido(name: string) {
    const ticket = ++pickTicket.current
    const o = sidos.find((x) => x.name === name)
    setSido(o)
    setSigungu(undefined)
    setSigungus([])
    setDongs([])
    if (!o) return
    const list = await load(o.code, ticket)
    if (!list) return
    // 세종: 이름 없는 시·군·구 한 칸이면 저절로 고르고 바로 읍·면·동을 불러요
    const skip = skippedSigungu(list)
    if (!skip) {
      setSigungus(list)
      return
    }
    setSigungu(skip)
    const dongList = await load(skip.code, ticket)
    if (dongList) setDongs(dongList)
  }
  async function pickSigungu(name: string) {
    const ticket = ++pickTicket.current
    const o = sigungus.find((x) => x.name === name)
    setSigungu(o)
    setDongs([])
    if (!o) return
    const list = await load(o.code, ticket)
    if (list) setDongs(list)
  }
  function pickDong(name: string) {
    const o = dongs.find((x) => x.name === name)
    const region = o && sido && sigungu && steppedRegion(sido, sigungu, o)
    if (region) setPicked(region)
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
                maxLength={REGION_QUERY_MAX}
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
                values={names(sidos)}
                onChange={(v) => void pickSido(v)}
              />
              {/* 세종처럼 시·군·구가 없으면 "없음"으로 보이고 고를 수 없어요 */}
              <Choice
                label="시·군·구"
                value={sigungu ? (sigungu.name ?? "없음") : ""}
                values={names(sigungus)}
                disabled={!!sigungu && sigungu.name === null}
                onChange={(v) => void pickSigungu(v)}
              />
              <Choice
                label="읍·면·동"
                value=""
                values={names(dongs)}
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
