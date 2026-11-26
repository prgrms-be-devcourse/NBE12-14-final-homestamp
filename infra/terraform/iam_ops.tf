# 운영 역할 두 개: 읽기(팀원 3명)와 관리(운영 담당 1명) (04 8장, decisions 2026-11-15)
#
# 과정은 팀 IAM 사용자 하나(devcos-team07)의 같은 액세스 키를 팀원 모두에게 줬다. 그래서 AWS는 사람을 구분하지 못한다.
# 역할을 맡을 때 세션 이름에 자기 GitHub 아이디를 적게 하고, 목록에 있는 이름만 받는다.
# CloudTrail에는 "역할 / 입력한 세션 이름"이 남지만, 공용 키이므로 실제 접속자 신원은 확인할 수 없다.
# 받아들인 한계: 키가 같아서 남의 이름을 적는 것까지는 막지 못한다(04 8장).
#
# 왜 둘로 나누나: SSM "셸"로 서버에 들어가면 관리자(sudo)가 되어 앱 역할의 자격 증명과 Parameter Store 비밀값
# (테이블 소유자 비밀번호 포함)을 꺼낼 수 있고, 그러면 고칠 수 없게 막은 기록도 고칠 수 있다(원칙 1, #41의 한계).
# 팀은 셸을 운영 담당 1명만 사용하기로 약속하고, 다른 팀원은 포트 포워딩으로 접근한다. 이 약속은 IAM이 강제하지 못한다.
#
#   읽기(ops-read)  : 우리 EC2 자기 자신의 포트로만 포워딩(Actuator 등). 셸 없음.
#                     RDS(사람별 읽기 전용 DB 계정)·Grafana로 가는 포워딩은 목적지를 고정한 전용 세션 문서를 만들 때 더한다(#41, 모니터링).
#                     AWS-StartPortForwardingSessionToRemoteHost는 넣지 않는다: 목적지를 아무 주소나 줄 수 있어
#                     서버 메타데이터(169.254.169.254)로 가면 셸 없이도 자격 증명을 꺼낼 수 있다.
#   관리(ops-admin) : 셸 + 포트 포워딩. 장애 대응, 운영 명령(OPS-3·4), 첫 설정. RDS 마스터 Secret 하나 읽기(Bootstrap)
#                     RDS로 가는 포워딩은 목적지를 박은 전용 문서(ssm_rds.tf)로만

