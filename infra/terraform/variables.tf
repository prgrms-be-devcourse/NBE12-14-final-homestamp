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
