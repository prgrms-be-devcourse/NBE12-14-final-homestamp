# 손으로 먼저 만든 자원을 Terraform이 맡는다 (04 13-1 0단계)
#
# 왜 손으로 먼저 만들었나
# - 운영 버킷: Terraform 장부(상태 파일)를 두는 곳이라 terraform init 전에 있어야 한다(닭과 달걀)
# - 탄력적 IP: 도메인이 가리키는 고정 주소. DNS를 EC2보다 먼저 연결하려고 미리 받았다
#
# 왜 이제 코드로 가져오나: 설정(버전 관리 켬, 공개 차단, 암호화, 태그)을 코드로 남기고,
# 누가 콘솔에서 바꾸면 다음 plan에서 알아채려고.
#
# prevent_destroy: 코드에서 지우거나 terraform destroy를 해도 Terraform이 멈춘다.
# 운영 버킷을 지우면 장부와 감사 기록이, 탄력적 IP를 지우면 도메인이 사라진다.
#
# 새 계정에서 처음부터 세울 때는 README "처음부터 세우기" 순서를 따른다(import로는 닭과 달걀이 풀리지 않는다)

# ---------- 운영 버킷 homestamp-ops ----------
import {
  to = aws_s3_bucket.ops
  id = "homestamp-ops"
}

import {
  to = aws_s3_bucket_versioning.ops
  id = "homestamp-ops"
}

import {
  to = aws_s3_bucket_server_side_encryption_configuration.ops
  id = "homestamp-ops"
}

import {
  to = aws_s3_bucket_public_access_block.ops
  id = "homestamp-ops"
}

import {
  to = aws_s3_bucket_ownership_controls.ops
  id = "homestamp-ops"
}

resource "aws_s3_bucket" "ops" {
  bucket = local.ops_bucket

  tags = {
    Name = local.ops_bucket
  }

  lifecycle {
    prevent_destroy = true
  }
}

# 장부·감사 기록을 잘못 덮어써도 이전 버전으로 되돌릴 수 있게(04 12-1). 사진 버킷과 반대
resource "aws_s3_bucket_versioning" "ops" {
  bucket = aws_s3_bucket.ops.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ops" {
  bucket = aws_s3_bucket.ops.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "ops" {
  bucket                  = aws_s3_bucket.ops.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "ops" {
  bucket = aws_s3_bucket.ops.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# ---------- 탄력적 IP homestamp-eip ----------
# 할당 ID는 비밀값이 아니다(04: 도메인 homestamp.cloud가 이 IP를 가리킨다)
import {
  to = aws_eip.web
  id = "eipalloc-0468fc85262882e8c"
}

resource "aws_eip" "web" {
  domain = "vpc"

  tags = {
    Name = "${var.prefix}-eip"
  }

  lifecycle {
    prevent_destroy = true
  }
}
