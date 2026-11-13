// 명세 4장 API 목록 중 화면이 쓰는 주소. 문서에 있는 것만 적습니다(없는 API는 만들지 않음).
const id = (v: string) => encodeURIComponent(v)
export const endpoints = {
  // 4-1 계정·인증
  phoneVerifications: "/api/auth/phone-verifications",
  verifyPhone: (vid: string) =>
    `/api/auth/phone-verifications/${id(vid)}/verify`,
  signup: "/api/auth/signup",
  login: "/api/auth/login",
  refresh: "/api/auth/refresh",
  logout: "/api/auth/logout",
  me: "/api/me",
  // 4-2 홈·알림
  home: "/api/me/home",
  notifications: "/api/me/notifications",
  unreadCount: "/api/me/notifications/unread-count",
  readNotification: (nid: string) => `/api/me/notifications/${id(nid)}/read`,
  sharing: "/api/me/sharing",
  // 4-3 호실과 참여
  // 주소 검색(호실 만들기): 서버가 도로명주소 검색 API를 대신 불러요. 명세 4장에 아직 없어 추가가 필요해요
  addresses: (keyword: string) => `/api/addresses?keyword=${id(keyword)}`,
  rooms: "/api/rooms",
  room: (rid: string) => `/api/rooms/${id(rid)}`,
  timeline: (rid: string) => `/api/rooms/${id(rid)}/timeline`,
  invite: (rid: string) => `/api/rooms/${id(rid)}/invite`,
  invitations: "/api/invitations",
  connect: (pid: string) => `/api/invitations/${id(pid)}/connect`,
  rejectInvitation: (pid: string) => `/api/invitations/${id(pid)}/reject`,
  moveOut: (rid: string) => `/api/rooms/${id(rid)}/move-out`,
  // 4-5 사진
  uploadTickets: (rid: string) => `/api/rooms/${id(rid)}/upload-tickets`,
  markWrong: (pid: string) => `/api/photos/${id(pid)}/mark-wrong`,
  unmarkWrong: (pid: string) => `/api/photos/${id(pid)}/unmark-wrong`,
  // 4-6 수리·돈
  repairCases: (rid: string) => `/api/rooms/${id(rid)}/repair-cases`,
  repairCase: (cid: string) => `/api/repair-cases/${id(cid)}`,
  repairAction: (
    cid: string,
    action:
      | "confirm"
      | "dispute"
      | "ack-defect"
      | "supplement"
      | "start"
      | "request-close"
      | "confirm-close"
      | "not-yet"
      | "close-alone"
      | "reopen"
      | "photos",
  ) => `/api/repair-cases/${id(cid)}/${action}`,
  corrections: "/api/corrections",
  correctionAction: (cid: string, action: "accept" | "reject" | "withdraw") =>
    `/api/corrections/${id(cid)}/${action}`,
  acceptAllCorrections: "/api/corrections/accept-all",
  payments: (cid: string) => `/api/repair-cases/${id(cid)}/payments`,
  proposals: (cid: string) => `/api/repair-cases/${id(cid)}/proposals`,
  proposalAction: (
    pid: string,
    action: "agree" | "dispute" | "counter" | "withdraw",
  ) => `/api/proposals/${id(pid)}/${action}`,
  // 4-7 점검
  inspections: (rid: string) => `/api/rooms/${id(rid)}/inspections`,
  inspection: (iid: string) => `/api/inspections/${id(iid)}`,
} as const
