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
  // 바뀐 약관·확인 항목 다시 확인(CONS-4)
  agreements: "/api/me/agreements",
  // 4-2 홈·알림
  home: "/api/me/home",
  notifications: "/api/me/notifications",
  unreadCount: "/api/me/notifications/unread-count",
  readNotification: (nid: string) => `/api/me/notifications/${id(nid)}/read`,
  sharing: "/api/me/sharing",
  // 4-3 호실과 참여
  // 법정동 찾기·단계 선택(호실 만들기, ROOM-1)
  regionSearch: (q: string) => `/api/regions/search?q=${id(q)}`,
  // parent가 없을 때만 시·도 주소. 빈 글자도 그대로 보내 서버가 VALIDATION_FAILED로 막게 해요(mock과 같게)
  regions: (parent?: string) =>
    parent === undefined ? "/api/regions" : `/api/regions?parent=${id(parent)}`,
  rooms: "/api/rooms",
  // 내 호실 목록 한 쪽(06 4-3: size 1~100, cursor는 앞 쪽의 nextCursor)
  roomPage: (size: number, cursor?: string) =>
    `/api/rooms?size=${size}${cursor ? `&cursor=${id(cursor)}` : ""}`,
  room: (rid: string) => `/api/rooms/${id(rid)}`,
  // 부위 목록·부위 더하기(ROOM-3)
  roomParts: (rid: string) => `/api/rooms/${id(rid)}/parts`,
  timeline: (rid: string) => `/api/rooms/${id(rid)}/timeline`,
  // 내려받기 목록(기록.json 내용 + 사진 묶음, DL-1)
  roomDownload: (rid: string) => `/api/rooms/${id(rid)}/download`,
  invite: (rid: string) => `/api/rooms/${id(rid)}/invite`,
  invitations: "/api/invitations",
  // 가입 전 링크 열람(S-16, LINK-1). 토큰은 주소에 두지 않고 본문으로 보내요(06 LINK-2b)
  linkView: "/api/public/links/view",
  connect: (pid: string) => `/api/invitations/${id(pid)}/connect`,
  rejectInvitation: (pid: string) => `/api/invitations/${id(pid)}/reject`,
  roomInfo: (rid: string) => `/api/rooms/${id(rid)}/info`,
  counterpartPhone: (rid: string) => `/api/rooms/${id(rid)}/counterpart-phone`,
  stopSharing: (rid: string) => `/api/rooms/${id(rid)}/stop-sharing`,
  resumeSharing: (rid: string) => `/api/rooms/${id(rid)}/resume-sharing`,
  moveOut: (rid: string) => `/api/rooms/${id(rid)}/move-out`,
  // 세입자 측이 집주인의 거주 종료 요청에 "맞아요" / "아직 살고 있어요"
  moveOutAnswer: (rid: string, answer: "confirm" | "reject") =>
    `/api/rooms/${id(rid)}/move-out/${answer}`,
  // 4-5 사진
  // 사진 주소 받기(1~50장, variant THUMB·ORIGINAL)
  photoViewUrls: "/api/photos/view-urls",
  uploadTickets: (rid: string) => `/api/rooms/${id(rid)}/upload-tickets`,
  photoAction: (
    pid: string,
    action:
      "hide-on-link" | "show-on-link" | "erase" | "mark-wrong" | "unmark-wrong",
  ) => `/api/photos/${id(pid)}/${action}`,
  // 4-6 수리·돈
  repairCases: (rid: string) => `/api/rooms/${id(rid)}/repair-cases`,
  // 수리 건 목록 한 쪽(06 4-6, 커서 방식)
  repairCasePage: (rid: string, size: number, cursor?: string) =>
    `/api/rooms/${id(rid)}/repair-cases?size=${size}${cursor ? `&cursor=${id(cursor)}` : ""}`,
  repairCase: (cid: string) => `/api/repair-cases/${id(cid)}`,
  // 원인 설명 응답(06 4장 POST /api/cause-notes/{id}/respond)
  causeNoteRespond: (nid: string) => `/api/cause-notes/${id(nid)}/respond`,
  progressNoteRespond: (nid: string) =>
    `/api/progress-notes/${id(nid)}/respond`,
  repairAction: (
    cid: string,
    // 06 4장 수리 건 행동(하자 응답은 /respond 하나: ACK·NEED_MORE·DISPUTE)
    action:
      | "respond"
      | "supplement"
      | "start"
      | "request-close"
      | "confirm-close"
      | "not-yet"
      | "close-alone"
      | "reopen"
      | "photos"
      | "ask-progress"
      | "progress-notes"
      | "cause-notes",
  ) => `/api/repair-cases/${id(cid)}/${action}`,
  corrections: "/api/corrections",
  correctionAction: (
    cid: string,
    action: "respond" | "resubmit" | "withdraw",
  ) => `/api/corrections/${id(cid)}/${action}`,
  acceptAllCorrections: "/api/corrections/accept-all",
  payments: (cid: string) => `/api/repair-cases/${id(cid)}/payments`,
  paymentAction: (pid: string, action: "respond" | "supplement") =>
    `/api/payments/${id(pid)}/${action}`,
  proposals: (cid: string) => `/api/repair-cases/${id(cid)}/proposals`,
  proposalAction: (
    pid: string,
    action: "agree" | "dispute" | "counter" | "withdraw",
  ) => `/api/proposals/${id(pid)}/${action}`,
  // 4-7 점검
  inspections: (rid: string) => `/api/rooms/${id(rid)}/inspections`,
  inspection: (iid: string) => `/api/inspections/${id(iid)}`,
  inspectionAction: (
    iid: string,
    action:
      "items" | "part-photos" | "request-confirm" | "confirm-all" | "finish",
  ) => `/api/inspections/${id(iid)}/${action}`,
  inspectionItemAction: (itemId: string, action: "respond" | "supplement") =>
    `/api/inspection-items/${id(itemId)}/${action}`,
  // 4-8 정산
  openSettlement: (rid: string) => `/api/rooms/${id(rid)}/settlement`,
  settlement: (sid: string) => `/api/settlements/${id(sid)}`,
  settlementAction: (
    sid: string,
    action:
      | "deposit-proposals"
      | "items"
      | "transfers"
      | "finish"
      | "finish/confirm"
      | "finish/not-yet"
      | "finish/alone"
      | "invite",
  ) => `/api/settlements/${id(sid)}/${action}`,
  settlementItemAction: (itemId: string, action: "exclude" | "proposals") =>
    `/api/settlement-items/${id(itemId)}/${action}`,
  transferRespond: (tid: string) =>
    `/api/settlement-transfers/${id(tid)}/respond`,
} as const
