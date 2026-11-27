# 화면(React) 정적 파일 운반 이미지(04 9-2, 런북 docs/runbooks/이미지-발행.md "화면 이미지").
# 실행하는 컨테이너가 아니다. 배포는 docker create → docker cp /site 로 파일만 꺼낸다(front-deploy).
# 빌드: docker build --platform linux/amd64 --build-arg REVISION=<커밋 40자> -f front/Dockerfile front
#   빌드 단계는 빌드하는 기계의 플랫폼에서 돈다(결과는 플랫폼과 무관한 정적 파일). 마지막 이미지만 linux/amd64
FROM --platform=$BUILDPLATFORM node:22.23.5-bookworm-slim@sha256:be979e5523c76fa4d5ca4ebd3099078f220aa47ec39c48ad5b5df9314debd6eb AS builder
ARG REVISION
WORKDIR /app
# 실제 API 모드: 같은 출처의 /api(nginx가 앱으로 넘김). 브라우저에 공개되는 값만 둔다(비밀값은 빌드에 넣지 않는다)
ENV VITE_API_MODE=http \
    VITE_API_BASE_URL= \
    CI=true \
    npm_config_audit=false \
    npm_config_fund=false \
    npm_config_update_notifier=false
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY index.html vite.config.ts tsconfig.json tsconfig.app.json tsconfig.node.json ./
COPY src/ ./src/
RUN set -eu; \
    case "${REVISION:-}" in \
      *[!0-9a-f]*|'') echo 'REVISION은 커밋 40자(소문자 16진수)입니다' >&2; exit 1 ;; \
    esac; \
    [ "${#REVISION}" -eq 40 ] || { echo 'REVISION은 커밋 40자입니다' >&2; exit 1; }; \
    npm run build; \
    test -f dist/index.html; \
    if find dist -name '*.map' | grep -q .; then echo '소스맵(.map)은 공개하지 않습니다' >&2; exit 1; fi; \
    printf '{"revision":"%s"}\n' "$REVISION" > dist/version.json; \
    find dist -type d -exec chmod 0755 {} +; \
    find dist -type f -exec chmod 0644 {} +

FROM scratch
COPY --from=builder /app/dist/ /site/
# 실행용이 아님. docker create가 명령을 요구해서 둔 자리표시(실행하면 실패한다)
CMD ["/homestamp-front-is-not-runnable"]
