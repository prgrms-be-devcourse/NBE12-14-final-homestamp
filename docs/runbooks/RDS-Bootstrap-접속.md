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
- 마스터 비밀번호는 노트북의 임시 `PGPASSFILE`(0600)에만 두고 끝나면 지운다. **EC2에는 두지 않는다**(셸로 EC2에서 psql을 돌리지 않는다. 셸 세션 기록에 남을 수 있다). EC2에 두는 것은 Migration 비밀 파일뿐이다(배포-블루그린 "Migration 비밀 폴더")
- `sslmode`를 `require` 이하로 낮추지 않는다. 인증서 확인이 안 되면 멈춘다
- 실패해도 11단계(임시 파일 삭제, 포워딩 종료)는 반드시 한다(아래 `trap`)

## 준비물 (노트북)

- AWS CLI v2, Session Manager plugin, `psql`(PostgreSQL 18 클라이언트), `python3`
- `~/.aws/config`의 관리 역할 프로필(`homestamp-ops-admin`, `role_session_name` = 자기 GitHub 아이디)
- RDS 인증서 묶음: 원본은 04 6장 "공개 파일" 줄의 저장소 파일 `back/certs/rds-ca-ap-northeast-2.crt`다. 앱 이미지가 `/app/certs/rds-ca.crt`로 복사하는 것과 **같은 파일**을 쓴다. 이 파일은 AWS 공식 지역 묶음(`https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem`)을 이름만 바꿔 저장소에 넣은 것이다
  - 저장소에 있으면: 그 파일의 SHA-256이 04 6장에 기록된 값과 같은지 확인하고 쓴다. 다르면 멈춘다
  - 아직 없으면(이미지 작업 G2/G4 전): AWS URL에서 받아 SHA-256을 계산하고, 그 파일을 `back/certs/`에 넣는 PR에서 SHA-256도 04 6장 "공개 파일" 줄에 함께 기록한다. 그 PR이 들어간 뒤에 이 절차를 시작한다. 노트북 사본과 저장소 파일을 따로 두지 않는다
- Terraform 출력 세 개(인프라 담당이 `terraform output`으로 확인. 비밀값은 아니지만 계정 번호가 들어 있어 채팅에 붙이지 않는다): `db_endpoint_address`, `db_master_secret_arn`, `web_instance_id`

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
export PGHOST='<db_endpoint_address>' PGHOSTADDR=127.0.0.1 PGPORT=15432 PGDATABASE=homestamp   # DB 이름은 Terraform db_name 기본값
export PGSSLMODE=verify-full PGSSLROOTCERT="$PWD/back/certs/rds-ca-ap-northeast-2.crt"   # 저장소 루트에서 실행
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
- 마스터 사용자 이름은 Secret에 함께 들어 있다(Terraform `db_master_username`). 접속할 때 `PGUSER`로도 넣는다: `export PGUSER=homestamp_master`(Terraform `db_master_username` 기본값. 바꿨으면 그 값)

### 5. SSM 포트 포워딩 시작
```sh
aws ssm start-session --target "$INSTANCE_ID" --document-name homestamp-rds-forward \
  --parameters 'localPortNumber=15432' > /dev/null 2>&1 &
SSM_PID=$!
sleep 5
```
- 6단계가 연결 거절(`connection refused`)로 실패하면 포워딩이 아직 열리지 않은 것이다. 몇 초 뒤 6단계만 다시 한다(`sslmode`는 낮추지 않는다)
- 목적지(RDS 주소:5432)는 문서에 박혀 있어 요청에서 고르지 않는다. 범용 `AWS-StartPortForwardingSessionToRemoteHost`는 관리 역할에 없다

### 6. TLS `verify-full` 접속 확인
```sh
psql -X -At -c "SELECT current_user, ssl, version FROM pg_stat_ssl JOIN pg_stat_activity USING (pid) WHERE pid = pg_backend_pid();"
```
- `homestamp_master|t|TLSv1.3`(또는 1.2)처럼 나와야 한다. 인증서 이름 오류가 나면 `PGHOST`가 RDS 주소인지 확인한다. `localhost`로 바꾸거나 `sslmode`를 낮추지 않는다

