# 자동 배포 경로: GitHub Actions(deploy.yml, Environment production 승인) → OIDC → 이 역할 → 이 SSM 문서 → deploy.sh
# (04 9-2·16장, 런북 docs/runbooks/배포-자동화.md)
#
# 이 역할로 할 수 있는 것: 우리 앱 EC2 한 대에 homestamp-deploy 문서만 보내고 결과를 읽기.
# AWS-RunShellScript·AWS-RunDocument 같은 범용 문서는 허락하지 않고 명시적으로도 막는다(아무 명령이나 root로 돌리는 길).
# 문서는 입력(digest)을 명령에 끼워 넣지 않고, 해시를 고정한 deploy.sh만 실행한다. 다른 문서를 부르는 aws:runDocument 단계는 없다.
# 명령 취소(CancelCommand)는 주지 않는다: 자원 범위를 좁힐 수 없고(계정의 남의 명령도 취소 가능), 배포는 서버에서 끝까지 도는 편이 안전하다.

variable "deploy_script_sha256" {
  description = "EC2에 설치를 승인한 /opt/homestamp/deploy/deploy.sh의 SHA-256. 비어 있으면 문서가 실행을 거절한다(종료 20)"
  type        = string
  default     = ""
  validation {
    condition     = can(regex("^([0-9a-f]{64})?$", var.deploy_script_sha256))
    error_message = "deploy_script_sha256는 빈 값이거나 64자리 소문자 16진수여야 합니다."
  }
}

locals {
  deploy_environment = "production"
}

resource "aws_ssm_document" "deploy" {
  name            = "${var.prefix}-deploy"
  document_type   = "Command"
  document_format = "JSON"
  target_type     = "/AWS::EC2::Instance"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "homestamp blue/green deploy: run the pinned deploy.sh with one image digest"
    parameters = {
      Digest = {
        type              = "String"
        description       = "image digest (sha256:<64 hex>)"
        allowedPattern    = "^sha256:[0-9a-f]{64}$"
        interpolationType = "ENV_VAR"
      }
    }
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "deploy"
      precondition = {
        StringEquals = ["platformType", "Linux"]
      }
      inputs = {
        # deploy.sh 상한: --migrate 900 + --apply-grants 300 + 헬스 120 = 1320초에 이미지 받기·멈춤·전환·되돌리기를 더한 여유(#205)
        timeoutSeconds = "2400"
        runCommand     = split("\n", trimspace(templatefile("${path.module}/ssm/deploy-command.sh.tftpl", { expected_sha256 = var.deploy_script_sha256 })))
      }
    }]
  })
}

data "aws_iam_policy_document" "deploy_assume" {
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
    # Environment를 쓰는 job은 sub에 브랜치 대신 environment가 들어간다. main 제한은 Environment의 배포 브랜치 규칙이 한다
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.github_sub_prefix}:environment:${local.deploy_environment}"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.prefix}-deploy"
  description          = "GitHub Actions production environment: send homestamp-deploy to the web EC2 only"
  assume_role_policy   = data.aws_iam_policy_document.deploy_assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deploy" {
  # SendCommand는 문서와 대상 인스턴스 양쪽 자원으로 허락을 본다. 둘 다 하나씩만
  statement {
    sid       = "SendDeployDocumentToWebOnly"
    actions   = ["ssm:SendCommand"]
    resources = [aws_ssm_document.deploy.arn, aws_instance.web.arn]
  }

  # 결과 읽기. 이 두 동작은 자원 범위를 좁힐 수 없다(AWS 서비스 권한 표). 읽기만이고 명령을 바꾸지 못한다
  statement {
    sid       = "ReadCommandResult"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }

  # 나중에 넓은 권한이 붙어도 다른 문서(AWS-RunShellScript·AWS-RunDocument 등)나 다른 서버로는 보내지 못하게
  statement {
    sid           = "DenySendCommandElsewhere"
    effect        = "Deny"
    actions       = ["ssm:SendCommand"]
    not_resources = [aws_ssm_document.deploy.arn, aws_instance.web.arn]
  }

  # 세션(셸·포트 포워딩)과 문서 고치기는 배포 역할의 일이 아니다
  statement {
    sid    = "DenySessionsAndDocumentChanges"
    effect = "Deny"
    actions = [
      "ssm:StartSession",
      "ssm:ResumeSession",
      "ssm:CreateDocument",
      "ssm:UpdateDocument",
      "ssm:UpdateDocumentDefaultVersion",
      "ssm:DeleteDocument",
      "ssm:CancelCommand",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "${var.prefix}-deploy-policy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}

output "deploy_role_name" {
  description = "deploy.yml이 맡을 역할 이름(ARN은 계정 번호가 들어가서 출력하지 않는다)"
  value       = aws_iam_role.deploy.name
}

output "deploy_document_name" {
  description = "배포 전용 SSM 문서 이름"
  value       = aws_ssm_document.deploy.name
}
