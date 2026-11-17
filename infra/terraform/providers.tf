# AWS 접속 설정
# - 자격 증명은 여기에 쓰지 않는다. 노트북은 AWS_PROFILE=homestamp, GitHub Actions는 OIDC 역할
# - allowed_account_ids: 다른 계정(개인 계정 등)으로 접속하면 아무것도 만들지 않고 멈춘다
# - default_tags: 모든 자원에 과정 규칙의 Team 태그가 자동으로 붙는다
provider "aws" {
  region              = var.region
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Team      = "devcos-team07"
      Project   = var.prefix
      ManagedBy = "terraform"
    }
  }
}