### 7. Bootstrap
#185 런북(`DB-보관-삭제.md` "운영 Role Bootstrap")의 명령 그대로다. 역할 이름은 운영 이름(decisions 2026-11-28, 이미지 `roles.properties`와 같음)을 변수로 넣는다. 테스트 이름(`homestamp_*`)을 쓰지 않는다. 비밀번호는 없다:
```sh
psql -X -v ON_ERROR_STOP=1 \
  -v owner_role=hs_owner \
  -v migration_role=hs_migrator \
  -v app_role=hs_app \
  -v purge_role=hs_purge \
  -v human_role=hs_human_read \
  -f infra/db/bootstrap_roles.sql
```

### 8. 점검
```sh
psql -X -v ON_ERROR_STOP=1 -v human_role=hs_human_read -f infra/db/check_human_access.sql   # 7단계와 같은 이름
```

### 9. 결과 판단
- 7·8 모두 종료 코드 0이어야 성공. 하나라도 0이 아니면 멈추고, 출력에서 비밀값이 아닌 오류 문장만 대장에 적는다. 같은 단계를 고쳐 다시 돌리기 전에 원인을 팀에 공유한다
- 실패해도 아래 10~11단계(세션·파일 정리)는 하고, 12단계(교체)는 원인을 정리한 뒤 따로 정한다

### 9b. 로그인 역할 비밀번호 (처음 설치 때, 같은 세션에서)
- **대상은 `hs_app`·`hs_migrator` 둘뿐이다.** `hs_owner`와 `hs_human_read`는 NOLOGIN이다. `hs_purge`는 LOGIN이지만 비밀번호를 정하지 않는다. 비밀번호가 없으면 비밀번호 인증이 실패하고 IAM DB 인증은 꺼져 있다(`rds.tf` `iam_database_authentication_enabled = false`). 그래서 Purge 작업자를 만들 때까지 지금의 운영 인증 방식으로는 이 역할로 직접 로그인할 수 없다. 역할 자체는 관리자(마스터)가 그대로 관리할 수 있다. 사람별 LOGIN 역할은 DB-보관-삭제 "사람별 Human 계정"을 따른다
- **값 만들기**: AI 도구·화면 녹화 밖의 별도 터미널에서 `openssl rand -hex 32`(64자, 셸·URL 특수 문자 없음)로 만든다. **하나씩** 만들고 DB에 정한 뒤 바로 공급처에 넣고 지운다
- **DB에 정하기**: 이 psql 세션에서 `\password hs_app`, 프롬프트에 두 번 붙여 넣는다. psql은 해시(SCRAM)로 바꿔 보내므로 평문이 psql 기록·DB 로그에 남지 않는다. `ALTER ROLE ... PASSWORD '<값>'`을 직접 치지 않는다(평문이 `~/.psql_history`에 남고, `log_statement`를 켜면 DB 로그에도 남는다)
- **App 공급**: 콘솔 → Systems Manager → Parameter Store. 두 값을 넣는다(04 6장 표, 형식이 다르면 앱이 시작하지 않는다)
  - `/homestamp/prod/app/spring.datasource.username`: `hs_app`, **SecureString**
  - `/homestamp/prod/app/spring.datasource.password`: 새 값, **SecureString**, KMS 키는 기본(`alias/aws/ssm`)
  - CLI `put-parameter --value`는 쓰지 않는다(셸 기록·프로세스 인자에 남는다)
- **Migration 공급**: `\password hs_migrator`로 정한 뒤 EC2에서 배포-블루그린 "Migration 비밀 폴더 → 처음 만들기"를 한다
- 끝나면 클립보드를 비운다(macOS `pbcopy </dev/null`). 값은 노트북 어디에도 남기지 않는다
- **확인(값 없이)**: App은 첫 배포의 헬스. Migration은 아래 "Migration 비밀번호 확인"으로 DB 인증을 바로 보고, EC2 파일과 JDBC 접속 전체는 첫 배포의 Migration 단계로 본다(배포-블루그린 "Migration 비밀 폴더 → 확인")

