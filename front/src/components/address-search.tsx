import { useState } from "react"
import { Loader2, MapPin, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useApi } from "@/api"
import { errorMessage } from "@/api/errors"
import type { AddressRegion } from "@/api/types"

// 주소 검색(행정안전부 도로명주소 검색 API · 서버가 대신 불러요): [주소 검색] → 검색어 → 결과 고르기 → 시·도 · 시·군·구 · 읍·면·동만 채워요.
// 저장되는 값은 form의 name 칸 하나("시·도 시·군·구 읍·면·동")뿐이에요. 도로명·번지·건물명은 고를 때 보여 주기만 하고 저장하지 않아요.
export function AddressSearch({
  name,
  inputId,
}: {
  name: string
  inputId: string
}) {
  const api = useApi()
  const [open, setOpen] = useState(false)
  const [keyword, setKeyword] = useState("")
  const [results, setResults] = useState<AddressRegion[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [picked, setPicked] = useState<AddressRegion | null>(null)
  const value = picked ? `${picked.sido} ${picked.sigungu} ${picked.dong}` : ""

  async function search() {
    const q = keyword.trim()
    if (q.length < 2) {
      setError("도로명이나 동 이름을 2글자 이상 적어 주세요.")
      return
    }
    setBusy(true)
    setError("")
    setResults(null)
    try {
      setResults(await api.searchAddress(q))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <input type="hidden" name={name} value={value} />
      {picked ? (
        <div className="flex flex-wrap items-center gap-3 rounded-[12px] border border-line bg-soft px-4 py-3">
          <MapPin size={18} className="flex-none text-brand" aria-hidden />
          <div className="min-w-0 flex-1">
            <strong className="block text-base text-ink">{value}</strong>
            <small className="text-[13px] text-sub">
              번지·건물명은 저장하지 않아요.
            </small>
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              setPicked(null)
              setOpen(true)
            }}
          >
            다시 검색
          </Button>
        </div>
      ) : !open ? (
        <Button
          type="button"
          variant="outline"
          className="self-start"
          onClick={() => setOpen(true)}
        >
          <Search />
          주소 검색
        </Button>
      ) : (
        <div className="flex flex-col gap-2 rounded-[12px] border border-line p-3">
          <div className="flex gap-2">
            <Input
              id={inputId}
              autoFocus
              value={keyword}
              placeholder="예: 연남로 1, 연남동"
              onChange={(e) => setKeyword(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault()
                  void search()
                }
              }}
            />
            <Button
              type="button"
              className="flex-none"
              disabled={busy}
              onClick={() => void search()}
            >
              {busy && <Loader2 className="animate-spin" />}검색
            </Button>
          </div>
          {error && (
            <p role="alert" className="form-error">
              {error}
            </p>
          )}
          {results &&
            (results.length ? (
              <ul
                className="m-0 flex max-h-64 list-none flex-col overflow-y-auto overscroll-contain rounded-[10px] border border-line p-0"
                aria-label="주소 검색 결과"
              >
                {results.map((r, n) => (
                  <li key={n} className="border-t border-line first:border-t-0">
                    <button
                      type="button"
                      className="flex w-full flex-col items-start gap-0.5 px-3.5 py-2.5 text-left hover:bg-hover focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand"
                      onClick={() => {
                        setPicked(r)
                        setOpen(false)
                        setResults(null)
                      }}
                    >
                      <span className="text-[15px] font-semibold text-ink">
                        {r.sido} {r.sigungu} {r.dong}
                      </span>
                      <span className="text-[13px] text-sub [overflow-wrap:anywhere]">
                        {r.label}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-sub">
                검색 결과가 없어요. 도로명이나 동 이름으로 다시 찾아 주세요.
              </p>
            ))}
        </div>
      )}
    </div>
  )
}