locals {
  team_iam_user = "devcos-team07"

  # 세션 이름 목록(GitHub 아이디). 빼거나 더할 때는 이 목록만 고치고 권한 대장에 부여·말소를 남긴다
  ops_read_names  = ["cheeerry0", "yoo-sihun", "jinni1"]
  ops_admin_names = ["dewyflowers"]

  ops_instance_arn = "arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:instance/*"
  ops_session_arn  = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:session/*"
  ops_port_forward = "arn:aws:ssm:${var.region}::document/AWS-StartPortForwardingSession"
  ops_shell_doc    = aws_ssm_document.ops_shell.arn
}

# 관리 역할의 셸은 이 문서로만 연다: 친 명령과 출력을 운영 버킷 logs/ssm/에 남긴다(04 8장 접속기록 1년).
# 계정 공용 설정(SSM-SessionManagerRunShell)은 다른 팀도 쓰므로 건드리지 않고, 우리 전용 세션 문서를 둔다.
# 기본 셸 문서는 허락하지 않아서, 문서를 빼먹고 열면 거절된다(기록 없는 셸이 열리지 않게)
resource "aws_ssm_document" "ops_shell" {
  name            = "${var.prefix}-shell"
  document_type   = "Session"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "1.0"
    description   = "homestamp ops shell with session log to S3"
    sessionType   = "Standard_Stream"
    inputs = {
      s3BucketName                = local.ops_bucket
      s3KeyPrefix                 = "logs/ssm/"
      s3EncryptionEnabled         = true
      cloudWatchLogGroupName      = ""
      cloudWatchEncryptionEnabled = true
      idleSessionTimeout          = "20"
      runAsEnabled                = false
      runAsDefaultUser            = ""
      shellProfile = {
        linux   = ""
        windows = ""
      }
    }
  })
}

# 같은 신뢰 정책 틀: 팀 IAM 사용자가, 목록에 있는 세션 이름으로만
data "aws_iam_policy_document" "ops_assume" {
  for_each = {
    read  = local.ops_read_names
    admin = local.ops_admin_names
  }

  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:user/${local.team_iam_user}"]
    }
    condition {
      test     = "StringEquals"
      variable = "sts:RoleSessionName"
      values   = each.value
    }
  }
}

# 두 역할이 함께 쓰는 것: 서버 찾기, 세션 상태 보기·끝내기
data "aws_iam_policy_document" "ops_common" {
  statement {
    sid = "Describe"
    actions = [
      "ec2:DescribeInstances",
      "ssm:DescribeInstanceInformation",
      "ssm:DescribeSessions",
      "ssm:GetConnectionStatus",
    ]
    resources = ["*"]
  }

  # 자기가 연 세션만 끝내거나 다시 잇는다. 과정 계정은 여러 팀이 같이 써서, 조건이 없으면 다른 팀 세션까지 끊을 수 있다.
  # Session Manager가 세션마다 붙이는 태그(aws:ssmmessages:session-id = 시작한 사람의 aws:userid)로 거른다(AWS 문서 권장 방식)
  statement {
    sid       = "EndOwnSessionsOnly"
    actions   = ["ssm:TerminateSession", "ssm:ResumeSession"]
    resources = [local.ops_session_arn]
    condition {
      test     = "StringLike"
      variable = "ssm:resourceTag/aws:ssmmessages:session-id"
      values   = ["$${aws:userid}"]
    }
  }
}

# ---------- 읽기: 팀원 3명, 포트 포워딩만 ----------
resource "aws_iam_role" "ops_read" {
  name                 = "${var.prefix}-ops-read"
  description          = "Team read access: port forwarding to our EC2 only, no shell. Session name must be a GitHub id"
  assume_role_policy   = data.aws_iam_policy_document.ops_assume["read"].json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "ops_read" {
  source_policy_documents = [data.aws_iam_policy_document.ops_common.json]

  # 우리 서버(Project=homestamp 태그)에만, 포트 포워딩 문서로만 세션을 연다
  statement {
    sid       = "PortForwardOurInstances"
    actions   = ["ssm:StartSession"]
    resources = [local.ops_instance_arn]
    condition {
      test     = "StringEquals"
      variable = "ssm:resourceTag/Project"
      values   = [var.prefix]
    }
  }
  statement {
    sid       = "PortForwardDocumentOnly"
    actions   = ["ssm:StartSession"]
    resources = [local.ops_port_forward]
  }
}

resource "aws_iam_role_policy" "ops_read" {
  name   = "${var.prefix}-ops-read-policy"
  role   = aws_iam_role.ops_read.id
  policy = data.aws_iam_policy_document.ops_read.json
}

# ---------- 관리: 운영 담당 1명, 셸 + 포트 포워딩 ----------
resource "aws_iam_role" "ops_admin" {
  name                 = "${var.prefix}-ops-admin"
  description          = "Operations lead: shell and port forwarding on our EC2 for incidents and ops commands. Session name must be a GitHub id"
  assume_role_policy   = data.aws_iam_policy_document.ops_assume["admin"].json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "ops_admin" {
  source_policy_documents = [data.aws_iam_policy_document.ops_common.json]

  statement {
    sid       = "SessionOurInstances"
    actions   = ["ssm:StartSession"]
    resources = [local.ops_instance_arn]
    condition {
      test     = "StringEquals"
      variable = "ssm:resourceTag/Project"
      values   = [var.prefix]
    }
  }
  statement {
    sid       = "ShellAndPortForwardDocuments"
    actions   = ["ssm:StartSession"]
    resources = [local.ops_shell_doc, local.ops_port_forward, aws_ssm_document.rds_forward.arn] # RDS 5432 전용(ssm_rds.tf)
  }

  # RDS 마스터 Secret 하나만 값 읽기(Role Bootstrap·Human 계정 관리용, decisions 2026-11-25).
  # ARN은 RDS가 만든 Secret을 참조하므로 RDS가 생긴 뒤에 정해진다(ListSecrets·와일드카드 없음).
  # Secret은 AWS 관리형 키(aws/secretsmanager)로 암호화되어 kms:Decrypt를 따로 주지 않는다
  # (rds.tf에 master_user_secret_kms_key_id를 넣어 고객 관리형 키로 바꾸면 이 문장을 다시 검토한다).
  # 한계: 과정의 공용 팀 사용자는 AdministratorAccess라 이 역할을 거치지 않고도 값을 읽을 수 있다.
  # 이 문장은 "정해 둔 경로"이고 IAM이 다른 팀원을 막지는 못한다(셸 약속과 같은 한계, 04 8장)
  statement {
    sid       = "ReadRdsMasterSecretOnly"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_db_instance.main.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_role_policy" "ops_admin" {
  name   = "${var.prefix}-ops-admin-policy"
  role   = aws_iam_role.ops_admin.id
  policy = data.aws_iam_policy_document.ops_admin.json
}

output "ops_role_names" {
  description = "운영 역할 이름(읽기, 관리). ~/.aws/config의 role_arn에 계정 번호와 함께 쓴다"
  value       = { read = aws_iam_role.ops_read.name, admin = aws_iam_role.ops_admin.name }
}