### 10. 다른 마스터 세션 정리 (아직 붙어 있는 동안)
비밀번호를 바꿔도 **이미 열린 세션은 끊기지 않는다**(PostgreSQL은 접속할 때만 인증한다. 실환경 확인 NOT_RUN). 그래서 마스터 계정으로 열린 다른 세션을 먼저 확인하고 끊는다.
```sh
psql -X -At -c "SELECT pid, application_name, backend_start FROM pg_stat_activity WHERE usename = current_user AND pid <> pg_backend_pid();"
# 내 것이 아닌 세션이 있으면 대장에 적고 끊는다
psql -X -At -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = current_user AND pid <> pg_backend_pid();"
```

### 11. 노트북 정리 (실패해도 `trap`이 한다)
```sh
cleanup; trap - EXIT INT TERM                       # 임시 PGPASSFILE 삭제, 포워딩 종료
aws ssm describe-sessions --state Active --query 'Sessions[].SessionId' --output text   # 내 세션이 남아 있으면 terminate-session
unset PGPASSFILE                                    # PGHOST·SECRET_ARN 등 위치 값은 13~14단계에서 다시 쓴다
```

### 12. 마스터 비밀번호 즉시 교체 (승인 뒤)
Bootstrap에서 노트북으로 꺼낸 비밀번호의 유효 기간을 줄인다. 직접 접근을 막는 경계는 아니다(04 6장).

- **승인**: 운영 담당이 팀 작업 대장에 "교체 실행"을 적고 진행한다(실행자·시각)
- **방식**: RDS의 즉시 교체(`ModifyDBInstance`의 `RotateMasterUserPassword=true`, `ApplyImmediately=true`). Secrets Manager `rotate-secret`을 직접 부르지 않는다(AWS RDS 문서 "Rotating the master user password secret for a DB instance"는 RDS 경로만 안내한다)
- **자격 증명(교육 계정)**: 관리 역할은 `GetSecretValue`만 있어 교체할 수 없다. AWS 문서상 교체하는 사용자에게 `secretsmanager:RotateSecret`이 필요하고 호출 자체는 `rds:ModifyDBInstance`다. 교육 계정에서는 이 둘을 이미 가진 **공용 팀 사용자 프로필(`homestamp`)**로 이 명령 하나만 실행한다(2026-11-25 IAM 시뮬레이터로 허용 확인, 관리 역할 권한은 늘리지 않음). 개인 계정에서는 이 경로 대신 교체 전용 최소 권한 역할을 둔다
```sh
AWS_PROFILE=homestamp aws rds modify-db-instance --db-instance-identifier homestamp-db \
  --rotate-master-user-password --apply-immediately \
  --query 'DBInstance.[DBInstanceStatus,MasterUserSecret.SecretStatus]' --output text
```
- `--query`로 상태만 받는다(기본 출력에는 Secret ARN·계정 번호가 들어 있다). 이 명령에는 비밀번호가 없다

### 13. 교체 상태 확인
```sh
AWS_PROFILE=homestamp aws rds wait db-instance-available --db-instance-identifier homestamp-db
AWS_PROFILE=homestamp aws rds describe-db-instances --db-instance-identifier homestamp-db \
  --query 'DBInstances[0].[DBInstanceStatus,MasterUserSecret.SecretStatus]' --output text   # available  active 여야 한다
AWS_PROFILE=homestamp aws secretsmanager describe-secret --secret-id "$SECRET_ARN" \
  --query '{lastRotated:LastRotatedDate,versions:length(keys(VersionIdsToStages))}'          # 값이 아니라 메타데이터
```
- `SecretStatus`가 `rotating`이면 잠시 뒤 다시 본다. `LastRotatedDate`·`VersionIdsToStages`는 Secrets Manager API의 일반 항목이고 RDS 문서에는 없다(실환경 확인 NOT_RUN). 12단계 전에 같은 명령으로 값을 적어 두고 비교한다

