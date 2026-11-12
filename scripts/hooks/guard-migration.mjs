// guard-migration: 이미 main에 들어간 Flyway 마이그레이션을 AI가 고치기 직전에 막는다 (PreToolUse).
// 적용된 파일을 고치면 Flyway 체크섬이 달라져 다른 사람 DB와 배포 서버가 뜨지 않는다.
// 내 브랜치에서 새로 만든 V 파일은 허용한다. main 기준은 마지막 fetch 시점의 origin/dev이다.
import { execFileSync } from "node:child_process";
import { getFilePaths, REPO_ROOT, relativeToRepo, runHook } from "./lib/input.mjs";

const MIGRATION_RE = /^back\/src\/main\/resources\/db\/migration\/(?:.+\/)?V[^/]*\.sql$/;

function existsOnMain(rel) {
  try {
    execFileSync("git", ["cat-file", "-e", `origin/dev:${rel}`], { cwd: REPO_ROOT, stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}

export function checkMigration(input, onMain = existsOnMain) {
  for (const abs of getFilePaths(input.toolInput, input.cwd)) {
    const rel = relativeToRepo(abs);
    if (rel && MIGRATION_RE.test(rel) && onMain(rel)) {
      return `이미 적용된 마이그레이션입니다. 새 V 파일을 만드세요 (${rel})`;
    }
  }
  return null;
}

runHook(import.meta.url, (input) => checkMigration(input));
