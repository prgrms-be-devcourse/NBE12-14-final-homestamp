// guard-bash: AI가 위험한 터미널 명령을 실행하기 직전에 막는다 (PreToolUse).
// 사람이 터미널에서 직접 치는 명령은 막지 않는다.
import { getCommand, relativeToRepo, resolvePath, runHook } from "./lib/input.mjs";
import { normalize, parseSegments, programName } from "./lib/shell.mjs";

const MESSAGE = "이 명령은 팀 규칙상 막혀 있습니다. 필요하면 직접 실행하세요";

const SHELLS = new Set(["bash", "sh", "zsh", "dash"]);
const POWERSHELLS = new Set(["powershell", "pwsh"]);
const DB_CLIENTS = new Set(["psql", "mysql", "mariadb", "sqlite3", "sqlcmd"]);
const SQL_RE = /\bdrop\s+(table|database|schema)\b|\btruncate\s+(table\s+)?["\w]/i;
const RM_PROGRAMS = new Set(["rm", "remove-item", "ri", "del", "erase", "rmdir", "rd"]);
const PS_VALUE_PARAMS = new Set(["-filter", "-include", "-exclude", "-credential", "-stream"]);
const SHORT_FORCE_RE = /^-[a-zA-Z]*f[a-zA-Z]*$/;

export function checkBash(input) {
  const posix = !/powershell/i.test(input.toolName);
  const reason = checkCommand(getCommand(input.toolInput), input.cwd, posix, 0);
  return reason ? `${MESSAGE} (${reason})` : null;
}

function checkCommand(cmd, cwd, posix, depth) {
  if (!cmd || depth > 3) return null;
  const segments = parseSegments(cmd, { posix });
  for (const seg of segments) {
    const reason = checkSegment(normalize(seg), cwd, depth);
    if (reason) return reason;
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

function checkGit(args) {
  let i = 0;
  while (i < args.length && args[i].startsWith("-")) {
    if (["-C", "-c", "--git-dir", "--work-tree", "--namespace"].includes(args[i])) i++;
    i++;
  }
  const sub = args[i];
  const rest = args.slice(i + 1);
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

function dangerousTarget(target, cwd) {
  if (/[$`%]/.test(target)) return `삭제 대상이 변수라 확인할 수 없음: ${target}`;
  const glob = target.search(/[*?[]/);
  const base = glob >= 0 ? target.slice(0, glob) : target; // rm -rf * → 현재 폴더로 판단
  const rel = relativeToRepo(resolvePath(base || ".", cwd));
  if (rel === null) return `저장소 밖 삭제: ${target}`;
  if (rel === "") return `저장소 루트 삭제: ${target}`;
  if (rel === ".git" || rel.startsWith(".git/")) return `.git 삭제: ${target}`;
  return null;
}

runHook(import.meta.url, checkBash);
