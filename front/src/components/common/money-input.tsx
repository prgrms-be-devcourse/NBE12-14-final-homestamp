import { useLayoutEffect, useRef, useState, type ComponentProps } from "react"
import { Input } from "@/components/ui/input"
import { formatMoneyInput, moneyCaret } from "@/lib/money"

type Props = Omit<
  ComponentProps<typeof Input>,
  "type" | "value" | "defaultValue" | "onChange"
> & {
  defaultValue?: string | number
}
export function MoneyInput({ defaultValue = "", ...props }: Props) {
  const [display, setDisplay] = useState(() => formatMoneyInput(defaultValue))
  const ref = useRef<HTMLInputElement>(null)
  const caret = useRef<number | null>(null)
  useLayoutEffect(() => {
    if (caret.current !== null && ref.current) {
      ref.current.setSelectionRange(caret.current, caret.current)
      caret.current = null
    }
  })
  return (
    <Input
      {...props}
      ref={ref}
      type="text"
      inputMode="numeric"
      value={display}
      onChange={(event) => {
        const raw = event.target.value
        const digits = raw
          .slice(0, event.target.selectionStart ?? raw.length)
          .replace(/\D/g, "").length
        const next = formatMoneyInput(raw)
        caret.current = moneyCaret(next, digits)
        setDisplay(next)
        // Deleting only a comma may keep the display unchanged; still restore the caret.
        event.target.value = next
        event.target.setSelectionRange(caret.current, caret.current)
      }}
      onKeyDown={(event) => {
        const input = event.currentTarget
        const position = input.selectionStart ?? 0
        if (input.selectionEnd !== position) return
        let raw: string
        let nextPosition = position
        if (event.key === "Backspace" && display[position - 1] === ",") {
          raw = display.slice(0, position - 2) + display.slice(position)
          nextPosition -= 2
        } else if (event.key === "Delete" && display[position] === ",")
          raw = display.slice(0, position) + display.slice(position + 2)
        else return
        event.preventDefault()
        const next = formatMoneyInput(raw)
        caret.current = moneyCaret(
          next,
          raw.slice(0, nextPosition).replace(/\D/g, "").length,
        )
        setDisplay(next)
      }}
    />
  )
}
