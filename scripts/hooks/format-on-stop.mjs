// Stop 훅: AI가 작업을 끝낼 때 바뀐 파일의 서식을 한 번 맞춘다 (Claude·Codex 공용).
// - back/ 의 .java가 바뀌었으면 그 파일만 spotlessApply (구글 자바 스타일, Spotless IDE 훅으로 파일 하나씩)
// - front/ 의 파일이 바뀌었으면 그 파일만 prettier --write
// 작업은 절대 막지 않는다 (언제나 exit 0). 서식을 바꿨거나 실패했을 때만 한 줄 알린다.
// 끄기: 환경 변수 HOMESTAMP_SKIP_FORMAT=1
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { IS_WIN, REPO_ROOT, isMain } from "./lib/input.mjs";

const FRONT_EXT = /\.(?:[cm]?[jt]sx?|json|css|scss|html|md|ya?ml)$/i;
const TIMEOUT_MS = 170_000; // 등록 파일의 timeout(180초)보다 조금 짧게

/** `git status --porcelain=v1 -z` 출력 → 지금 있는(지워지지 않은) 파일 경로 목록 (저장소 기준, "/" 구분) */
export function parsePorcelain(text) {
  const parts = text.split("\0");
  const files = [];
  for (let i = 0; i < parts.length; i++) {
    const entry = parts[i];
    if (entry.length < 4) continue;
    const xy = entry.slice(0, 2);
    const file = entry.slice(3);
    if (xy[0] === "R" || xy[0] === "C") i++; // 이름 바꾸기·복사는 다음 칸이 옛 경로
    if (xy.includes("D")) continue; // 지운 파일은 서식 대상이 아님
    files.push(file);
  }
  return files;
}

/** 바뀐 파일 목록 → 무엇을 돌릴지 */
export function planFormat(files) {
  const java = files.filter((f) => /^back\/src\/[^/]+\/java\/.+\.java$/.test(f));
  const front = files
    .filter((f) => f.startsWith("front/") && FRONT_EXT.test(f))
    .filter((f) => !/^front\/(?:node_modules|dist)\//.test(f) && f !== "front/package-lock.json")
    .map((f) => f.slice("front/".length));
  return { java, front };
}

function hashOf(abs) {
  try {
    return createHash("sha1").update(readFileSync(abs)).digest("hex");
  } catch {
    return null;
  }
}

function run(cmd, args, cwd, shell = false, timeout = TIMEOUT_MS) {
  const r = spawnSync(cmd, args, { cwd, shell, encoding: "utf8", timeout, windowsHide: true });
  return { ok: r.status === 0 && !r.error, out: `${r.stderr ?? ""}${r.stdout ?? ""}`.trim(), error: r.error };
}

/** 바뀐 .java 파일만 하나씩 서식을 맞춘다. 바꾸지 않은 파일은 건드리지 않는다 (Spotless IDE 훅: -PspotlessIdeHook=<절대 경로>) */
function formatJava(files, deadline) {
  const back = path.join(REPO_ROOT, "back");
  const build = path.join(back, "build.gradle");
  if (!existsSync(build) || !readFileSync(build, "utf8").includes("spotless")) return { skipped: true };
  let failed = 0;
  let timedOut = 0;
  for (const rel of files) {
    const left = deadline - Date.now();
    if (left < 5_000) {
      timedOut++;
      continue;
    }
    const abs = path.join(REPO_ROOT, rel);
    // Windows: cmd가 현재 폴더를 찾지 않을 수 있어(NoDefaultCurrentDirectoryInExePath) 전체 경로로 부른다
    const r = IS_WIN
      ? run(`"${path.join(back, "gradlew.bat")}" -q spotlessApply "-PspotlessIdeHook=${abs}"`, [], back, true, left)
      : run("sh", ["gradlew", "-q", "spotlessApply", `-PspotlessIdeHook=${abs}`], back, false, left);
    if (r.error?.code === "ETIMEDOUT") timedOut++;
    else if (!r.ok) failed++;
  }
  return { ok: failed === 0 && timedOut === 0, failed, timedOut };
}

function formatFront(files) {
  const front = path.join(REPO_ROOT, "front");
  const bin = path.join(front, "node_modules", "prettier", "bin", "prettier.cjs");
  if (!existsSync(bin)) return { skipped: true, reason: "front에서 npm install이 필요" };
  return run(process.execPath, [bin, "--write", "--ignore-unknown", "--log-level", "warn", ...files], front);
}

/** 실제 실행. 알릴 말이 있으면 한 줄, 없으면 null */
export function formatOnStop() {
  if (process.env.HOMESTAMP_SKIP_FORMAT === "1") return null;
  const status = spawnSync("git", ["status", "--porcelain=v1", "-z", "--untracked-files=all"], {
    cwd: REPO_ROOT,
    encoding: "utf8",
    windowsHide: true,
  });
  if (status.status !== 0) return null;
  const { java, front } = planFormat(parsePorcelain(status.stdout));
  if (!java.length && !front.length) return null;

  const targets = [...java, ...front.map((f) => `front/${f}`)].map((f) => path.join(REPO_ROOT, f));
  const before = new Map(targets.map((t) => [t, hashOf(t)]));
  const notes = [];

  if (java.length) {
    const r = formatJava(java, Date.now() + TIMEOUT_MS);
    if (!r.skipped && !r.ok) {
      const why = [r.failed && `실패 ${r.failed}개`, r.timedOut && `시간 초과 ${r.timedOut}개`].filter(Boolean).join(", ");
      notes.push(`spotlessApply ${why} — 직접 \`cd back && ./gradlew spotlessApply\`로 확인하세요`);
    }
  }
  if (front.length) {
    const r = formatFront(front);
    if (r.skipped && r.reason) notes.push(`프론트 서식은 건너뜀(${r.reason})`);
    else if (!r.ok) notes.push("prettier 실패 — 직접 `cd front && npm run format`으로 확인하세요");
  }

  const changed = targets.filter((t) => hashOf(t) !== before.get(t));
  if (changed.length) {
    const names = changed.map((t) => path.relative(REPO_ROOT, t).split(path.sep).join("/"));
    const shown = names.slice(0, 5).join(", ") + (names.length > 5 ? ` 외 ${names.length - 5}개` : "");
    notes.unshift(`서식을 맞췄어요: ${shown}`);
  }
  return notes.length ? `[format-on-stop] ${notes.join(" · ")}` : null;
}

if (isMain(import.meta.url)) {
  try {
    readFileSync(0, "utf8"); // 입력은 쓰지 않지만 읽어 둔다
  } catch {}
  let message = null;
  try {
    message = formatOnStop();
  } catch (e) {
    message = `[format-on-stop] 훅 오류: ${e.message}`;
  }
  if (message) process.stdout.write(`${JSON.stringify({ systemMessage: message })}\n`);
  process.exit(0);
}
