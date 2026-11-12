// guard-secrets: AI가 비밀값 파일을 고치기 직전에 막는다 (PreToolUse).
import path from "node:path";
import { getFilePaths, runHook } from "./lib/input.mjs";

const MESSAGE = "비밀값 파일은 AI가 수정하지 않습니다. 운영 담당에게 요청하세요";

/** 파일 이름(소문자)이 비밀값 파일인지. 루트 .gitignore의 비밀값 목록과 맞춘다 */
export function isSecretFile(name) {
  if (name === ".env") return true;
  if (name.startsWith(".env.")) return name !== ".env.example";
  if (/\.(pem|key|p12|pfx|tfvars)$/.test(name)) return true;
  return /^application-(prod|local|secret)[^/]*\.(ya?ml|properties)$/.test(name);
}

export function checkSecrets(input) {
  for (const abs of getFilePaths(input.toolInput, input.cwd)) {
    const name = path.basename(abs);
    if (isSecretFile(name.toLowerCase())) return `${MESSAGE} (${name})`;
  }
  return null;
}

runHook(import.meta.url, checkSecrets);
