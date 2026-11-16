# GitHub Actions → AWS, 액세스 키 없이 (04 6장·16장, 과정 확인 2026-11-14)
#
# GitHub Actions가 실행될 때마다 GitHub가 "이 실행은 우리 저장소의 PR이다"라는 짧은 신분증(OIDC 토큰)을 준다.
# AWS는 그 신분증을 확인하고 정해 둔 역할의 임시 자격 증명(1시간)을 내준다. 저장소·GitHub Secrets에 키가 없다.
#
# 이 파일: OIDC 공급자 + plan 역할(읽기 전용, PR에서만). apply 역할은 따로 만든다(#42).

# 이 저장소는 GitHub "immutable subject"가 켜져 있어 sub에 조직·저장소 고유 번호가 붙는다.
# (gh api repos/prgrms-be-devcourse/NBE12-14-final-homestamp/actions/oidc/customization/sub 의 sub_claim_prefix)
# 저장소 이름을 바꾸거나 같은 이름으로 새 저장소를 만들어도 이 역할을 맡지 못한다
locals {
  github_sub_prefix = "repo:prgrms-be-devcourse@88020948/NBE12-14-final-homestamp@1453694560"
  tf_state_key      = "tfstate/homestamp.tfstate" # backend.tf의 key와 같아야 한다(backend 블록은 변수를 못 써서 여기에 한 번 더 적음)
}

# 계정에 하나뿐인 공용 자원. 다른 팀도 쓸 수 있어 과정 담당자 확인 없이 지우지 않는다(04 13-1)
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  tags = {
    Name = "github-actions-oidc"
    Note = "shared per account - do not delete without course staff"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# ---------- plan 역할: PR에서 terraform plan만 ----------
data "aws_iam_policy_document" "tf_plan_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # 우리 저장소의 pull_request 실행만. main·다른 브랜치 push, 다른 저장소는 거절
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.github_sub_prefix}:pull_request"]
    }
  }
}

resource "aws_iam_role" "tf_plan" {
  name                 = "${var.prefix}-tf-plan"
  description          = "GitHub Actions PR: terraform plan only, read-only"
  assume_role_policy   = data.aws_iam_policy_document.tf_plan_assume.json
  max_session_duration = 3600
}

# 자원 설정을 읽는 AWS 관리형 읽기 전용 정책
resource "aws_iam_role_policy_attachment" "tf_plan_readonly" {
  role       = aws_iam_role.tf_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

data "aws_iam_policy_document" "tf_plan" {
  # 상태 파일(장부)은 읽기만. plan 역할로 장부를 덮어쓰거나 지울 수 없게(PR #43 리뷰)
  statement {
    sid       = "StateReadOnly"
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${local.ops_bucket}/${local.tf_state_key}"]
  }

  # 잠금 파일(use_lockfile이 만드는 <장부>.tflock)만 만들고 지운다
  statement {
    sid       = "LockFileOnly"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["arn:aws:s3:::${local.ops_bucket}/${local.tf_state_key}.tflock"]
  }

  # ReadOnlyAccess는 계정의 모든 버킷 파일을 읽을 수 있다(다른 팀 버킷, 우리 사진, 감사 기록 포함).
  # 상태 파일 말고는 파일 내용을 읽지 못하게 막는다. 버킷 설정(GetBucket*)은 plan에 필요해 막지 않는다
  statement {
    sid           = "DenyObjectReadExceptState"
    effect        = "Deny"
    actions       = ["s3:GetObject", "s3:GetObjectVersion"]
    not_resources = ["arn:aws:s3:::${local.ops_bucket}/tfstate/*"]
  }

  # 비밀값은 plan에 필요 없다. 복호화와 비밀 관리 서비스 값 읽기는 전부 막는다
  statement {
    sid       = "DenyDecryptAndSecrets"
    effect    = "Deny"
    actions   = ["kms:Decrypt", "secretsmanager:GetSecretValue"]
    resources = ["*"]
  }

  # 우리 Parameter Store 경로(/homestamp/…)는 읽지 못하게.
  # Amazon Linux 최신 이미지 번호(/aws/service/…, AWS 공개 파라미터)는 우리 경로가 아니라 plan이 읽을 수 있다
  statement {
    sid    = "DenyOwnParameters"
    effect = "Deny"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
      "ssm:GetParameterHistory",
    ]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.prefix}/*"]
  }
}

resource "aws_iam_role_policy" "tf_plan" {
  name   = "${var.prefix}-tf-plan-policy"
  role   = aws_iam_role.tf_plan.id
  policy = data.aws_iam_policy_document.tf_plan.json
}

output "tf_plan_role_name" {
  description = "GitHub Actions에서 맡을 역할 이름(ARN은 계정 번호가 들어가서 출력하지 않는다)"
  value       = aws_iam_role.tf_plan.name
}
