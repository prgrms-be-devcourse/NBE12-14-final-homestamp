import {
  PRIVACY_NOTICE_POINTS,
  TERMS_SUMMARY,
} from "@/features/member/terms-text"

// 가입 화면과 약관 다시 확인 창이 함께 쓰는 약관 요약·처리 안내(문구는 terms-text.ts)
export function TermsSummary() {
  return (
    <div className="space-y-2 text-sm leading-relaxed">
      {TERMS_SUMMARY.map((t) => (
        <p key={t}>{t}</p>
      ))}
    </div>
  )
}

export function PrivacyNoticePoints() {
  return (
    <ul>
      {PRIVACY_NOTICE_POINTS.map((t) => (
        <li key={t}>{t}</li>
      ))}
    </ul>
  )
}
