# S3 (04 5-1·8장·12-1, 13-1의 6번)
#
# 사진 버킷 homestamp-photos (Terraform이 만든다)
#   pending/{호실}/…  브라우저가 presigned POST로 올린 임시 파일. 1일 뒤 저절로 지워진다
#   photos/{호실}/…   제출 때 검사를 통과해 복사된 정식 사진
#   - 버전 관리를 켜지 않는다: 개인정보 사진을 지우면 이전 버전 없이 정말로 지워져야 한다(04 8장)
#   - 공개 접근 전부 차단, 사진은 presigned URL로만 본다
#
# 운영 버킷 homestamp-ops (손으로 만들었다, Terraform은 정책과 수명 주기만 맡는다)
#   버킷 정책과 수명 주기는 버킷마다 하나라서, 앞으로 CloudTrail·삭제 기록 보호 규칙도 여기에 더한다

# ---------- 사진 버킷 ----------
resource "aws_s3_bucket" "photos" {
  bucket = local.photos_bucket

  tags = {
    Name = local.photos_bucket
  }
}

resource "aws_s3_bucket_public_access_block" "photos" {
  bucket                  = aws_s3_bucket.photos.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 객체 주인은 항상 버킷 주인(ACL을 쓰지 않는다)
resource "aws_s3_bucket_ownership_controls" "photos" {
  bucket = aws_s3_bucket.photos.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "photos" {
  bucket = aws_s3_bucket.photos.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 일부러 끈 상태로 적어 둔다(기본값과 같지만, 누가 켜려고 하면 이 줄이 이유를 보여 준다)
resource "aws_s3_bucket_versioning" "photos" {
  bucket = aws_s3_bucket.photos.id
  versioning_configuration {
    status = "Disabled"
  }
}

# 브라우저가 우리 도메인에서만 바로 올리고(POST) 받아 갈(GET, 내려받기 ZIP) 수 있게
# 로컬 개발 주소(localhost)는 운영 버킷에 넣지 않는다
resource "aws_s3_bucket_cors_configuration" "photos" {
  bucket = aws_s3_bucket.photos.id
  cors_rule {
    allowed_origins = ["https://homestamp.cloud"]
    allowed_methods = ["POST", "GET", "HEAD"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3600
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "photos" {
  bucket = aws_s3_bucket.photos.id

  # 등록하지 않은 임시 사진은 하루 뒤 지운다(06 pending.cleanup-after 24h, 03 불변식 17)
  rule {
    id     = "pending-expire-1d"
    status = "Enabled"
    filter {
      prefix = "pending/"
    }
    expiration {
      days = 1
    }
  }

  # 끝나지 않은 업로드 조각이 남아 요금이 나가지 않게
  rule {
    id     = "abort-incomplete-upload"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# 누가 언제 어떤 사진을 받아 갔는지 운영 버킷에 남긴다(30일, 06 log.access.retention)
resource "aws_s3_bucket_logging" "photos" {
  bucket        = aws_s3_bucket.photos.id
  target_bucket = local.ops_bucket
  target_prefix = "logs/s3-access/"
}

# 암호화되지 않은 연결(http)은 거절
data "aws_iam_policy_document" "photos" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.photos.arn,
      "${aws_s3_bucket.photos.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "photos" {
  bucket = aws_s3_bucket.photos.id
  policy = data.aws_iam_policy_document.photos.json

  depends_on = [aws_s3_bucket_public_access_block.photos]
}

# ---------- 운영 버킷 (정책·수명 주기만) ----------
data "aws_iam_policy_document" "ops" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      "arn:aws:s3:::${local.ops_bucket}",
      "arn:aws:s3:::${local.ops_bucket}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # S3 서버 접근 로그를 쓰는 AWS 서비스에 logs/s3-access/만 허락(사진 버킷에서 오는 것만)
  statement {
    sid       = "S3ServerAccessLogsFromPhotos"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["arn:aws:s3:::${local.ops_bucket}/logs/s3-access/*"]
    principals {
      type        = "Service"
      identifiers = ["logging.s3.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = [aws_s3_bucket.photos.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_s3_bucket_policy" "ops" {
  bucket = local.ops_bucket
  policy = data.aws_iam_policy_document.ops.json
}

# 운영 버킷은 버전 관리를 켰으므로, 지운 로그의 이전 버전도 함께 정리해야 실제로 사라진다
resource "aws_s3_bucket_lifecycle_configuration" "ops" {
  bucket = local.ops_bucket

  # SSM 세션 기록(관리 역할 셸): 접속기록 1년(decisions 2026-11-11, 04 8장)
  rule {
    id     = "ssm-session-logs-365d"
    status = "Enabled"
    filter {
      prefix = "logs/ssm/"
    }
    expiration {
      days = 365
    }
    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  rule {
    id     = "s3-access-logs-30d"
    status = "Enabled"
    filter {
      prefix = "logs/s3-access/"
    }
    expiration {
      days = 30
    }
    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }
}
