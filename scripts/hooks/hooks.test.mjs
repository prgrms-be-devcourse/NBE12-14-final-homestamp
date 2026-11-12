// 훅 테스트: node --test scripts/hooks/
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { checkBash } from "./guard-bash.mjs";
import { checkMigration } from "./guard-migration.mjs";
import { checkSecrets } from "./guard-secrets.mjs";
import { REPO_ROOT } from "./lib/input.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const bash = (command, cwd = REPO_ROOT) => checkBash({ toolName: "Bash", toolInput: { command }, cwd });
const ps = (command, cwd = REPO_ROOT) => checkBash({ toolName: "PowerShell", toolInput: { command }, cwd });
const edit = (file_path, toolName = "Edit") => ({ toolName, toolInput: { file_path }, cwd: REPO_ROOT });

describe("guard-bash", () => {
  const blocked = [
    "git push --force",
    "git push -f origin main",
    "git push origin +main",
    "git -C back push --force",
    "git reset --hard HEAD~1",
    "git clean -fd",
    "git clean -xdf",
    "git clean -d --force",
    "cd back && ./gradlew flywayClean",
    "flyway clean",
    "rm -rf /",
    "rm -rf ~",
    "rm -rf ..",
    "rm -rf ../other-project",
    "rm -rf *",
    "rm -rf .",
    "rm -rf .git",
    "rm -rf $DIR",
    "find . -name x | xargs rm -rf",
    "sudo rm -rf /",
    'bash -c "git reset --hard"',
    'psql -c "DROP TABLE users"',
    "echo 'truncate table users;' | psql",
    'docker exec db psql -U app -c "drop database app"',
    "echo ok; git push -f",
  ];
  for (const cmd of blocked) it(`막음: ${cmd}`, () => assert.ok(bash(cmd)));

  const allowed = [
    "git push",
    "git push -u origin feat/back/x",
    "git push --force-with-lease",
    "git reset --soft HEAD~1",
    "git clean -n",
    "git clean -fdn",
    "git clean -nfx",
    "git clean -f --dry-run",
    "rm -rf back/build",
    "rm -rf front/node_modules front/dist",
    "rm -rf build/*",
    "rm file.txt",
    'git commit -m "fix: rm -rf 버그와 git push --force 문서 수정"',
    "grep -rn truncate src",
    "echo 'DROP TABLE users'",
    "./gradlew test",
  ];
  for (const cmd of allowed) it(`통과: ${cmd}`, () => assert.equal(bash(cmd), null));

  it("하위 폴더에서도 저장소 기준으로 판단", () => {
    assert.equal(bash("rm -rf build", path.join(REPO_ROOT, "back")), null);
    assert.ok(bash("rm -rf ../..", path.join(REPO_ROOT, "back")));
  });

  it("명령 안의 cd를 따라가서 판단", () => {
    // 전에는 루트 기준으로 봐서 저장소 밖 삭제로 잘못 막았다
    assert.equal(bash("cd .agents/skills && cd .. && rm -rf ../.claude/skills"), null);
    assert.equal(bash("cd back && rm -rf ../front/dist"), null);
    assert.equal(bash("cd back; rm -rf build"), null);
    // 반대로 루트 기준이면 통과했을 명령을 막는다
    assert.ok(bash("cd back && rm -rf ../.."));
    assert.ok(bash("cd .. && rm -rf build"));
    assert.ok(bash("cd back/src && rm -rf ../.."));
  });

  it("서브셸 안의 cd는 바깥에 영향 없음", () => {
    assert.ok(bash("(cd back/src) && rm -rf ../front")); // cd가 새면 back/front로 오해해서 통과시킴
    assert.ok(bash("echo $(cd back/src && pwd) && rm -rf ../front"));
    assert.equal(bash("(cd .. && ls) ; rm -rf build"), null);
  });

  it("cd 위치를 알 수 없으면 상대 경로 삭제를 막음", () => {
    assert.ok(bash("cd $DIR && rm -rf build"));
    assert.ok(bash("cd - && rm -rf build"));
    assert.ok(bash("cd && rm -rf build")); // 홈으로 이동 → 저장소 밖
  });

  it("PowerShell", () => {
    assert.ok(ps("Remove-Item -Recurse -Force C:\\"));
    assert.ok(ps("Remove-Item -Recurse ..\\other"));
    assert.ok(ps("git push --force"));
    assert.equal(ps("Remove-Item -Recurse -Force front\\dist"), null);
    assert.equal(ps("Remove-Item foo.txt"), null);
  });

  it("Codex 배열 명령", () => {
    const r = checkBash({ toolName: "shell", toolInput: { command: ["bash", "-lc", "git reset --hard"] }, cwd: REPO_ROOT });
    assert.ok(r);
  });
});

describe("guard-secrets", () => {
  for (const f of [".env", "back/.env.local", "certs/server.pem", "a/private.key", "back/src/main/resources/application-prod.yaml", "application-local.yml", "application-secret.properties"]) {
    it(`막음: ${f}`, () => assert.ok(checkSecrets(edit(f))));
  }
  for (const f of [".env.example", "back/src/main/resources/application.yaml", "front/src/env.ts", "keys.ts"]) {
    it(`통과: ${f}`, () => assert.equal(checkSecrets(edit(f, "Write")), null));
  }
  it("Codex apply_patch 본문에서 경로를 찾음", () => {
    const patch = "*** Begin Patch\n*** Update File: back/.env\n@@\n-A=1\n+A=2\n*** End Patch";
    assert.ok(checkSecrets({ toolName: "apply_patch", toolInput: { command: ["apply_patch", patch] }, cwd: REPO_ROOT }));
  });
});

describe("guard-migration", () => {
  const dir = "back/src/main/resources/db/migration";
  const onDev = (rel) => rel.endsWith("V1__init.sql");

  it("dev에 있는 V 파일은 막음", () => assert.ok(checkMigration(edit(`${dir}/V1__init.sql`), onDev)));
  it("새 V 파일은 통과", () => assert.equal(checkMigration(edit(`${dir}/V2__add_user.sql`, "Write"), onDev), null));
  it("R(반복) 파일과 다른 경로는 통과", () => {
    assert.equal(checkMigration(edit(`${dir}/R__views.sql`), () => true), null);
    assert.equal(checkMigration(edit("docs/V1__init.sql"), () => true), null);
  });
});

describe("실행 (exit 코드)", () => {
  const run = (script, payload) =>
    spawnSync(process.execPath, [path.join(HERE, script)], { input: JSON.stringify(payload), encoding: "utf8" });

  it("막으면 exit 2 + 이유 한 줄", () => {
    const r = run("guard-bash.mjs", { tool_name: "Bash", tool_input: { command: "git reset --hard" }, cwd: REPO_ROOT });
    assert.equal(r.status, 2);
    assert.match(r.stderr, /팀 규칙상 막혀/);
  });
  it("통과하면 exit 0, 출력 없음", () => {
    const r = run("guard-secrets.mjs", { tool_name: "Edit", tool_input: { file_path: "AGENTS.md" }, cwd: REPO_ROOT });
    assert.equal(r.status, 0);
    assert.equal(r.stdout + r.stderr, "");
  });
  it("훅 오류는 막지 않고 exit 1", () => {
    const r = spawnSync(process.execPath, [path.join(HERE, "guard-bash.mjs")], { input: "{broken", encoding: "utf8" });
    assert.equal(r.status, 1);
  });
});
