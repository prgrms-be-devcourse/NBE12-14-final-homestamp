#!/bin/sh
# 시험 전용: Migration 비밀 폴더를 운영과 같은 모양으로 만든다(root가 runner 안에서 실행).
#   secrets-fixture.sh <비밀 폴더> <원본 폴더>
# - 상위 폴더: root 0755 / 비밀 폴더: 10002 0500 / 파일 4개: 10002 0400 (deploy.sh check_migration_secrets와 같은 계약)
# - 원본 폴더의 같은 이름 파일 4개를 복사한다. 값은 시험용 가짜 값이고 출력하지 않는다
# - 새 폴더를 다 만든 뒤 바꿔 끼운다(중간에 실패하면 이전 폴더가 남는다)
set -eu
dst=$1
src=$2
parent=$(dirname "$dst")
mkdir -p "$parent"
chown 0:0 "$parent"
chmod 0755 "$parent"
rm -rf "$dst.new"
mkdir "$dst.new"
for f in spring.datasource.url spring.datasource.username spring.datasource.password app.migration.owner-role; do
  cp "$src/$f" "$dst.new/$f"
done
chown -R 10002:10002 "$dst.new"
chmod 0400 "$dst.new"/*
chmod 0500 "$dst.new"
rm -rf "$dst"
mv "$dst.new" "$dst"
