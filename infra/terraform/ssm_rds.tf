# 운영 담당 노트북 → SSM → 앱 EC2 → RDS 5432 포트 포워딩 (Role Bootstrap, docs/runbooks/RDS-Bootstrap-접속.md)
#
#   노트북 127.0.0.1:<로컬 포트> ──SSM──▶ 앱 EC2(SSM 에이전트) ──5432──▶ RDS(비공개)
#
# - AWS-StartPortForwardingSessionToRemoteHost는 host·portNumber를 요청마다 받는다. 그 문서를 허락하면
#   서버 메타데이터(169.254.169.254) 같은 아무 주소로도 갈 수 있어 쓰지 않는다(iam_ops.tf 머리말).
# - 이 문서는 같은 구조(sessionType Port, LocalPortForwarding)에서 host와 portNumber를 parameters에 두지 않고
#   properties에 값으로 박는다. 사용자가 고를 수 있는 것은 노트북 쪽 로컬 포트 하나다.
# - ⚠ 확인 못 한 것: AWS 문서는 "parameters에 없는 값을 StartSession에서 덮어쓸 수 없다"를 명시하지 않는다.
#   그래서 이 문서의 목적지 고정은 apply 뒤 실제로 거절되는지 확인하기 전까지 보안 경계로 보지 않는다
#   (런북 "apply 뒤 1회 확인"). 관리 역할은 이미 셸을 가지므로 이 문서가 관리 역할의 도달 범위를 넓히지는 않는다.
# - RDS 주소는 RDS가 생긴 뒤 정해진다(aws_db_instance.main.address). 그래서 이 문서는 RDS 다음에 만들어진다.
#   RDS를 다시 만들어 주소가 바뀌면 문서도 함께 바뀐다.

resource "aws_ssm_document" "rds_forward" {
  name            = "${var.prefix}-rds-forward"
  document_type   = "Session"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "1.0"
    description   = "homestamp: port forwarding to the homestamp RDS endpoint on 5432 only"
    sessionType   = "Port"
    parameters = {
      localPortNumber = {
        type           = "String"
        description    = "Local port on the operator laptop"
        default        = "0"
        allowedPattern = "^([0-9]|[1-9][0-9]{1,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$"
      }
    }
    properties = {
      type            = "LocalPortForwarding"
      host            = aws_db_instance.main.address
      portNumber      = "5432"
      localPortNumber = "{{ localPortNumber }}"
    }
  })

  tags = {
    Name = "${var.prefix}-rds-forward"
  }
}

# 런북이 쓰는 값. 둘 다 비밀값이 아니다(Secret의 값이 아니라 위치). 계정 번호가 들어 있으니 채팅·이슈에 붙이지 않는다
output "db_endpoint_address" {
  description = "RDS 엔드포인트 주소 (psql host, 인증서 이름 확인에 쓴다)"
  value       = aws_db_instance.main.address
}

output "db_master_secret_arn" {
  description = "RDS 관리형 마스터 Secret의 ARN (값이 아님). 운영 관리 역할로만 값을 읽는다"
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}
