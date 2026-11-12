// Claude·Codex 훅 입력을 같은 모양으로 바꿔 주는 공통 모듈.
// 막는 훅은 check 함수만 export하고, runHook으로 실행한다 (테스트에서는 check만 import).
import { readFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const IS_WIN = process.platform === "win32";

/** 저장소 루트 (이 파일 위치 기준: scripts/hooks/lib → 루트) */
export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");

/** stdin JSON → { toolName, toolInput, cwd } */
export function readInput(text = readFileSync(0, "utf8")) {
  const raw = text.trim() ? JSON.parse(text) : {};
  return {
    toolName: raw.tool_name ?? raw.toolName ?? "",
    toolInput: raw.tool_input ?? raw.toolInput ?? {},
    cwd: raw.cwd || process.cwd(),
  };
}

/** 실행하려는 명령 문자열. Codex처럼 ["bash", "-lc", "..."] 배열이면 실제 스크립트만 꺼낸다 */
export function getCommand(toolInput) {
  const c = toolInput?.command ?? toolInput?.cmd ?? "";
  if (Array.isArray(c)) {
    const i = c.findIndex((a) => /^-[a-z]*c$/.test(a) || /^-command$/i.test(a));
    return i >= 0 && i + 1 < c.length ? c.slice(i + 1).join(" ") : c.join(" ");
  }
  return typeof c === "string" ? c : "";
}

const PATH_KEYS = ["file_path", "filePath", "path", "notebook_path"];
const PATCH_RE = /^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$/gm;

/** 수정하려는 파일의 절대 경로 목록 (Edit/Write 계열 + Codex apply_patch 본문) */
export function getFilePaths(toolInput, cwd) {
  const found = new Set();
  for (const key of PATH_KEYS) {
    if (typeof toolInput?.[key] === "string") found.add(toolInput[key]);
  }
  for (const s of collectStrings(toolInput)) {
    for (const m of s.matchAll(PATCH_RE)) found.add(m[1].trim());
  }
  return [...found].map((p) => resolvePath(p, cwd));
}

function collectStrings(v, out = []) {
  if (typeof v === "string") out.push(v);
  else if (Array.isArray(v)) v.forEach((x) => collectStrings(x, out));
  else if (v && typeof v === "object") Object.values(v).forEach((x) => collectStrings(x, out));
  return out;
}

/** Git Bash·WSL 경로(/c/..., /mnt/c/...)를 Windows 경로로 */
export function toNativePath(p) {
  if (!IS_WIN || typeof p !== "string") return p;
  const m = /^\/(?:mnt\/)?([a-zA-Z])(?:\/(.*))?$/.exec(p);
  return m ? `${m[1].toUpperCase()}:/${m[2] ?? ""}` : p;
}

export function resolvePath(p, cwd) {
  let target = p;
  if (target === "~" || target.startsWith("~/")) target = path.join(os.homedir(), target.slice(1));
  return path.resolve(toNativePath(cwd), toNativePath(target));
}

/** 저장소 기준 상대 경로 ("/" 구분). 루트 자신이면 "", 저장소 밖이면 null */
export function relativeToRepo(abs) {
  const rel = path.relative(REPO_ROOT, abs);
  if (rel === "") return "";
  if (rel === ".." || rel.startsWith(`..${path.sep}`) || path.isAbsolute(rel)) return null;
  return rel.split(path.sep).join("/");
}

function isMain(metaUrl) {
  if (!process.argv[1]) return false;
  const a = path.resolve(process.argv[1]);
  const b = fileURLToPath(metaUrl);
  return IS_WIN ? a.toLowerCase() === b.toLowerCase() : a === b;
}

/**
 * 훅 실행기. check(input)이 문자열을 돌려주면 exit 2 + 그 한 줄(= 막기),
 * null이면 아무 말 없이 exit 0. 훅 자체 오류는 막지 않고 exit 1로 알리기만 한다.
 */
export function runHook(metaUrl, check) {
  if (!isMain(metaUrl)) return;
  let reason;
  try {
    reason = check(readInput());
  } catch (e) {
    process.stderr.write(`[hook] ${path.basename(fileURLToPath(metaUrl))} 오류: ${e.message}\n`);
    process.exit(1);
  }
  if (reason) {
    process.stderr.write(`${reason}\n`);
    process.exit(2);
  }
  process.exit(0);
}
