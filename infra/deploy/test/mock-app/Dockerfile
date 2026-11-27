# 시험 전용 가짜 앱: 실제 앱 대신 같은 계약(8080, /actuator/health, /api/, --migrate·--apply-grants 종료 코드)만 흉내 낸다
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
ARG VERSION=v1
ARG HEALTH_CODE=200
ARG HEALTH_STATUS=UP
ARG API_CODE=401
ARG MIGRATE_EXIT=0
ARG GRANTS_EXIT=0
ARG MIGRATE_SLEEP=0
ARG GRANTS_WEB=0
ARG CRASH=0
COPY mock-entry.sh /mock-entry.sh
COPY mock-nginx.conf /etc/nginx/nginx.conf
RUN sed -i -e "s/__VERSION__/${VERSION}/g" -e "s/__HEALTH_CODE__/${HEALTH_CODE}/" \
      -e "s/__HEALTH_STATUS__/${HEALTH_STATUS}/" -e "s/__API_CODE__/${API_CODE}/" /etc/nginx/nginx.conf \
    && chmod 0755 /mock-entry.sh
ENV MOCK_MIGRATE_EXIT=${MIGRATE_EXIT} MOCK_GRANTS_EXIT=${GRANTS_EXIT} MOCK_MIGRATE_SLEEP=${MIGRATE_SLEEP} MOCK_CRASH=${CRASH} MOCK_GRANTS_WEB=${GRANTS_WEB}
USER 101
ENTRYPOINT ["/mock-entry.sh"]
