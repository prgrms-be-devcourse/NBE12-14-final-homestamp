variable "prefix" {
  description = "모든 자원 이름 앞에 붙는 말 (예: homestamp-web)"
  type        = string
  default     = "homestamp"
}

variable "region" {
  description = "AWS 리전 (서울)"
  type        = string
  default     = "ap-northeast-2"
}

# 과정 규칙("계정 정보 노출 금지")에 따라 기본값을 두지 않는다.
# 노트북은 gitignore된 terraform.tfvars, GitHub Actions는 TF_VAR_aws_account_id로 넣는다
variable "aws_account_id" {
  description = "과정 AWS 계정 번호 (12자리). 저장소에 적지 않는다"
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id는 숫자 12자리여야 합니다."
  }
}

# ---------- RDS (rds.tf) ----------
# 계정·팀마다 다를 수 있는 값만 변수로 둔다. 이름은 prefix에서 만든다

# 기본값은 과정 계정의 db.t4g.micro(04 3장 "위치", 과정 기본 허용 RDS small까지. 서울 리전 PostgreSQL 18 주문 가능,
# 2026-11-25 확인). 개인 계정은 비용·리전에 맞춰 tfvars로 바꾸고, 주문 가능 여부를 먼저 확인한다(terraform.tfvars.example)
variable "db_instance_class" {
  description = "RDS 인스턴스 클래스 (과정 계정: db.t4g.micro). PostgreSQL 18 주문 가능 여부를 먼저 확인한다"
  type        = string
  default     = "db.t4g.micro"

  validation {
    condition     = can(regex("^db\\.[a-z0-9]+\\.[a-z0-9]+$", var.db_instance_class))
    error_message = "db_instance_class는 db.<계열>.<크기> 형식이어야 합니다."
  }
}

variable "db_name" {
  description = "처음 만들 데이터베이스 이름 (Role Bootstrap의 전용 DB)"
  type        = string
  default     = "homestamp"

  validation {
    condition     = can(regex("^[a-z][a-z0-9_]{0,62}$", var.db_name))
    error_message = "db_name은 소문자로 시작하는 PostgreSQL 식별자여야 합니다."
  }
}

# 마스터 계정 이름(비밀 아님). Bootstrap 관리자만 쓰며 App·Purge·Human·Migration 역할 이름과 달라야 한다
variable "db_master_username" {
  description = "RDS 마스터 계정 이름. 비밀번호는 RDS가 Secrets Manager에서 관리한다"
  type        = string
  default     = "homestamp_master"

  validation {
    condition     = can(regex("^[a-z][a-z0-9_]{0,62}$", var.db_master_username)) && !startswith(var.db_master_username, "pg_") && !startswith(var.db_master_username, "rds")
    error_message = "db_master_username은 소문자 식별자여야 하고 pg_·rds로 시작할 수 없습니다."
  }
}

variable "db_backup_retention_days" {
  description = "자동 백업 보존 일수 (04 7장 \"백업·이관\": 5일 검토)"
  type        = number
  default     = 5

  validation {
    condition     = var.db_backup_retention_days >= 1 && var.db_backup_retention_days <= 35
    error_message = "백업 보존은 1~35일이어야 합니다(0은 자동 백업·시점 복구를 끈다)."
  }
}

variable "db_deletion_protection" {
  description = "RDS 삭제 보호(AWS 쪽). 지우려면 이 값을 false로 apply하는 것만으로는 안 되고, rds.tf의 prevent_destroy(Terraform 쪽)도 PR로 빼야 한다. 최종 스냅숏은 남는다"
  type        = bool
  default     = true
}

variable "db_log_retention_days" {
  description = "RDS 로그(CloudWatch Logs) 보관 일수 (04 13-1의 7번: 365일)"
  type        = number
  default     = 365
}
