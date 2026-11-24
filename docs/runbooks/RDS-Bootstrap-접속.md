# RDS Bootstrap 접속 런북

운영 담당이 **자기 노트북에서** 비공개 RDS(PostgreSQL 18)에 마스터 계정으로 붙어 Role Bootstrap(`infra/db/bootstrap_roles.sql`)과 점검(`infra/db/check_human_access.sql`)을 돌리는 절차다. SQL의 변수와 역할 표는 `docs/runbooks/DB-보관-삭제.md` "운영 Role Bootstrap"(#185)을 따른다.

- 언제: 최초 설치, 역할 구조가 바뀔 때. **일반 배포에서는 돌리지 않는다**(배포는 Migration 계정, decisions 2026-11-25)
- 누가: 운영 담당 1명(`homestamp-ops-admin`, 세션 이름 = GitHub 아이디). 실행 전 팀 작업 대장에 목적·시각을 적는다
- 이 문서는 아직 실제 AWS에서 돌려 보지 않았다. 처음 실행할 때 각 단계의 결과를 확인하며 고친다

```
노트북 127.0.0.1:15432 ──SSM(homestamp-rds-forward)──▶ 앱 EC2 ──5432──▶ RDS
psql: host=<RDS 주소>(인증서 이름 확인)  hostaddr=127.0.0.1(실제로 붙는 곳)  sslmode=verify-full
```

## 지켜야 할 것

- Secret 값·비밀번호를 화면에 출력하지 않는다. 명령 인자, 셸 기록, GitHub, 이슈·채팅, 로그에 넣지 않는다
- 비밀번호는 노트북의 임시 `PGPASSFILE`(0600)에만 두고 끝나면 지운다. **EC2에는 두지 않는다**(셸로 EC2에서 psql을 돌리지 않는다. 셸 세션 기록에 남을 수 있다)
- `sslmode`를 `require` 이하로 낮추지 않는다. 인증서 확인이 안 되면 멈춘다
- 실패해도 10·11단계(임시 파일 삭제, 세션 종료)는 반드시 한다(아래 `trap`)

## 준비물 (노트북)

- AWS CLI v2, Session Manager plugin, `psql`(PostgreSQL 18 클라이언트), `python3`
- `~/.aws/config`의 관리 역할 프로필(`homestamp-ops-admin`, `role_session_name` = 자기 GitHub 아이디)
- RDS 인증서 묶음: AWS 공식 `https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem`. 받은 뒤 SHA-256을 계산해 04 6장 "공개 파일" 줄에 기록된 값과 비교한다(**아직 기록된 값이 없다**. 첫 실행 때 기록하고, 이후에는 다르면 멈춘다)
- Terraform 출력 두 개(인프라 담당이 `terraform output`으로 확인. 비밀값은 아니지만 계정 번호가 들어 있어 채팅에 붙이지 않는다): `db_endpoint_address`, `db_master_secret_arn`, 그리고 `web_instance_id`

## 절차

### 1. 실행 확인
- 실행자가 운영 담당인지, 대상이 우리 RDS(`homestamp-db`)인지, 새 빈 DB인지(기존 DB면 #185 런북의 사전 조사부터) 확인하고 대장에 시작 시각을 적는다

### 2. 임시 관리자 자격 증명
```sh
export AWS_PROFILE=homestamp-ops-admin AWS_REGION=ap-northeast-2
aws sts get-caller-identity --query 'Arn' --output text | sed -E 's/[0-9]{12}/<acct>/'   # assumed-role/homestamp-ops-admin/<GitHub 아이디> 인지
```

### 3~4. Secret 조회 → 임시 PGPASSFILE (값은 화면에 나오지 않는다)
```sh
export PGHOST='<db_endpoint_address>' PGHOSTADDR=127.0.0.1 PGPORT=15432 PGDATABASE=homestamp
export PGSSLMODE=verify-full PGSSLROOTCERT="$HOME/.homestamp/ap-northeast-2-bundle.pem"
SECRET_ARN='<db_master_secret_arn>'; INSTANCE_ID='<web_instance_id>'

umask 077
PGPASSFILE="$(mktemp)"; export PGPASSFILE
cleanup() { rm -f "$PGPASSFILE"; [ -n "${SSM_PID:-}" ] && kill "$SSM_PID" 2>/dev/null; }
trap cleanup EXIT INT TERM

aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" --query SecretString --output text \
  | python3 -I -c '
import json, os, sys
s = json.load(sys.stdin)
esc = lambda v: v.replace("\\", "\\\\").replace(":", "\\:")
print(":".join([os.environ["PGHOST"], os.environ["PGPORT"], os.environ["PGDATABASE"], esc(s["username"]), esc(s["password"])]))
' > "$PGPASSFILE"
stat -f '%Lp' "$PGPASSFILE" 2>/dev/null || stat -c '%a' "$PGPASSFILE"   # 600 이어야 한다
```
- libpq는 `.pgpass`의 호스트 칸을 `host`와 맞춘다(`hostaddr`가 아님). 그래서 첫 칸은 RDS 주소다
- 마스터 사용자 이름은 Secret에 함께 들어 있다(Terraform `db_master_username`). 접속할 때 `PGUSER`로도 넣는다: `export PGUSER=homestamp_master`

### 5. SSM 포트 포워딩 시작
```sh
aws ssm start-session --target "$INSTANCE_ID" --document-name homestamp-rds-forward \
  --parameters 'localPortNumber=15432' > /dev/null 2>&1 &
SSM_PID=$!
sleep 5
```
- 목적지(RDS 주소:5432)는 문서에 박혀 있어 요청에서 고르지 않는다. 범용 `AWS-StartPortForwardingSessionToRemoteHost`는 관리 역할에 없다

### 6. TLS `verify-full` 접속 확인
```sh
psql -X -At -c "SELECT current_user, ssl, version FROM pg_stat_ssl JOIN pg_stat_activity USING (pid) WHERE pid = pg_backend_pid();"
```
- `homestamp_master|t|TLSv1.3`(또는 1.2)처럼 나와야 한다. 인증서 이름 오류가 나면 `PGHOST`가 RDS 주소인지 확인한다. `localhost`로 바꾸거나 `sslmode`를 낮추지 않는다

### 7. Bootstrap
#185 런북의 명령 그대로(역할 이름 변수만, 비밀번호 없음):
```sh
psql -X -v ON_ERROR_STOP=1 -v owner_role=... -v migration_role=... -v app_role=... -v purge_role=... -v human_role=... -f infra/db/bootstrap_roles.sql
```

### 8. 점검
```sh
psql -X -v ON_ERROR_STOP=1 -v human_role=homestamp_human_read -f infra/db/check_human_access.sql
```

### 9. 결과 판단
- 7·8 모두 종료 코드 0이어야 성공. 하나라도 0이 아니면 멈추고, 출력에서 비밀값이 아닌 오류 문장만 대장에 적는다. 같은 단계를 고쳐 다시 돌리기 전에 원인을 팀에 공유한다

### 10~11. 정리 (실패해도 `trap`이 한다)
```sh
cleanup; trap - EXIT INT TERM
aws ssm describe-sessions --state Active --query 'Sessions[].SessionId' --output text   # 내 세션이 남아 있으면 terminate-session
unset PGPASSFILE PGHOST PGHOSTADDR PGPORT PGDATABASE PGUSER SECRET_ARN
```

### 12. 마스터 Secret 즉시 교체
Bootstrap에서 노트북으로 꺼낸 비밀번호의 유효 기간을 줄인다(직접 접근을 막는 경계는 아니다, 04 6장).
```sh
aws secretsmanager rotate-secret --secret-id "$SECRET_ARN"
```
- ⚠ **미정**: 관리 역할은 지금 `GetSecretValue`만 가진다. 교체를 관리 역할로 할지(`secretsmanager:RotateSecret`을 같은 Secret 하나에 더함) 공용 팀 사용자로 할지 정해야 한다. 정하기 전에는 이 단계를 실행하지 않는다

### 13. 교체 뒤 접속 확인
- 3~6단계를 다시 해서 새 비밀번호로 붙는지 확인한다(새 값도 같은 방식으로 읽고, 끝나면 10~11단계). 교체가 끝나기 전이면 이전 값이 나올 수 있으니 잠시 뒤 다시 한다

### 14. 기록
- 대장: 실행자, 목적, 시작·종료 시각, 7·8·12·13 결과(성공·실패만)
- 감사 증거: CloudTrail 이벤트 기록에서 `AssumeRole`, `GetSecretValue`, `StartSession`, `TerminateSession`, `RotateSecret`의 시각과 주체(`assumed-role/homestamp-ops-admin/<세션 이름>`)를 대장과 대조한다

## apply 뒤 1회 확인 (보안 경계로 쓰기 전)

AWS 문서는 "문서의 `parameters`에 없는 값은 StartSession에서 덮어쓸 수 없다"를 명시하지 않는다. 그래서 문서를 만든 뒤 한 번, 목적지를 바꾸는 요청이 **거절되는지** 확인하고 결과를 대장에 남긴다(RDS가 아닌 주소로 실제 연결이 열리면 문서를 쓰지 않고 팀에 알린다).
```sh
aws ssm start-session --target "$INSTANCE_ID" --document-name homestamp-rds-forward \
  --parameters 'host=localhost,portNumber=22,localPortNumber=15433'
```

## 감사와 한계

| 항목 | 남는 것 | 한계 |
|---|---|---|
| CloudTrail | `AssumeRole`·`GetSecretValue`·`StartSession`·`RotateSecret` 같은 API 호출, 시각, 주체 | 세션 이름은 입력한 값이라 신원 증명이 아니다. 공용 팀 사용자 키로 직접 부른 호출은 사람을 구분하지 못한다. **추적(Trail)은 아직 없어** 이벤트 기록(90일)만 있다 |
| SSM 세션 로그 | 없음(포트 포워딩 세션은 Session Manager 로깅 대상이 아니다. 시작·종료는 CloudTrail에 남는다) | 오간 내용(SQL)은 S3·CloudWatch 어디에도 남지 않는다 |
| PostgreSQL | RDS `postgresql` 로그(CloudWatch, 365일, terse) | 실행한 SQL을 남기려면 `log_statement` 또는 pgAudit가 필요하다(아직 없음). 켜더라도 비밀번호가 들어가는 문장이 로그에 남지 않게 한다 |
| 교육 계정 | — | 공용 팀 사용자는 `AdministratorAccess`라 이 절차를 거치지 않고 Secret을 읽거나 세션 문서를 바꿀 수 있다. 팀 약속·대장·CloudTrail 대조로 관리한다(04 8장) |
