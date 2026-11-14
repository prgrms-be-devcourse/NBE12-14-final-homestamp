# 앱 EC2 homestamp-web (04 3-1·8장, 13-1의 8번)
#
# - Amazon Linux 2023 x86(t3는 x86). SSM 에이전트가 기본으로 있어 SSH 없이 들어간다
# - t3.small(2GB) + 스왑 4GB. 블루/그린 때 앱 두 개가 잠깐 겹치는 몫을 스왑이 받는다(04 3-1)
# - 공개 서브넷 a(RDS와 같은 가용 영역), 탄력적 IP로만 바깥과 오간다(자동 공인 IP 없음)
# - SSH 키 없음. 들어갈 때는 aws ssm start-session
# - IMDSv2 필수 + 홉 제한 2: 컨테이너 안의 앱이 역할 자격 증명을 받을 수 있게(04 13-1)

# 최신 Amazon Linux 2023 이미지 번호를 AWS 공개 파라미터에서 읽는다
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

locals {
  web_user_data = <<-EOT
    #!/bin/bash
    set -euo pipefail

    # 탄력적 IP가 붙기 전에는 바깥에 나갈 수 없으므로 패키지 설치를 몇 번 다시 시도한다
    for i in $(seq 1 30); do
      dnf -y install docker && break
      sleep 10
    done

    # Docker 로그가 디스크를 채우지 않게(04 8장 "디스크")
    mkdir -p /etc/docker
    cat > /etc/docker/daemon.json <<'JSON'
    {
      "log-driver": "json-file",
      "log-opts": { "max-size": "10m", "max-file": "3" }
    }
    JSON
    systemctl enable --now docker

    # 스왑 4GB(04 3-1). 평소에는 쓰지 않고 메모리가 정말 모자랄 때만(swappiness 10)
    if [ ! -f /swapfile ]; then
      dd if=/dev/zero of=/swapfile bs=1M count=4096
      chmod 600 /swapfile
      mkswap /swapfile
      echo "/swapfile swap swap defaults 0 0" >> /etc/fstab
    fi
    swapon -a
    echo "vm.swappiness=10" > /etc/sysctl.d/99-homestamp.conf
    sysctl --system

    # 서버 시각은 UTC(04 8장 "시간대", 한국 시간 규칙은 앱의 app.zone이 계산)
    timedatectl set-timezone UTC
  EOT
}

resource "aws_instance" "web" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = "t3.small"
  subnet_id              = aws_subnet.public["a"].id
  vpc_security_group_ids = [aws_security_group.web.id]
  iam_instance_profile   = aws_iam_instance_profile.web.name

  associate_public_ip_address = false # 탄력적 IP를 붙인다

  # CPU 크레딧을 다 쓰면 느려질 뿐 추가 요금은 내지 않게(unlimited는 넘친 만큼 요금)
  credit_specification {
    cpu_credits = "standard"
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 20
    encrypted             = true
    delete_on_termination = true
    tags = {
      Name = "${var.prefix}-web-root"
    }
  }

  user_data = local.web_user_data

  tags = {
    Name = "${var.prefix}-web"
  }

  lifecycle {
    # 새 이미지가 나와도, 시작 스크립트를 고쳐도 서버를 다시 만들지 않는다.
    # 시작 스크립트는 처음 켤 때만 돈다. 이미지를 올릴 때는 일부러 바꾼다
    ignore_changes = [ami, user_data]
  }
}

resource "aws_eip_association" "web" {
  instance_id   = aws_instance.web.id
  allocation_id = aws_eip.web.id
}

output "web_instance_id" {
  description = "SSM 세션으로 들어갈 때 쓰는 EC2 ID"
  value       = aws_instance.web.id
}

output "web_public_ip" {
  description = "탄력적 IP (homestamp.cloud가 가리키는 주소)"
  value       = aws_eip.web.public_ip
}
