// 셸 명령을 "명령 단위 → 토큰"으로 나누는 작은 파서.
// 완전한 셸 파서는 아니다. 따옴표 안의 글자를 명령으로 오해하지 않고,
// &&, ||, ;, |, 줄바꿈, 괄호, 백틱으로 이어진 명령을 각각 보는 정도만 한다.

/**
 * @returns {string[][]} 명령 단위 토큰 배열. 각 배열의 depth = 괄호 깊이
 * (`(cd x && ...)`, `$(...)` 안의 cd가 바깥 명령의 위치를 바꾸지 않게 하려고)
 */
export function parseSegments(cmd, { posix = true } = {}) {
  const segments = [];
  let tokens = [];
  let cur = "";
  let has = false;
  let quote = null;
  let depth = 0;
  const esc = posix ? "\\" : "`"; // PowerShell은 백틱이 이스케이프

  const pushTok = () => {
    if (has) tokens.push(cur);
    cur = "";
    has = false;
  };
  const pushSeg = () => {
    pushTok();
    if (tokens.length) {
      tokens.depth = depth;
      segments.push(tokens);
    }
    tokens = [];
  };

  for (let i = 0; i < cmd.length; i++) {
    const c = cmd[i];
    if (quote === "'") {
      if (c === "'") quote = null;
      else cur += c;
      continue;
    }
    if (quote === '"') {
      if (c === '"') quote = null;
      else if (posix && c === "\\" && i + 1 < cmd.length && '$`"\\\n'.includes(cmd[i + 1])) cur += cmd[++i];
      else cur += c;
      continue;
    }
    if (c === "'" || c === '"') {
      quote = c;
      has = true;
      continue;
    }
    if (c === esc && i + 1 < cmd.length) {
      cur += cmd[++i];
      has = true;
      continue;
    }
    if (c === "\n" || ";&|()".includes(c) || (posix && c === "`")) {
      pushSeg();
      if (c === "(") depth++;
      if (c === ")" && depth > 0) depth--;
      continue;
    }
    if (/\s/.test(c)) {
      pushTok();
      continue;
    }
    cur += c;
    has = true;
  }
  pushSeg();
  return segments;
}

/** 경로·확장자를 뗀 소문자 프로그램 이름 (C:\x\git.exe → git) */
export function programName(token) {
  return token
    .replace(/\\/g, "/")
    .split("/")
    .pop()
    .toLowerCase()
    .replace(/\.(exe|cmd|bat)$/, "");
}

const WRAPPERS = new Set(["sudo", "doas", "command", "builtin", "env", "exec", "time", "nohup", "nice", "xargs"]);

/** 앞에 붙은 VAR=값, sudo·env·xargs 같은 감싸는 명령을 걷어 내고 실제 프로그램과 인자를 돌려준다 */
export function normalize(tokens) {
  let i = 0;
  let viaXargs = false;
  while (i < tokens.length) {
    if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(tokens[i])) {
      i++;
      continue;
    }
    const name = programName(tokens[i]);
    if (!WRAPPERS.has(name)) break;
    if (name === "xargs") viaXargs = true;
    i++;
    while (i < tokens.length && tokens[i].startsWith("-")) i++;
  }
  return {
    program: i < tokens.length ? programName(tokens[i]) : "",
    args: tokens.slice(i + 1),
    viaXargs,
  };
}
