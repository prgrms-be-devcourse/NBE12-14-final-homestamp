import { Button as ButtonPrimitive } from "@base-ui/react/button"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

// homestamp 버튼: 글자가 길면 줄을 바꾸고(한국어 문구), 누르기 쉬운 높이(기본 40px)를 지켜요.
// 아이콘이 앞(또는 뒤)에 있으면 그쪽 여백을 조금 줄여 눈으로 보기에 가운데로 맞춰요.
const iconSide =
  "has-[>svg:first-child]:pl-[13px] [&:has(>svg:last-child):not(:has(>svg:first-child))]:pr-[13px]"
const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-[10px] border border-transparent bg-clip-padding text-center text-sm leading-[1.35] font-medium whitespace-normal text-balance transition-all outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary/80",
        outline:
          "border-border bg-background hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-[color-mix(in_oklch,var(--secondary),var(--foreground)_5%)] aria-expanded:bg-secondary aria-expanded:text-secondary-foreground",
        ghost:
          "hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:hover:bg-muted/50",
        destructive:
          "bg-destructive/10 text-destructive hover:bg-destructive/20 focus-visible:border-destructive/40 focus-visible:ring-destructive/20 dark:bg-destructive/20 dark:hover:bg-destructive/30 dark:focus-visible:ring-destructive/40",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        default: `min-h-10 gap-1.5 px-4 py-2 ${iconSide}`,
        xs: "h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
        // h-7·h-9는 아직 남아 있는 service.css 버튼 규칙이 크기를 알아보는 표시예요(공통 단계에서 CSS를 지울 때 함께 정리)
        sm: "h-7 min-h-[34px] gap-[5px] px-3 py-1.5 text-[13px] has-[>svg:first-child]:pl-2.5 [&_svg:not([class*='size-'])]:size-3.5",
        lg: `h-9 min-h-[46px] gap-1.5 px-[22px] py-2.5 text-[15px] ${iconSide}`,
        icon: "size-8",
        "icon-xs":
          "size-6 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3",
        "icon-sm":
          "size-7 rounded-[min(var(--radius-md),12px)] in-data-[slot=button-group]:rounded-lg",
        "icon-lg": "size-9",
      },
    },
    // 링크 모양: 글자처럼 붙고 눌림 영역(32px)만 확보
    compoundVariants: [
      {
        variant: "link",
        class:
          "min-h-8 justify-start rounded-md px-0.5 py-1 text-left has-[>svg:first-child]:pl-0.5 [&:has(>svg:last-child):not(:has(>svg:first-child))]:pr-0.5",
      },
    ],
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
)

function Button({
  className,
  variant = "default",
  size = "default",
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  return (
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
