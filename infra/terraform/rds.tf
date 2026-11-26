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
#   앱 EC2 역할은 Secrets Manager 값 읽기가 명시적으로 거부돼 있다(iam_web.tf DenySecretsManagerValues).
#   그 Secret을 읽는 운영 관리자 권한은 별도 PR이다(decisions 2026-11-25). Secrets Manager는 과정 사용 가능 서비스
# - RDS가 만드는 Secret은 Terraform이 관리하지 않아 default_tags(Team)가 붙는지 보장되지 않는다.
#   apply 뒤 Secret 메타데이터의 태그만 확인하고(값은 읽지 않음), 없으면 붙인다(04 13-1 RDS 단계)
# - 인스턴스 클래스 기본값은 과정 계정의 db.t4g.micro, 개인 계정은 tfvars로 바꾼다. 서울 리전에서 PostgreSQL 18.1~18.6이
#   db.t4g·db.t3의 micro·small로 주문 가능함을 확인했다(2026-11-25 describe-orderable-db-instance-options. AWS 문서
#   지원표는 이 클래스들을 17까지만 적어 늦게 갱신됨). 과정 기본 허용은 RDS small 1대까지(과정 공지, 04 3장)

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
  engine_version             = "18" # 주 버전만 고정. 만들 때 마이너는 RDS가 정하고 이후 자동으로 올라간다(17장)
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

  # 실수로 지우지 않게 두 겹. deletion_protection은 AWS가(콘솔·CLI·Terraform 모두) 지우기를 거절하고,
  # 아래 prevent_destroy는 Terraform이 지우거나 교체하는 plan 자체를 거절한다(콘솔·CLI는 막지 못함).
  # 계정을 정리할 때는 ① db_deletion_protection = false로 apply ② prevent_destroy를 빼는 PR ③ destroy 순서로 한다.
  # 지울 때 최종 스냅숏을 남긴다(최종·수동 스냅숏은 저절로 지워지지 않으므로 7장 "백업·이관"대로 따로 목록·기한을 둔다)
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

# ---------- 앱 DB 주소 (04 13-1 "DB 주소", 6장 SSM 표) ----------
# RDS 엔드포인트로 #165의 운영 주소 계약을 만들어 앱 경로에 String으로 둔다. 사람이 옮겨 적지 않고,
# RDS를 다시 만들어 주소가 바뀌면 같은 apply에서 따라 바뀐다(떠 있는 앱은 다음 시작부터 새 값을 읽는다).
# 주소는 비밀이 아니다. 사용자·비밀번호는 넣지 않는다(#165가 URL 안 사용자 정보를 거절). 인증서 경로는 이미지 안
# 고정 경로이고, 파일은 G2/G4가 이미지에 넣는다(ca_cert_identifier와 같은 RDS CA 묶음)
locals {
  db_root_cert_path    = "/app/certs/rds-ca.crt"
  db_url_parameter     = "${local.param_prefix}/app/spring.datasource.url"
  db_url_parameter_arn = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.db_url_parameter}"
}

resource "aws_ssm_parameter" "app_datasource_url" {
  name        = local.db_url_parameter
  description = "App JDBC URL (verify-full). Managed by Terraform from the RDS endpoint"
  type        = "String"
  tier        = "Standard"
  value       = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${var.db_name}?sslmode=verify-full&sslrootcert=${local.db_root_cert_path}"

  tags = {
    Name = "${var.prefix}-app-datasource-url"
  }
}
