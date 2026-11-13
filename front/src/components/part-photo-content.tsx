import type { PartPhoto } from '@/data/demo'
import { safeSharedText } from '@/lib/service-rules'
import { PhotoGallery } from '@/components/photo-gallery'
import { RecordDescription } from '@/components/record-description'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

// 부위 사진: 썸네일 + 제목·부위·메모 요약. 사진은 썸네일을 눌러 크게 봐요.
export function PartPhotoContent({ record, onShare, shared = false }: {record: PartPhoto; onShare?: () => void; shared?: boolean}) {
  const title = shared ? safeSharedText(record.title) : record.title
  const description = shared ? safeSharedText(record.description) : record.description
  return <div className="part-photo-record rec-item" data-record-kind="part-photo">
    <div className="rec-thumb"><PhotoGallery title={title} description={description} photos={record.photos} layout="compact" /></div>
    <div className="rec-body">
      <div className="rec-head"><h3>{title}</h3><Badge variant="secondary">부위 사진</Badge></div>
      <small className="rec-meta">{record.zone} · {record.recordedAt} · {record.author} 등록{record.late ? ' · 입주 후 등록' : ''}{record.afterEnd ? ' · 거주 종료 뒤 추가' : ''}</small>
      {description && <RecordDescription text={description} />}
      <small className="rec-meta">상대 확인 대상이 아니에요. 하자 유무를 판단하지 않아요.</small>
      {onShare && <Button variant="link" size="sm" className="h-auto p-0" onClick={onShare}>공유 링크 복사</Button>}
    </div>
  </div>
}
