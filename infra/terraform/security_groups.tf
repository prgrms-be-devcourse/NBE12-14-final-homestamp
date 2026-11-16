# 보안 그룹 (04 13-1의 4번): 서버마다 "누가 어느 문(포트)으로 들어오고 나갈 수 있나"
#
#   인터넷 ──80·443──▶ web(앱 EC2) ──5432──▶ db(RDS)
#                         ▲ 9091·9092
#                    monitoring(모니터링 EC2)
#
# - SSH(22)는 어디에도 열지 않는다. 서버에는 SSM 세션으로만 들어간다
# - 9091·9092(Actuator)는 인터넷이 아니라 모니터링 보안 그룹에서만
# - 5432(PostgreSQL)는 앱 보안 그룹에서만. 팀원이 DB를 볼 때도 SSM 포트 포워딩으로 앱 EC2를 거친다
# - 나가는 규칙도 필요한 것만 연다(그룹을 만들면 AWS가 넣는 "전부 허용"은 Terraform이 지운다)
# - 규칙은 따로따로 둔다(aws_vpc_security_group_*_rule). 한 줄을 고쳐도 다른 줄이 다시 만들어지지 않는다

# VPC를 만들면 생기는 기본 보안 그룹은 규칙을 모두 비워 둔다(실수로 이 그룹을 붙여도 아무것도 통하지 않게)
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.prefix}-default-sg-unused"
  }
}

# ---------- web: 앱 EC2 (nginx + Spring) ----------
resource "aws_security_group" "web" {
  name        = "${var.prefix}-web-sg"
  description = "app EC2: HTTP/HTTPS from internet only"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.prefix}-web-sg"
  }
}

resource "aws_vpc_security_group_ingress_rule" "web_http" {
  security_group_id = aws_security_group.web.id
  description       = "HTTP (redirect to HTTPS, Lets Encrypt check)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_ingress_rule" "web_https" {
  security_group_id = aws_security_group.web.id
  description       = "HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "web_actuator_from_monitoring" {
  security_group_id            = aws_security_group.web.id
  description                  = "Actuator (blue 9091, green 9092) from monitoring only"
  referenced_security_group_id = aws_security_group.monitoring.id
  ip_protocol                  = "tcp"
  from_port                    = 9091
  to_port                      = 9092
}

resource "aws_vpc_security_group_egress_rule" "web_https_out" {
  security_group_id = aws_security_group.web.id
  description       = "HTTPS out: GHCR, SSM, S3, Parameter Store, SMS provider, Lets Encrypt"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "web_http_out" {
  security_group_id = aws_security_group.web.id
  description       = "HTTP out: OS package mirrors"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "web_to_db" {
  security_group_id            = aws_security_group.web.id
  description                  = "PostgreSQL to RDS"
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# ---------- monitoring: 모니터링 EC2 (Prometheus + Grafana) ----------
# 들어오는 규칙이 하나도 없다. Grafana는 SSM 포트 포워딩으로만 본다(04 11장)
resource "aws_security_group" "monitoring" {
  name        = "${var.prefix}-monitoring-sg"
  description = "monitoring EC2: no inbound, scrape app actuator"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.prefix}-monitoring-sg"
  }
}

resource "aws_vpc_security_group_egress_rule" "monitoring_https_out" {
  security_group_id = aws_security_group.monitoring.id
  description       = "HTTPS out: images, SSM, Slack webhook"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "monitoring_http_out" {
  security_group_id = aws_security_group.monitoring.id
  description       = "HTTP out: OS package mirrors"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "monitoring_to_web_actuator" {
  security_group_id            = aws_security_group.monitoring.id
  description                  = "scrape app actuator 9091-9092"
  referenced_security_group_id = aws_security_group.web.id
  ip_protocol                  = "tcp"
  from_port                    = 9091
  to_port                      = 9092
}

# ---------- db: RDS ----------
# 앱에서 오는 5432만 받는다. 나가는 규칙은 없다(DB가 먼저 밖으로 연결할 일이 없다)
resource "aws_security_group" "db" {
  name        = "${var.prefix}-db-sg"
  description = "RDS: PostgreSQL from app only"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.prefix}-db-sg"
  }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_web" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from app EC2"
  referenced_security_group_id = aws_security_group.web.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
