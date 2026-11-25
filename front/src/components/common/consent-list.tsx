// 기록 공유 동의 다섯 칸(S-07·S-08): 보여 줄 사람 · 보여 주는 것 · 목적 · 기간 · 동의하지 않으면
export function ConsentList({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="consent-list">
      {rows.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  )
}
