import { Input } from "@/components/ui/input"
import { Choice } from "@/components/common/blocks"
import { partGroups } from "@/data/demo"

// 구역 · 부위 칸(하자 등록 · 점검). 퇴거 때 같은 부위끼리 비교하는 기준이에요.
// pickPart: 구역을 바꾸면 부위를 비울지(입주 점검), 그 구역의 첫 부위로 둘지(그 밖)
export function PartField({
  zone,
  part,
  onChange,
  pickPart = true,
}: {
  zone: string
  part: string
  onChange: (zone: string, part: string) => void
  pickPart?: boolean
}) {
  return (
    <div className="form-grid">
      <div className="field">
        <label>구역</label>
        <Choice
          label="구역"
          value={zone}
          values={Object.keys(partGroups)}
          onChange={(v) =>
            onChange(
              v,
              v === "기타" ? "기타" : pickPart ? partGroups[v][0] : "",
            )
          }
        />
      </div>
      {zone !== "구역 선택" && (
        <div className="field">
          <label>부위 · 사진 비교 기준</label>
          <Choice
            label="부위"
            value={part || "부위 선택"}
            values={Array.from(new Set([...(partGroups[zone] || []), "기타"]))}
            onChange={(p) => onChange(zone, p)}
          />
        </div>
      )}
      {zone === "기타" && (
        <div className="field">
          <label htmlFor="custom-zone">구역 직접 입력</label>
          <Input id="custom-zone" name="customZone" required />
        </div>
      )}
      {part === "기타" && (
        <div className="field">
          <label htmlFor="custom-part">부위 직접 입력</label>
          <Input id="custom-part" name="customPart" required />
        </div>
      )}
    </div>
  )
}