### 14. 새 비밀번호로 재접속하고 남은 세션 끊기
- 3~6단계를 다시 한다(관리 역할 `homestamp-ops-admin`으로 새 값을 읽어 새 임시 `PGPASSFILE`에. 새 값도 화면·인자·EC2에 남기지 않는다)
- 접속되면 10단계의 두 쿼리를 다시 돌려, 교체 전부터 열려 있던 마스터 세션이 남아 있지 않은지 확인하고 있으면 끊는다
- 끝나면 11단계로 정리하고 위치 값도 지운다: `unset PGHOST PGHOSTADDR PGPORT PGDATABASE PGUSER PGSSLMODE PGSSLROOTCERT SECRET_ARN INSTANCE_ID`
- 이전 비밀번호로 접속이 거절되는지는 일부러 시험하지 않는다(이전 값은 이미 지웠다)

### 교체가 실패하면
- `SecretStatus`가 `impaired`: AWS 문서상 Secret으로 접속은 되지만 교체는 안 되는 상태다. 다시 교체를 누르지 말고 대장에 적고 팀에 알린 뒤 원인(권한·KMS·DB 상태)을 확인한다
- DB가 `available`로 돌아오지 않거나 새 값으로 접속이 안 됨: 반복 실행하지 않는다. 비밀번호를 손으로 정하지 않는다(관리형에서는 `MasterUserPassword`를 줄 수 없다). 앱·배포는 마스터 계정을 쓰지 않으므로 서비스에는 바로 영향이 없다. 원인을 확인한 뒤 12단계를 다시 할지 정한다
- 어떤 경우에도 정리(11단계)는 한다

### 15. 기록
- 대장: 실행자, 목적, 시작·종료 시각, 7·8·10·12·13·14 결과(성공·실패, 끊은 세션 수만)
- 감사 증거: CloudTrail 이벤트 기록에서 `AssumeRole`·`GetSecretValue`·`StartSession`·`TerminateSession`(관리 역할, `assumed-role/homestamp-ops-admin/<세션 이름>`)과 `ModifyDBInstance`(공용 팀 사용자 `user/devcos-team07`, 사람을 구분하지 못함)의 시각을 대장과 대조한다

### 자동 교체와 이번 교체의 차이
| | 자동 교체 | Bootstrap 뒤 즉시 교체 |
|---|---|---|
| 언제 | RDS가 기본 7일마다(AWS 문서) | Bootstrap·역할 변경으로 사람이 값을 꺼낸 직후 |
| 누가 | RDS | 운영 담당(교육 계정은 공용 팀 사용자 프로필) |
| 목적 | 정기적으로 바꿈 | 노트북에 꺼낸 값의 유효 기간을 줄임 |
| 열린 세션 | 끊지 않음 | 끊지 않음 → 10·14단계에서 직접 끊는다 |

## 로그인 역할 비밀번호 교체 (코드 배포와 따로)

