# 네트워크 (04 13-1의 1~3번): VPC → 서브넷 → 인터넷 게이트웨이·라우트 테이블
#
# 서브넷은 두 종류로 나눈다.
# - public(a·b·c): 인터넷 게이트웨이로 나가는 길이 있다. 앱 EC2·모니터링 EC2가 여기에 산다
# - private(a·b): 인터넷으로 나가는 길이 아예 없다. RDS 전용(RDS 서브넷 그룹은 가용 영역 2개가 필요해서 둘)
#   RDS는 인터넷에 나갈 일이 없고, 보안 그룹을 잘못 열어도 바깥에서 닿을 길이 없게 한 겹 더 막는다
#   NAT 게이트웨이는 쓰지 않는다(과정 문의 대상이고, 비공개 서브넷에서 밖으로 나갈 자원이 없다)
#
# 앱 EC2와 RDS는 같은 가용 영역(a)에 둔다(04 13-1 "가용 영역", 영역 간 요금·지연)

locals {
  az_public = {
    a = "10.0.1.0/24"
    b = "10.0.2.0/24"
    c = "10.0.3.0/24"
  }
  az_private = {
    a = "10.0.11.0/24"
    b = "10.0.12.0/24"
  }
}

resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true # RDS 엔드포인트 이름을 VPC 안에서 찾으려면 필요

  tags = {
    Name = "${var.prefix}-vpc"
  }
}

resource "aws_subnet" "public" {
  for_each = local.az_public

  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value
  availability_zone = "${var.region}${each.key}"

  # 자동 공인 IP를 주지 않는다. 앱 EC2는 탄력적 IP를 붙이고, 공인 IP가 필요한 서버만 인스턴스에서 따로 켠다
  # (공인 IPv4는 하나마다 시간당 요금이 나간다)
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.prefix}-public-${each.key}"
    Tier = "public"
  }
}

resource "aws_subnet" "private" {
  for_each = local.az_private

  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value
  availability_zone = "${var.region}${each.key}"

  tags = {
    Name = "${var.prefix}-private-${each.key}"
    Tier = "private"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.prefix}-igw"
  }
}

# 공개 서브넷: 바깥(0.0.0.0/0)으로 가는 길은 인터넷 게이트웨이
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.prefix}-rt-public"
  }
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# 비공개 서브넷: VPC 안(10.0.0.0/16)끼리만 다닌다. 바깥으로 가는 줄이 없다
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.prefix}-rt-private"
  }
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}
