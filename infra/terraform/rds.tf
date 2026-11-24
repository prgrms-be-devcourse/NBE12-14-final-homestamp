# RDS PostgreSQL 18 (04 3장 표 "위치", 13-1의 7번, 7장 "꺼진 시간과 백업"·"백업·이관", 17장 "DB 로그")
#
#   web(앱 EC2, 공개 a) ──5432·TLS──▶ db(RDS, 비공개 a)   서브넷 그룹은 비공개 a·b(규칙상 AZ 2개)
#
# - 네트워크·보안 그룹은 이미 있다(network.tf 비공개 서브넷, security_groups.tf db 그룹·web에서 5432만). 여기서는 다시 만들지 않는다
# - 단일 AZ, 앱 EC2와 같은 가용 영역 a. Multi-AZ·읽기 복제본·NAT는 쓰지 않는다(04 3장, 비용)
# - 외부 공개 없음. 비공개 서브넷은 바깥으로 가는 길이 없고 보안 그룹은 web에서 오는 5432만 받는다
# - 저장소 암호화는 만들 때만 켤 수 있어 처음부터 켠다
# - 마스터 비밀번호는 Terraform이 만들거나 받지 않는다. RDS가 Secrets Manager에 만들고 돌린다
#   (manage_master_user_password). 상태 파일에는 비밀값이 아니라 비밀의 ARN만 남는다.
#   마스터 계정은 Role Bootstrap(infra/db/bootstrap_roles.sql, #185)을 돌리는 신뢰된 DB 관리자만 쓴다.
#   앱 EC2 역할은 Secrets Manager 값 읽기가 명시적으로 거부돼 있다(iam_web.tf DenySecretsManagerValues)
#   ⚠ 이 방식은 아직 결정 전이다(04 6장 "DB 비밀번호"는 password_wo 또는 Parameter Store 직접 입력만 적음,
#     Secrets Manager의 과정 허용 여부 미확인). 결정 전에는 apply하지 않는다
# - 인스턴스 클래스는 기본값이 없다. AWS 지원표에서 PostgreSQL 18이 db.t3·db.t4g에 적혀 있지 않아,
#   서울 리전에서 실제로 주문 가능한지와 과정 한도(RDS small까지)를 확인한 뒤 tfvars에 넣는다

locals {
  db_identifier = "${var.prefix}-db"
}

resource "aws_db_subnet_group" "main" {
  name        = "${var.prefix}-db-subnets"
  description = "RDS: private subnets a and b only"
  subnet_ids  = [for key in sort(keys(aws_subnet.private)) : aws_subnet.private[key].id]

  tags = {
    Name = "${var.prefix}-db-subnets"
  }
}

# 17장 "DB 로그": 오류 상세에 값이 남지 않게 terse. TLS 없는 접속은 서버에서도 거절한다(앱은 verify-full)
resource "aws_db_parameter_group" "postgres18" {
  name        = "${var.prefix}-postgres18"
  family      = "postgres18"
  description = "HomeStamp PostgreSQL 18: terse errors, TLS only"

  parameter {
    name  = "log_error_verbosity"
    value = "terse"
  }

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  tags = {
    Name = "${var.prefix}-postgres18"
  }
}

# 로그 내보내기 대상 그룹을 먼저 만들어 보관 기간을 정한다(RDS가 먼저 만들면 기한 없이 쌓인다)
resource "aws_cloudwatch_log_group" "db_postgresql" {
  name              = "/aws/rds/instance/${local.db_identifier}/postgresql"
  retention_in_days = var.db_log_retention_days

  tags = {
    Name = "${local.db_identifier}-postgresql-logs"
  }
}

resource "aws_db_instance" "main" {
  identifier = local.db_identifier

  engine                     = "postgres"
  engine_version             = "18" # 주 버전만 고정, 마이너는 RDS 최신 18.x(17장 고정 버전)
  auto_minor_version_upgrade = true
  instance_class             = var.db_instance_class
  parameter_group_name       = aws_db_parameter_group.postgres18.name

  db_name  = var.db_name
  username = var.db_master_username
  port     = 5432

  manage_master_user_password = true

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  availability_zone      = "${var.region}a" # 앱 EC2와 같은 영역(04 13-1 "가용 영역")
  multi_az               = false
  publicly_accessible    = false

  ca_cert_identifier                  = "rds-ca-rsa2048-g1"
  iam_database_authentication_enabled = false

  # 백업은 자동 일정으로 켜진 평일 낮(12:00 KST), 유지보수는 그 뒤 화요일 13:00 KST (7장)
  backup_retention_period = var.db_backup_retention_days
  backup_window           = "03:00-03:30"
  maintenance_window      = "tue:04:00-tue:04:30"
  copy_tags_to_snapshot   = true

  enabled_cloudwatch_logs_exports = ["postgresql"]
  performance_insights_enabled    = false
  monitoring_interval             = 0

  # 실수로 지우지 않게 두 겹. 지울 때는 먼저 최종 스냅숏을 남긴다
  # (최종·수동 스냅숏은 저절로 지워지지 않으므로 7장 "백업·이관"대로 따로 목록·기한을 둔다)
  deletion_protection       = var.db_deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.db_identifier}-final"
  apply_immediately         = false

  tags = {
    Name = local.db_identifier
  }

  depends_on = [aws_cloudwatch_log_group.db_postgresql]

  lifecycle {
    prevent_destroy = true
  }
}
