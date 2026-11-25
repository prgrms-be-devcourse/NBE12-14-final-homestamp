import type { ComponentProps, ReactNode } from "react"
import { ArrowRight, Link2 } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Empty, Logo, Panel, Status } from "@/components/common/blocks"
import { RecordDescription } from "@/components/common/record-description"
import { PhotoGallery } from "@/features/photo/photo-gallery"
import { PartPhotoContent } from "@/features/photo/part-photo-content"
import { InspectionItemCorrections } from "@/features/inspection/inspection-corrections"
import type { Account } from "@/api/auth"
import type {
  DemoState,
  Inspection,
  PartPhoto,
  Repair,
  Role,
  Room,
} from "@/data/demo"
import type { maskOf } from "@/lib/photo-privacy"
import { safeSharedText } from "@/lib/service-rules"
import { isSettlementInvitation } from "@/lib/workflows"

export type SharedKind =
  "repair" | "inspection" | "part-photo" | "invite" | "settlement"

// 공유 링크로 들어온 화면(06 LINK, 가입 전 열람): 링크에 지정된 기록 한 건만 읽기 전용으로 보여요.
export function SharePage({
  account,
  sharedKind,
  sharedRoom,
  sharedRepair,
  sharedInspection,
  sharedPhoto,
  db,
  role,
  renderRepair,
  itemMasks,
  onAct,
  onHome,
  onConnect,
  onRequireAuth,
}: {
  account: Account | null
  sharedKind: SharedKind
  sharedRoom?: Room
  sharedRepair?: Repair
  sharedInspection?: Inspection
  sharedPhoto?: PartPhoto
  db: DemoState
  role: Role
  renderRepair: (r: Repair) => ReactNode
  itemMasks: (i: Inspection) => ReturnType<typeof maskOf>[]
  onAct: ComponentProps<typeof InspectionItemCorrections>["onAct"]
  onHome: () => void
  onConnect: () => void
  onRequireAuth: () => void
}) {
  return (
    <div className="guest-shell">
      <header>
        <Logo />
        <div className="flex items-center gap-2">
          <Badge variant="secondary">
            {account ? "로그인됨 · 호실 연결 전" : "읽기 전용 · 비회원"}
          </Badge>
          {account ? (
            <Button size="sm" variant="ghost" onClick={onHome}>
              내 홈으로
            </Button>
          ) : (
            <Button size="sm" variant="outline" onClick={onRequireAuth}>
              로그인
            </Button>
          )}
        </div>
      </header>
      <div className="guest-banner">
        <Link2 size={22} />
        <div>
          <h1>
            {sharedKind === "inspection"
              ? "공유된 점검 하자 항목 · 읽기 전용"
              : sharedKind === "part-photo"
                ? "공유된 부위 사진 · 읽기 전용"
                : sharedKind === "invite"
                  ? "호실 연결 초대"
                  : sharedKind === "settlement"
                    ? "퇴거 정산 확인 요청"
                    : "공유된 수리 기록 · 읽기 전용"}
          </h1>
          <p>
            이 링크에 지정된 기록 한 건만 표시합니다. 다른 점검·수리 기록과 호실
            전체 기록은 공개되지 않아요.
          </p>
        </div>
      </div>
      {sharedKind === "settlement" ? (
        sharedRoom && isSettlementInvitation(sharedRoom) ? (
          <Card className="guest-cta">
            <h2>퇴거 정산 확인 요청이 있어요</h2>
            <p>정산 금액과 항목은 가입하고 호실에 연결한 뒤에 볼 수 있어요.</p>
            {account ? (
              <Button onClick={onConnect}>
                호실 연결 확인
                <ArrowRight />
              </Button>
            ) : (
              <Button onClick={onRequireAuth}>
                가입하고 확인하기
                <ArrowRight />
              </Button>
            )}
          </Card>
        ) : (
          <Empty text="유효하지 않은 정산 초대 링크예요." />
        )
      ) : sharedRoom?.status === "거주 중" &&
        sharedRoom.connection !== "거절됨" &&
        (sharedKind === "invite" ||
          (sharedKind === "repair" && sharedRepair) ||
          (sharedKind === "inspection" &&
            sharedInspection &&
            sharedInspection.publication !== "draft") ||
          (sharedKind === "part-photo" && sharedPhoto?.sharedOnLink)) ? (
        <>
          {sharedKind === "repair" &&
            sharedRepair &&
            renderRepair(sharedRepair)}
          {sharedKind === "inspection" && sharedInspection && (
            <Panel title={safeSharedText(sharedInspection.title)}>
              <Badge variant="secondary">
                기존 하자 기록 · 응답은 연결 후 가능
              </Badge>
              <p className="muted">
                {sharedInspection.kind === "in" ? "입주 당시" : "퇴거 당시"}{" "}
                점검에 남긴 하자 항목 한 건입니다. 사실 확인과 비용 부담 동의는
                별개입니다.
              </p>
              <RecordDescription
                text={safeSharedText(sharedInspection.description)}
              />
              <PhotoGallery
                title={safeSharedText(sharedInspection.title)}
                description={safeSharedText(sharedInspection.description)}
                photos={sharedInspection.photos}
                masks={itemMasks(sharedInspection)}
                art={sharedInspection.art}
              />
              <Status>{sharedInspection.ack}</Status>
              <InspectionItemCorrections
                i={sharedInspection}
                db={db}
                role={role}
                readOnly
                guest
                actions={[]}
                correctionActionsOf={() => []}
                onAct={onAct}
              />
              <p className="muted">
                현재 확인 상태만 표시합니다. 확인·이견 응답은 가입·번호
                확인·호실 연결을 마친 뒤 회원 화면에서 남길 수 있어요.
              </p>
            </Panel>
          )}
          {sharedKind === "part-photo" && sharedPhoto && (
            <Panel title="공유된 부위 사진 한 건">
              <PartPhotoContent record={sharedPhoto} shared />
            </Panel>
          )}
          <Card className="guest-cta">
            <h2>
              {sharedKind === "part-photo"
                ? "호실의 기록을 함께 보려면"
                : "연결한 뒤 기록에 응답할 수 있어요"}
            </h2>
            <p>
              {sharedKind === "part-photo"
                ? "이 사진은 상대 확인 대상이 아닙니다. 호실 전체 기록을 함께 보려면 가입·번호 확인·연결 수락이 필요해요."
                : "가입·로그인만으로 호실 전체가 열리지는 않습니다. 초대 번호 확인과 명시적인 연결 수락을 마쳐야 해요."}
            </p>
            {account ? (
              <Button onClick={onConnect}>
                호실 연결 확인
                <ArrowRight />
              </Button>
            ) : (
              <Button onClick={onRequireAuth}>
                가입·로그인하고 연결하기
                <ArrowRight />
              </Button>
            )}
          </Card>
        </>
      ) : (
        <Empty text="공유되지 않았거나 유효하지 않은 기록입니다." />
      )}
      <p className="guest-foot">
        공유 범위는 이 기록 한 건입니다. 읽음·열람은 하자 확인이나 합의가
        아닙니다.{" "}
      </p>
    </div>
  )
}
