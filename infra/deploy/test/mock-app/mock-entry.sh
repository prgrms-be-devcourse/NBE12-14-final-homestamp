#!/bin/sh
# 비밀 파일은 읽을 수 있는지만 보고 값은 출력하지 않는다
secret=/run/secrets/homestamp-migration/spring.datasource.password
case "${1:-}" in
  --migrate)
    [ -s "$secret" ] || { echo "mock migrate: 비밀 폴더 없음"; exit 1; }
    sleep "$MOCK_MIGRATE_SLEEP"
    echo "mock migrate exit=$MOCK_MIGRATE_EXIT"
    exit "$MOCK_MIGRATE_EXIT" ;;
  --apply-grants)
    # #299 전 이미지 흉내: 이 인자를 모르고 웹 앱으로 뜬다(끝나지 않음)
    [ "$MOCK_GRANTS_WEB" = 1 ] && exec nginx -g 'daemon off;'
    [ -s "$secret" ] || { echo "mock grants: 비밀 폴더 없음"; exit 1; }
    echo "mock grants exit=$MOCK_GRANTS_EXIT"
    exit "$MOCK_GRANTS_EXIT" ;;
esac
[ "$MOCK_CRASH" = 1 ] && { echo "mock app: 시작 실패"; exit 1; }
exec nginx -g 'daemon off;'