언제: 노출이 의심될 때, 담당자가 바뀔 때, EC2·EBS를 잃었을 때(`hs_migrator`). 교체는 무중단이 아니다. 시작 전에 배포 승인을 멈추고, GitHub Actions에 진행 중이거나 승인 대기·승인된 `deploy.yml` 실행이 없는지, EC2 `--status`에 진행 중인 배포(`in-progress`)가 없는지 본 뒤 대장에 적는다. 교체와 확인이 끝날 때까지 `deploy.yml`도 손으로 하는 `deploy.sh`도 돌리지 않는다. 3~6단계로 마스터 세션을 열어 둔다.
- **무엇이 무엇을 막나**: DB 비밀번호를 바꾼 때부터 SSM·Migration 파일을 맞추고 확인할 때까지가 불일치 구간이다. 이 구간에 새 배포가 시작되지 않게 하는 것은 **배포 승인 중지**다(배포 잠금은 EC2에서 상태를 바꾸는 단계, 곧 재시작과 파일 교체만 잡는다). 그래도 이 구간에 배포가 돌면 App은 새 앱이 뜨지 못해(13·14) 이전 앱이 그대로이고, Migration은 11로 멈춘다
- 잠금을 쥔 셸을 오래 열어 두고 그 안에서 아래 `flock -n` 명령을 부르면 같은 잠금을 다시 잡지 못해 실패한다. 아래 명령은 각자 잠금을 잡았다 놓으므로 그대로 쓴다
- 매일 자동 중지·시작 시각 근처에는 하지 않는다. App 2와 4 사이에 앱이 다시 시작하면 새 SSM 값과 이전 DB 값이 어긋나 뜨지 않는다
- 배포는 확인이 끝난 뒤 다시 받는다: App은 5 통과, Migration은 파일 교체와 "Migration 비밀번호 확인" 통과. 교체가 실패하면 DB 값과 SSM·Migration 파일 값을 다시 맞추고(아래 실패 줄) 그 확인을 통과한 뒤에만 배포를 다시 받는다. 대장에 적고 푼다

**App(`hs_app`)**: 앱은 SSM을 **시작할 때만** 읽는다. 같은 digest 재배포는 noop이라 새 값을 읽지 않으므로 재시작으로 대신한다.
1. 새 값을 만든다(9b)
2. 콘솔에서 `/homestamp/prod/app/spring.datasource.password`를 새 값으로 바꾼다. 실행 중인 앱은 아직 이전 값으로 동작한다
3. 마스터 세션에서 `\password hs_app`에 같은 값을 넣는다. 이미 열린 연결은 유지되지만 앱 연결 풀의 새 연결은 이때부터 실패하므로 바로 4로 간다
4. EC2: 잠금을 쥔 뒤 활성 슬롯을 읽고 재시작한다(잠금을 못 잡으면 배포 중이니 나중에)
   ```sh
   sudo flock -n /run/lock/homestamp-deploy.lock bash -c 's=$(/opt/homestamp/deploy/deploy.sh --status | sed -n "s/^active: //p")
     case $s in blue|green) docker restart -t 30 "homestamp-app-$s" ;; *) echo "활성 슬롯을 모름: $s"; exit 1 ;; esac'
   ```
   시작하면서 새 값을 읽는다. 다시 뜰 때까지 수십 초 동안 API가 503이다
5. 확인: `--status`, 헬스 200·UP, `sudo docker logs --since 10m homestamp-app-<활성 슬롯> 2>&1 | grep -c 'password authentication failed'`가 0
- 새 값은 5가 끝날 때까지 지우지 않는다
- 3이 실패하면 4를 하지 않는다(앱은 이전 값으로 계속 돈다). 같은 값으로 3을 다시 한다
- 4 뒤에 앱이 뜨지 않고 인증 실패가 보이면 SSM과 DB 값이 다르다. 들고 있는 값으로 3을 다시 하고 4를 다시 한다. 값을 잃었으면 1부터 새 값으로 한다
- 멈춰 둔 이전 슬롯 앱과 다음 배포의 새 앱은 시작할 때 새 값을 읽는다

**Migration(`hs_migrator`)**: 새 값 → `\password hs_migrator` → 아래 "Migration 비밀번호 확인" → 배포-블루그린 "Migration 비밀 폴더 → 처음 만들기·교체" → 다음 새 digest 배포의 Migration 단계로 최종 확인. 그사이 배포는 11로 멈추고 서비스는 그대로다. 같은 digest 재배포(noop)는 확인이 아니다.

