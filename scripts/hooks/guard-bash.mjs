// guard-bash: AI가 위험한 터미널 명령을 실행하기 직전에 막는다 (PreToolUse).
// 사람이 터미널에서 직접 치는 명령은 막지 않는다.
import path from "node:path";
import { getCommand, relativeToRepo, resolvePath, runHook, toNativePath } from "./lib/input.mjs";
import { normalize, parseSegments, programName, splitHeredocs } from "./lib/shell.mjs";

const MESSAGE = "이 명령은 팀 규칙상 막혀 있습니다. 필요하면 직접 실행하세요";

const SHELLS = new Set(["bash", "sh", "zsh", "dash"]);
const POWERSHELLS = new Set(["powershell", "pwsh"]);
const DB_CLIENTS = new Set(["psql", "mysql", "mariadb", "sqlite3", "sqlcmd"]);
const SQL_RE = /\bdrop\s+(table|database|schema)\b|\btruncate\s+(table\s+)?["\w]/i;
const RM_PROGRAMS = new Set(["rm", "remove-item", "ri", "del", "erase", "rmdir", "rd"]);
const PS_VALUE_PARAMS = new Set(["-filter", "-include", "-exclude", "-credential", "-stream"]);
const SHORT_FORCE_RE = /^-[a-zA-Z]*f[a-zA-Z]*$/;
const CD_PROGRAMS = new Set(["cd", "pushd", "chdir", "set-location", "sl"]);
const UNKNOWN_RE = /[$`%]/;
const AI_TOOLS = "claude|anthropic|codex|openai|chatgpt|copilot|gemini";
const SIGNATURE_RE = new RegExp(
  `co-authored-by:[^\\n]*\\b(${AI_TOOLS})\\b|generated\\s+(with|by)\\s*\\[?\\s*(${AI_TOOLS})\\b`,
  "i",
);

export function checkBash(input) {
  const posix = !/powershell/i.test(input.toolName);
  const reason = checkCommand(getCommand(input.toolInput), input.cwd, posix, 0);
  return reason ? `${MESSAGE} (${reason})` : null;
}

function checkCommand(cmd, cwd, posix, depth) {
  if (!cmd || depth > 3) return null;
  // heredoc 본문은 데이터로 보고 뺀다. 단 셸이 읽는 본문(bash <<EOF, cat <<EOF | sh)은 명령으로 따로 검사
  const { script, heredocs } = posix ? splitHeredocs(cmd) : { script: cmd, heredocs: [] };
  const segments = parseSegments(script, { posix });
  // cd를 따라가며 지금 위치를 기억한다 (cd back && rm -rf ../front/dist → back 기준).
  // 괄호(서브셸) 안의 cd는 괄호가 끝나면 되돌린다. 위치를 알 수 없으면 null
  let here = cwd;
  let level = 0;
  const saved = [];
  for (const seg of segments) {
    while (level < seg.depth) (saved.push(here), level++);
    while (level > seg.depth) (here = saved.pop(), level--);
    const parsed = normalize(seg);
    if (CD_PROGRAMS.has(parsed.program)) {
      here = nextCwd(parsed.args, here, posix);
      continue;
    }
    const reason = checkSegment(parsed, here, depth);
    if (reason) return reason;
  }
  for (const { header, body } of heredocs) {
    const fedToShell = parseSegments(header).some((s) => {
      const p = normalize(s).program;
      return SHELLS.has(p) || POWERSHELLS.has(p);
    });
    const reason = fedToShell ? checkCommand(body, here, true, depth + 1) : null;
    if (reason) return reason;
  }
  // AI 도구 서명은 커밋·이슈·PR 명령에서만 본다 (heredoc 본문 포함, docs/decisions.md 2026-11-12)
  if (segments.some((seg) => publishesText(normalize(seg))) && SIGNATURE_RE.test(cmd)) {
    return "AI 도구 서명(Co-Authored-By, Generated with)은 커밋·이슈·PR에 넣지 않습니다";
  }
  // SQL은 DB 클라이언트로 보낼 때만 본다 (grep truncate, 커밋 메시지 등은 통과)
  const usesDbClient = segments.some((seg) => seg.some((t) => DB_CLIENTS.has(programName(t))));
  if (usesDbClient && SQL_RE.test(cmd)) return "SQL DROP/TRUNCATE";
  return null;
}

function checkSegment({ program, args, viaXargs }, cwd, depth) {
  if (SHELLS.has(program)) {
    const i = args.findIndex((a) => /^-[a-z]*c$/.test(a));
    if (i >= 0 && args[i + 1]) return checkCommand(args[i + 1], cwd, true, depth + 1);
  }
  if (POWERSHELLS.has(program)) {
    const i = args.findIndex((a) => /^-(c|command)$/i.test(a));
    if (i >= 0 && args[i + 1]) return checkCommand(args.slice(i + 1).join(" "), cwd, false, depth + 1);
  }
  if (program === "git") return checkGit(args);
  if (program === "flyway" && args.some((a) => a.toLowerCase() === "clean")) return "flyway clean";
  if (args.some((a) => /(^|:)flywayClean$/i.test(a) || /^flyway:clean$/i.test(a))) return "flyway clean";
  if (RM_PROGRAMS.has(program)) return checkRm(program, args, viaXargs, cwd);
  return null;
}

/** git 앞쪽 옵션(-C 경로 등)을 건너뛴 하위 명령과 나머지 인자 */
function gitSubcommand(args) {
  let i = 0;
  while (i < args.length && args[i].startsWith("-")) {
    if (["-C", "-c", "--git-dir", "--work-tree", "--namespace"].includes(args[i])) i++;
    i++;
  }
  return { sub: args[i], rest: args.slice(i + 1) };
}

/** 커밋·이슈·PR을 만들거나 고치는 명령인지 (본문에 AI 도구 서명이 들어가면 안 되는 곳) */
function publishesText({ program, args }) {
  if (program === "git") return gitSubcommand(args).sub === "commit";
  return program === "gh" && ["pr", "issue"].includes(args[0]);
}

function checkGit(args) {
  const { sub, rest } = gitSubcommand(args);
  const forced = (a) => a === "--force" || SHORT_FORCE_RE.test(a);

  // --force-with-lease는 허용
  if (sub === "push" && rest.some((a) => forced(a) || (a.startsWith("+") && a.length > 1))) return "git push --force";
  if (sub === "reset" && rest.includes("--hard")) return "git reset --hard";
  // -n / --dry-run은 지울 목록만 보여 주므로 -f와 함께 있어도 허용 (git clean -fdn)
  const dryRun = rest.some((a) => a === "--dry-run" || /^-[a-zA-Z]*n[a-zA-Z]*$/.test(a));
  if (sub === "clean" && rest.some(forced) && !dryRun) return "git clean -f";
  return null;
}

function checkRm(program, args, viaXargs, cwd) {
  const posixRm = program === "rm";
  let recursive = false;
  let endOfFlags = false;
  const targets = [];

  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (!endOfFlags && a === "--") {
      endOfFlags = true;
      continue;
    }
    if (!endOfFlags && posixRm && a.startsWith("-")) {
      if (a === "--recursive" || /^-[a-zA-Z]*[rR]/.test(a)) recursive = true;
      continue;
    }
    if (!posixRm && /^\/[a-z]$/i.test(a)) {
      if (a.toLowerCase() === "/s") recursive = true; // rd /s, del /s
      continue;
    }
    if (!posixRm && a.startsWith("-")) {
      if (/^-r/i.test(a)) recursive = true; // Remove-Item -Recurse
      if (PS_VALUE_PARAMS.has(a.toLowerCase())) i++;
      continue;
    }
    targets.push(a);
  }

  if (!recursive) return null;
  if (targets.length === 0) return viaXargs ? "xargs로 넘긴 대상 재귀 삭제" : null;
  for (const t of targets) {
    const reason = dangerousTarget(t, cwd);
    if (reason) return reason;
  }
  return null;
}

/** cd 다음 위치. 변수·`cd -`처럼 알 수 없으면 null */
function nextCwd(args, here, posix) {
  const target = args.find((a) => a === "-" || !a.startsWith("-"));
  if (target === undefined) return posix ? resolvePath("~", here ?? ".") : here; // bash의 cd는 홈으로
  if (target === "-" || UNKNOWN_RE.test(target)) return null;
  if (here === null && !isAbsolute(target)) return null;
  return resolvePath(target, here ?? ".");
}

function isAbsolute(p) {
  return p === "~" || p.startsWith("~/") || path.isAbsolute(toNativePath(p));
}

function dangerousTarget(target, cwd) {
  if (UNKNOWN_RE.test(target)) return `삭제 대상이 변수라 확인할 수 없음: ${target}`;
  const glob = target.search(/[*?[]/);
  const base = glob >= 0 ? target.slice(0, glob) : target; // rm -rf * → 현재 폴더로 판단
  if (cwd === null && !isAbsolute(base)) return `cd 위치를 알 수 없어 삭제 대상을 확인할 수 없음: ${target}`;
  const rel = relativeToRepo(resolvePath(base || ".", cwd ?? "."));
  if (rel === null) return `저장소 밖 삭제: ${target}`;
  if (rel === "") return `저장소 루트 삭제: ${target}`;
  if (rel === ".git" || rel.startsWith(".git/")) return `.git 삭제: ${target}`;
  return null;
}

runHook(import.meta.url, checkBash);
