import { API_MODE } from "@/config"

// 목업 모드(서버 없이 브라우저 메모리로 동작)임을 화면 맨 위에 알려요. HTTP 모드에서는 보이지 않아요.
export function MockModeBanner() {
  if (API_MODE !== "mock") return null
  return (
    <div
      role="note"
      className="border-b border-amber-200 bg-amber-50 px-4 py-1.5 text-center text-[13px] leading-[1.6] text-amber-900"
    >
      예시 모드 · 서버 없이 이 브라우저에서만 동작해요. 새로고침하면 처음 상태로
      돌아가요.
    </div>
  )
}
