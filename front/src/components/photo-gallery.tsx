import { useState } from "react"
import { ChevronLeft, ChevronRight, EyeOff, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Art } from "@/components/prototype"
import type { PhotoMask } from "@/data/demo"
import { maskLabel } from "@/lib/photo-privacy"

interface PhotoGalleryProps {
  photos: string[]
  title: string
  art?: string
  layout?: "cover" | "strip" | "compact"
  description?: string
  // photos와 같은 순서. 값이 있으면 개인정보 이유로 가린 사진이라 흐리게 보여요.
  masks?: (PhotoMask | undefined)[]
}

// 가린 사진: 원본 대신 흐리게 + "개인정보 이유로 가림" 표시
function Shown({
  src,
  alt,
  mask,
  art,
  className,
}: {
  src?: string
  alt: string
  mask?: PhotoMask
  art: string
  className?: string
}) {
  if (!src) return <Art art={art} large />
  if (!mask) return <img className={className} src={src} alt={alt} />
  return (
    <span className="photo-masked" title={maskLabel(mask)}>
      <img className={className} src={src} alt="" aria-hidden />
      <span className="photo-masked-label">
        <EyeOff size={14} aria-hidden />
        개인정보 이유로 가림
      </span>
      <span className="sr-only">
        {alt} · {maskLabel(mask)}
      </span>
    </span>
  )
}

// Viewing state only: never changes a record, its photos, or service status.
export function PhotoGallery({
  photos: given,
  title,
  art = "wall",
  layout = "cover",
  description,
  masks: givenMasks,
}: PhotoGalleryProps) {
  const [selected, setSelected] = useState<number | null>(null)
  // 가린 사진은 뒤로: 열려 있는 사진이 첫 장(대표 사진)이 되게 순서만 바꿔요(각 묶음 안의 순서는 그대로).
  const order = given
    .map((_, n) => n)
    .sort(
      (a, b) =>
        Number(Boolean(givenMasks?.[a])) - Number(Boolean(givenMasks?.[b])),
    )
  const photos = order.map((n) => given[n])
  const masks = givenMasks && order.map((n) => givenMasks[n])
  const images = photos.length ? photos : [undefined]
  const current = Math.min(selected ?? 0, images.length - 1)
  const move = (delta: number) =>
    setSelected((index) =>
      Math.max(0, Math.min((index ?? 0) + delta, images.length - 1)),
    )
  const currentMask = masks?.[current]

  return (
    <>
      <div
        className={`photo-gallery gallery-${layout}`}
        aria-label={`${title} 사진 목록`}
      >
        {(layout === "compact" ? images.slice(0, 1) : images.slice(0, 3)).map(
          (src, index) => (
            <Button
              key={index}
              variant="ghost"
              className={`photo-trigger ${index === 0 ? "photo-cover" : ""}`}
              aria-label={`${title} · 사진 ${index + 1} 확대 보기`}
              onClick={() => setSelected(index)}
            >
              <Shown
                src={src}
                alt={`${title} · 사진 ${index + 1}`}
                mask={masks?.[index]}
                art={art}
                className="record-photo large"
              />
              {index === 0 && images.length > 1 && (
                <span className="photo-count">사진 {images.length}장</span>
              )}
            </Button>
          ),
        )}
        {layout !== "compact" && images.length > 3 && (
          <Button
            variant="outline"
            className="photo-more"
            onClick={() => setSelected(3)}
          >
            +{images.length - 3}장
          </Button>
        )}
      </div>
      {layout !== "compact" && photos.length > 0 && (
        <Button
          variant="link"
          className="photo-all"
          onClick={() => setSelected(0)}
        >
          전체 사진 보기 ({photos.length}장)
        </Button>
      )}
      <Dialog
        open={selected !== null}
        onOpenChange={(open) => {
          if (!open) setSelected(null)
        }}
      >
        <DialogContent
          className="photo-viewer"
          showCloseButton={false}
          onKeyDown={(event) => {
            if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
              event.preventDefault()
              move(event.key === "ArrowLeft" ? -1 : 1)
            }
          }}
        >
          <DialogHeader className="photo-viewer-header">
            <DialogTitle>{title} · 사진 확대 보기</DialogTitle>
            <DialogDescription>
              사진 전체를 확인하세요. 좌우 버튼이나 키보드 방향키로 넘길 수
              있어요.
            </DialogDescription>
          </DialogHeader>
          <div className="photo-viewer-stage">
            <Shown
              src={images[current]}
              alt={`${title} · 확대 사진 ${current + 1}`}
              mask={currentMask}
              art={art}
            />
          </div>
          {currentMask && (
            <p className="photo-masked-note">
              <EyeOff size={14} aria-hidden />
              {maskLabel(currentMask)}
              {currentMask.objections?.length
                ? ` · 이견 ${currentMask.objections.length}건`
                : ""}
            </p>
          )}
          <div className="photo-viewer-controls">
            <Button
              variant="outline"
              aria-label="이전 사진"
              disabled={current === 0}
              onClick={() => move(-1)}
            >
              <ChevronLeft />
              <span>이전</span>
            </Button>
            <p role="status" aria-live="polite" aria-label="현재 사진 번호">
              {current + 1} / {images.length}
            </p>
            <Button
              variant="outline"
              aria-label="다음 사진"
              disabled={current === images.length - 1}
              onClick={() => move(1)}
            >
              <span>다음</span>
              <ChevronRight />
            </Button>
          </div>
          {description && (
            <p className="photo-full-description">{description}</p>
          )}
          {images.length > 1 && (
            <div className="photo-viewer-thumbnails">
              {images.map((src, i) => (
                <Button
                  key={i}
                  variant={current === i ? "default" : "outline"}
                  aria-label={`전체 사진 ${i + 1} 선택`}
                  onClick={() => setSelected(i)}
                >
                  {src ? (
                    <Shown
                      src={src}
                      alt={`사진 ${i + 1} 썸네일`}
                      mask={masks?.[i]}
                      art={art}
                    />
                  ) : (
                    i + 1
                  )}
                </Button>
              ))}
            </div>
          )}
          <DialogClose
            render={
              <Button
                variant="ghost"
                size="icon"
                className="photo-viewer-close"
              />
            }
            aria-label="사진 확대 보기 닫기"
          >
            <X />
          </DialogClose>
        </DialogContent>
      </Dialog>
    </>
  )
}
