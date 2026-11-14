# Terraform

과정 AWS 계정의 자원을 만든다. 설계와 순서는 `docs/rules/04-인프라-아키텍처.md` 13-1을 따른다.

## 처음 한 번

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # 과정 계정 번호를 채운다 (저장소에 올라가지 않음)
AWS_PROFILE=homestamp terraform init
```

## 바꿀 때

```bash
AWS_PROFILE=homestamp terraform plan    # 무엇이 바뀌는지 먼저 본다
AWS_PROFILE=homestamp terraform apply   # plan을 확인한 뒤에만
```

- 명령마다 `AWS_PROFILE=homestamp`를 붙인다. 빼면 개인 계정으로 가려 하고, `allowed_account_ids`가 막는다
- 손으로 먼저 만든 것(Terraform이 관리하지 않음): 운영 버킷 `homestamp-ops`, 탄력적 IP `homestamp-eip`
- 비밀값(DB 비밀번호 등)은 `.tf`와 상태 파일에 두지 않는다(04 13-1)
- OIDC를 세운 뒤에는 `infra/**` PR에서 GitHub Actions가 `plan`을 돌리고, 노트북 실행은 첫 구성 때만 한다
