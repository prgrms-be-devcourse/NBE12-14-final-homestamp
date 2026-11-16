# 앱 EC2의 역할 (04 13-1의 5번, 6장, 12-1)
#
# EC2 안의 앱은 액세스 키 없이 이 역할의 임시 자격 증명으로 AWS를 쓴다(인스턴스 프로파일).
# 할 수 있는 일만 하나씩 적는다. 적지 않은 것은 전부 거절된다.
#
#   사진 버킷  pending/  올리기 받기(presigned POST), 검사, 복사 원본, 지우기, 목록
#              photos/   복사 대상, 보기(presigned GET), 지우기(DB에 있는 키로만). 목록은 못 함
#   운영 버킷  audit/deletions/  삭제 기록 쓰기만(읽기·지우기 없음)
#              logs/ssm/         SSM 세션 기록 쓰기만(SSM 에이전트가 씀)
#   Parameter Store  /homestamp/prod/{app,migration,purge}/  읽기만
#   SSM        세션·명령 받기(SSH 대신)
#
# 받아들인 한계(#41): 앱·마이그레이션·삭제 작업 컨테이너가 이 역할 하나를 함께 쓰므로
# 서버를 장악하면 세 경로를 모두 읽을 수 있다. 모니터링 경로(/homestamp/prod/monitoring/)는 넣지 않는다

locals {
  photos_bucket = "${var.prefix}-photos"
  ops_bucket    = "${var.prefix}-ops" # 손으로 만든 운영 버킷(Terraform 밖)
  param_prefix  = "/${var.prefix}/prod"
}

data "aws_caller_identity" "current" {}

# EC2 서비스만 이 역할을 맡을 수 있다
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "web" {
  name               = "${var.prefix}-web-role"
  description        = "app EC2: photos bucket paths, ops audit path, own parameters, SSM"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

data "aws_iam_policy_document" "web" {
  # 사진: 파일 하나씩 다루기
  statement {
    sid = "PhotosObjects"
    actions = [
      "s3:PutObject",    # pending/ 올리기(presigned POST 서명), photos/로 복사
      "s3:GetObject",    # 검사(HeadObject), 복사 원본, presigned GET 서명
      "s3:DeleteObject", # DB에 있는 키로만 지움(04 12-1)
    ]
    resources = [
      "arn:aws:s3:::${local.photos_bucket}/pending/*",
      "arn:aws:s3:::${local.photos_bucket}/photos/*",
    ]
  }

  # 사진: 목록은 pending/만 (photos/를 훑어 지우지 못하게, 04 12-1)
  statement {
    sid       = "PhotosListPendingOnly"
    actions   = ["s3:ListBucket"]
    resources = ["arn:aws:s3:::${local.photos_bucket}"]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["pending/*"]
    }
  }

  # 운영 버킷: 삭제 기록과 SSM 세션 기록은 쓰기만
  statement {
    sid     = "OpsWriteOnly"
    actions = ["s3:PutObject"]
    resources = [
      "arn:aws:s3:::${local.ops_bucket}/audit/deletions/*",
      "arn:aws:s3:::${local.ops_bucket}/logs/ssm/*",
    ]
  }

  # SSM 세션 기록을 S3에 쓰기 전에 에이전트가 버킷 암호화 설정을 확인한다
  statement {
    sid       = "OpsEncryptionCheckForSsmLogs"
    actions   = ["s3:GetEncryptionConfiguration"]
    resources = ["arn:aws:s3:::${local.ops_bucket}"]
  }

  # 비밀값: 우리 경로만 읽기 (04 6장)
  statement {
    sid = "ReadOwnParameters"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    # 경로 아래 값(…/app/*)과 경로 자체(…/app) 둘 다 필요하다.
    # GetParametersByPath는 권한을 경로 자체의 ARN으로 확인한다
    resources = flatten([
      for p in ["app", "migration", "purge"] : [
        "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.param_prefix}/${p}",
        "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.param_prefix}/${p}/*",
      ]
    ])
  }
}

resource "aws_iam_role_policy" "web" {
  name   = "${var.prefix}-web-policy"
  role   = aws_iam_role.web.id
  policy = data.aws_iam_policy_document.web.json
}

# SSM 세션·명령을 받는 데 필요한 AWS 관리형 정책 (SSH 대신)
resource "aws_iam_role_policy_attachment" "web_ssm_core" {
  role       = aws_iam_role.web.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# EC2에 역할을 붙이는 통
resource "aws_iam_instance_profile" "web" {
  name = "${var.prefix}-web-profile"
  role = aws_iam_role.web.name
}
