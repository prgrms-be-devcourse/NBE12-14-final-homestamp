import { useState } from "react"
import { Button } from "@/components/ui/button"
export function RecordDescription({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <div className="record-description">
      <p className={expanded ? "" : "description-preview"}>{text}</p>
      {(text.length > 70 || text.split("\n").length > 3) && (
        <Button
          variant="link"
          aria-expanded={expanded}
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? "접기" : "더 보기"}
        </Button>
      )}
    </div>
  )
}
