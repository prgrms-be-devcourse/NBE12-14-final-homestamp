# Terraform

과정 AWS 계정의 자원을 만든다. 설계와 순서는 `docs/rules/04-인프라-아키텍처.md` 13-1을 따른다.

## 처음 한 번

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # 과정 계정 번호를 채운다 (저장소에 올라가지 않음)
AWS_PROFILE=homestamp terraform init
```

## 처음부터 세우기 (새 계정)

장부를 둘 운영 버킷이 없으면 `terraform init`부터 막힌다. 그래서 둘만 손으로 먼저 만든다.

1. 운영 버킷 `homestamp-ops`: 서울 리전에 만들고 공개 차단, 버전 관리 켬, AES256 암호화, 태그 `Team`·`Name`
2. 탄력적 IP: `aws ec2 allocate-address --domain vpc` (태그 `Name=homestamp-eip`), 도메인 A 레코드를 이 IP로
3. `imported.tf`의 탄력적 IP 할당 ID를 새 값으로 바꾼다
4. `terraform init` → `plan`(가져오기 + 나머지 만들기 확인) → `apply`

## 역할·정책을 만들거나 바꿀 때 묻는 여섯 가지

과정 계정은 여러 팀이 같이 쓰고, 팀원은 같은 액세스 키를 가진다. 그래서 권한 실수가 우리 서비스와 다른 팀 모두에 닿는다.

1. **누가 맡나**: 신뢰 정책의 주체와 조건(저장소·브랜치·세션 이름)이 그 일을 할 사람·실행만 받나
2. **무엇을 하나**: 그 일에 꼭 필요한 동작만 있나(읽기만 할 역할에 쓰기·지우기가 섞이지 않았나)
3. **어디에 하나**: 자원이 우리 것(이름·경로·`Project` 태그)으로 좁혀졌나. `*`라면 다른 팀 자원에도 닿는지
4. **넘어갈 길이 있나**: 이 권한으로 더 큰 권한을 얻을 수 있나(셸 → 서버 자격 증명·비밀값, 아무 주소로 포트 포워딩 → 메타데이터, 상태 파일 쓰기 → 장부 조작)
5. **남나**: CloudTrail에 역할과 입력한 세션 이름이 남나. 공용 키에서는 실제 사람을 확인할 수 없으므로 작업 대장과 대조한다. 관리 셸의 SSM 기록과 달리 포트 포워딩의 내용은 SSM에 남지 않는다.
6. **실패하면 막히나**: 조건이 틀리면 "아무것도 못 함"으로 끝나나, "다 됨"으로 끝나나(원칙 8)

## 바꿀 때

```bash
AWS_PROFILE=homestamp terraform plan    # 무엇이 바뀌는지 먼저 본다
AWS_PROFILE=homestamp terraform apply   # plan을 확인한 뒤에만
```

- 명령마다 `AWS_PROFILE=homestamp`를 붙인다. 빼면 개인 계정으로 가려 하고, `allowed_account_ids`가 막는다
- 손으로 먼저 만들고 `imported.tf`로 가져온 것: 운영 버킷 `homestamp-ops`(장부를 두는 곳), 탄력적 IP `homestamp-eip`(도메인이 가리키는 주소). 둘 다 `prevent_destroy`라 Terraform이 지우지 못한다
- 비밀값(DB 비밀번호 등)은 `.tf`와 상태 파일에 두지 않는다(04 13-1)
- OIDC를 세운 뒤에는 `infra/**` PR에서 GitHub Actions가 `plan`을 돌리고, 노트북 실행은 첫 구성 때만 한다
