import { cn } from "cn"

// 하자 확인 표시. 둘이 하자를 확인한 기록에만 찍어요(수리비 확인에는 쓰지 않아요: 하자 확인은 돈을 낸다는 뜻이 아니라서, 08 2장).
// 꾸밈이라 화면 읽기 프로그램은 건너뛰고, 옆의 "확인 ✓" 글을 읽어요. 기울기는 기록마다 조금씩 달라 손으로 찍은 것처럼 보여요.
export function Stamp({
  seed = "",
  size = 40,
  className,
}: {
  seed?: string
  size?: number
  className?: string
}) {
  const turn = ((hash(seed) % 17) - 8) * 1.2
  return (
    <span
      aria-hidden
      className={cn(
        "grid flex-none place-items-center rounded-full border-2 border-stamp font-extrabold text-stamp opacity-90",
        className,
      )}
      style={{
        width: size,
        height: size,
        fontSize: Math.round(size * 0.28),
        transform: `rotate(${turn}deg)`,
      }}
    >
      확인
    </span>
  )
}

function hash(s: string) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return h
}
