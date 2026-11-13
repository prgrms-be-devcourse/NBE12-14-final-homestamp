import type { Notice, Room } from '@/data/demo'

// 화면이 쓰는 API 모양. Mock과 HTTP가 같은 모양을 구현합니다.
// 명세에 응답 본문 형식이 아직 정해지지 않은 곳은 화면 모델(data/demo의 타입)을 그대로 쓰고, 백엔드 연결 때 변환 함수만 바꿉니다.
// 로그인한 계정. 호실마다 어느 측인지는 호실 연결(room.members)로 정해져요.
export interface Session { accountId: string }
// 주소 검색 결과 한 줄. 저장은 시·도 · 시·군·구 · 읍·면·동만 해요(명세 '주소 저장 범위').
// label(도로명 주소 전체)은 결과 중에서 고를 때 보여 주기만 하고 저장하지 않아요.
export interface AddressRegion { sido: string; sigungu: string; dong: string; label: string }
export interface HomeStampApi {
  mode: 'mock' | 'http'
  // GET /api/rooms
  listRooms(session: Session): Promise<Room[]>
  // GET /api/me/notifications(응답 필요 먼저)
  listNotifications(session: Session): Promise<Notice[]>
  // POST /api/me/notifications/{id}/read — 읽음은 응답이 아니라 기다림 타이머는 그대로
  readNotification(session: Session, id: string): Promise<void>
  readAllNotifications(session: Session): Promise<void>
  // GET /api/addresses?keyword= — 서버가 행정안전부 도로명주소 검색 API(승인키는 서버에만)를 대신 불러 시·도·시·군·구·읍·면·동으로 줄여 줘요
  searchAddress(keyword: string): Promise<AddressRegion[]>
}