### Migration 비밀번호 확인 (노트북, 처음 공급·교체 때만)
`\password hs_migrator`로 정한 값이 DB에서 인증되는지 **읽기 전용**으로 바로 본다. 일반 자동 배포에는 필요 없다.
- 2·5단계의 관리 역할과 포트 포워딩, 3~4단계의 `PGHOST`·`PGHOSTADDR`·`PGPORT`·`PGDATABASE`·`PGSSLMODE=verify-full`·`PGSSLROOTCERT`를 그대로 쓴다. AI 도구·화면 녹화 밖의 터미널에서 한다
- 비밀번호는 프롬프트에만 붙여 넣는다. 명령 인자·환경변수·파일에 넣지 않는다. 마스터용 `PGPASSFILE`·`PGPASSWORD`는 쓰지 않게 끈다
```sh
env -u PGPASSWORD PGPASSFILE=/dev/null PGUSER=hs_migrator PGOPTIONS='-c default_transaction_read_only=on' \
  psql -X -W -At -P pager=off -v ON_ERROR_STOP=1 -c "SELECT current_user, ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
```
- `hs_migrator|t`가 나오면 인증·TLS 성공. `password authentication failed`면 `\password`를 다시 한다. 인증서 이름 오류면 `PGHOST`를 본다(`sslmode`를 낮추지 않는다)
- 쿼리는 읽기만 하고, `default_transaction_read_only`로 쓰기를 막는다. 이 세션에서 다른 쿼리를 치지 않는다
- 사람이 입력한 값이 DB와 맞는다는 확인이다. EC2의 Migration 파일 내용과 앱의 JDBC 접속은 다음 새 digest 배포의 Migration 단계에서 본다
- 2026-11-28 로컬 확인(가짜 CA·인증서, PostgreSQL 18, 가짜 비밀번호): 맞는 값 `hs_migrator|t`, 틀린 값 실패, 남은 `PGPASSFILE`·`PGPASSWORD` 안 씀, 쓰기 거절, 인증서 이름 다르면 접속 안 함, psql 기록·DB 로그에 값 없음

**마스터**: 12~14단계(RDS 관리형 교체)만 쓴다. Secrets Manager `rotate-secret`을 직접 부르지 않는다.

**앱 키**: 무엇을 바꿔도 되는지는 04 6장 "키를 잃거나 바꾸면"을 본다. 바꾼 뒤 적용은 App과 같다(값 바꿈 → 재시작).

## apply 뒤 1회 확인 (보안 경계로 쓰기 전)

AWS 문서는 "문서의 `parameters`에 없는 값은 StartSession에서 덮어쓸 수 없다"를 명시하지 않는다. 그래서 문서를 만든 뒤 한 번, 목적지를 바꾸는 요청이 **거절되는지** 확인하고 결과를 대장에 남긴다(RDS가 아닌 주소로 실제 연결이 열리면 문서를 쓰지 않고 팀에 알린다).
```sh
aws ssm start-session --target "$INSTANCE_ID" --document-name homestamp-rds-forward \
  --parameters 'host=localhost,portNumber=22,localPortNumber=15433'
```

## 감사와 한계

| 항목 | 남는 것 | 한계 |
|---|---|---|
| CloudTrail | `AssumeRole`·`GetSecretValue`·`StartSession`·`ModifyDBInstance` 같은 API 호출, 시각, 주체 | 세션 이름은 입력한 값이라 신원 증명이 아니다. 공용 팀 사용자 키로 직접 부른 호출은 사람을 구분하지 못한다. **추적(Trail)은 아직 없어** 이벤트 기록(90일)만 있다 |
| SSM 세션 로그 | 없음(포트 포워딩 세션은 Session Manager 로깅 대상이 아니다. 시작·종료는 CloudTrail에 남는다) | 오간 내용(SQL)은 S3·CloudWatch 어디에도 남지 않는다 |
| PostgreSQL | RDS `postgresql` 로그(CloudWatch, 365일, terse) | 실행한 SQL을 남기려면 `log_statement` 또는 pgAudit가 필요하다(아직 없음). 켜더라도 비밀번호가 들어가는 문장이 로그에 남지 않게 한다 |
| 교육 계정 | — | 공용 팀 사용자는 `AdministratorAccess`라 이 절차를 거치지 않고 Secret을 읽거나 세션 문서를 바꿀 수 있다. 팀 약속·대장·CloudTrail 대조로 관리한다(04 8장) |
