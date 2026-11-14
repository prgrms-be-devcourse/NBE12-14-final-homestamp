# Terraform과 provider 버전 고정 (04 13-1·17장)
# - 1.10 이상이어야 S3 자체 잠금(use_lockfile)을 쓸 수 있다. 노트북과 GitHub Actions를 같은 판으로 맞춘다
# - provider는 .terraform.lock.hcl을 커밋해 누가 돌려도 같은 판을 받는다
terraform {
  required_version = "~> 1.17.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.71.0"
    }
  }
}
