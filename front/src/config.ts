// 실행 환경 설정. 실제 값은 .env의 VITE_ 변수로 바꿉니다.
// - VITE_API_MODE: 'http'(백엔드 연결) | 'mock'(서버 없이 개발: 브라우저 메모리)
//   지정하지 않으면 배포 빌드는 'http', 개발 서버·테스트는 'mock'이에요. 배포 화면이 가짜 인증·발송으로 동작하지 않게 합니다.
// - VITE_API_BASE_URL: http 모드의 API 주소(기본 '' = 같은 출처의 /api)
const mode = import.meta.env.VITE_API_MODE
export const API_MODE: "mock" | "http" =
  mode === "http"
    ? "http"
    : mode === "mock"
      ? "mock"
      : import.meta.env.PROD && import.meta.env.MODE !== "single"
        ? "http"
        : "mock"
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? ""
