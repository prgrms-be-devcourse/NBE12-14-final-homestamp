# 상태 파일(Terraform이 만든 자원의 장부)을 둘 곳 (04 13-1 "상태 파일")
# - 운영 버킷 homestamp-ops는 Terraform 밖에서 손으로 만들었다(버전 관리 켬, 공개 차단, 암호화)
# - 잠금은 S3 자체 잠금. DynamoDB는 과정 허용 서비스 목록에 없어 쓰지 않는다
# - backend 블록에는 변수를 쓸 수 없어 값을 그대로 적는다(비밀값 아님)
terraform {
  backend "s3" {
    bucket       = "homestamp-ops"
    key          = "tfstate/homestamp.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
