# TradingPT Dev Server - Complete AWS Infrastructure Documentation

## 📊 Architecture Overview

**Generated Diagram**: `tpt_dev_architecture.png`

이 문서는 TradingPT Dev 서버의 AWS 인프라 전체 구성을 상세하게 설명합니다. CI/CD 파이프라인부터 네트워크 구조, 보안 구성까지 모든 리소스를 포함합니다.

---

## 🚀 CI/CD Pipeline

### GitHub Actions Workflow

**Workflow File**: `.github/workflows/deploy-dev.yml`

**트리거**:
- develop 브랜치에 push
- develop 브랜치로 merge
- Manual dispatch (workflow_dispatch)

**빌드 및 배포 프로세스**:

#### 1단계: 빌드 및 테스트
```bash
./gradlew clean bootJar
```
- JDK 17 (Corretto) 사용
- Gradle 빌드 수행
- JAR 파일 생성

#### 2단계: Docker 이미지 생성 및 ECR 푸시
```bash
# Docker 이미지 빌드
docker build -t {SERVER_NAME} .

# ECR 로그인
aws ecr get-login-password --region {AWS_REGION} | docker login --username AWS --password-stdin

# 이미지 태깅 (SHA + latest)
docker tag {SERVER_NAME} $ECR_REGISTRY/{SERVER_NAME}:$GITHUB_SHA
docker tag {SERVER_NAME} $ECR_REGISTRY/{SERVER_NAME}:latest

# ECR에 푸시
docker push $ECR_REGISTRY/{SERVER_NAME}:$GITHUB_SHA
docker push $ECR_REGISTRY/{SERVER_NAME}:latest
```

**ECR Repository**:
- Name: `{SERVER_NAME}`
- Region: `{AWS_REGION}`
- Images: 태그별로 SHA 해시 및 latest 버전 관리

#### 3단계: 배포 패키지 생성 및 S3 업로드
```bash
# 배포 정보 파일 생성
cat > deployment-info.env << EOF
ECR_REGISTRY=$ECR_REGISTRY
ECR_REPOSITORY={SERVER_NAME}
IMAGE_TAG=$GITHUB_SHA
AWS_REGION={AWS_REGION}
EOF

# 배포 패키지 압축
tar -czvf $GITHUB_SHA.tar.gz appspec.yml scripts deployment-info.env

# S3에 업로드
aws s3 cp ./$GITHUB_SHA.tar.gz s3://tpt-dev-deployments/$GITHUB_SHA.tar.gz
```

**S3 Bucket**:
- Name: `tpt-dev-deployments`
- Purpose: CodeDeploy 배포 패키지 저장소
- 저장 파일: appspec.yml, scripts/, deployment-info.env

#### 4단계: CodeDeploy 배포 실행
```bash
aws deploy create-deployment \
  --application-name {SERVER_NAME} \
  --deployment-config-name CodeDeployDefault.AllAtOnce \
  --deployment-group-name Develop \
  --s3-location bucket=tpt-dev-deployments,bundleType=tgz,key=$GITHUB_SHA.tar.gz
```

**CodeDeploy Application**:
- Application Name: `{SERVER_NAME}`
- Deployment Group: `Develop`
- Deployment Config: `CodeDeployDefault.AllAtOnce` (모든 인스턴스 동시 배포)
- Target: EC2 인스턴스 (tpt-dev-server-a)

---

### CodeDeploy Lifecycle Hooks

**appspec.yml 구조**:
```yaml
version: 0.0
os: linux

files:
  - source: /
    destination: /home/ubuntu/{SERVER_NAME}

hooks:
  BeforeInstall:    # 기존 컨테이너 정리
  ApplicationStop:  # 실행 중인 서비스 중지
  ApplicationStart: # 새 Docker 컨테이너 시작
  ValidateService:  # Health Check 검증
```

#### Hook 1: BeforeInstall (`scripts/before-install.sh`)
```bash
# 기존 컨테이너 정리
docker stop tpt-spring-app || true
docker rm tpt-spring-app || true

# 사용하지 않는 Docker 이미지 정리
docker image prune -f || true
```

#### Hook 2: ApplicationStop (`scripts/stop-server.sh`)
```bash
# 컨테이너 graceful 종료
docker stop tpt-spring-app
```

#### Hook 3: ApplicationStart (`scripts/start-server.sh`)

**주요 작업**:

1. **ECR 로그인**
```bash
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin $ECR_REGISTRY
```

2. **Parameter Store에서 환경변수 가져오기**
```bash
aws ssm get-parameters-by-path \
  --path "/tpt-api/dev/" \
  --recursive \
  --with-decryption \
  --region {AWS_REGION}
```

**Parameter Store 경로**: `/tpt-api/dev/`
- 모든 민감한 환경변수 저장 (DB credentials, API keys, secrets)
- 암호화 지원 (SecureString)
- 실시간 환경변수 업데이트 가능

3. **Docker 이미지 Pull 및 실행**
```bash
docker pull $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG

docker run -d \
  --name tpt-spring-app \
  --env-file /tmp/app.env \
  -p 8080:8080 \
  --memory="700m" \
  --memory-swap="1g" \
  --restart unless-stopped \
  --log-driver awslogs \
  --log-opt awslogs-group="/tpt/dev/application" \
  --log-opt awslogs-stream="tpt-spring-app-$(date +%Y%m%d)" \
  --log-opt awslogs-region="{AWS_REGION}" \
  $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
```

**Docker 컨테이너 설정**:
- Name: `tpt-spring-app`
- Port: `8080` (Spring Boot)
- Memory Limit: `700MB` (swap: 1GB)
- Restart Policy: `unless-stopped`
- Log Driver: `awslogs` (CloudWatch Logs 직접 전송)
- Log Group: `/tpt/dev/application`
- Log Stream: `tpt-spring-app-YYYYMMDD`

#### Hook 4: ValidateService (`scripts/validate-service.sh`)

**Health Check**:
```bash
# 최대 30회 시도 (10초 간격 = 총 5분)
curl -f -s http://localhost:8080/actuator/health
```

**Health Check Endpoint**: `/actuator/health`
- 성공: HTTP 200 OK, `{"status":"UP"}`
- 실패: 컨테이너 로그 출력 및 배포 실패 처리

---

## 🌐 VPC Network Architecture

### VPC Configuration

**VPC Information**:
- **VPC ID**: `vpc-052cdd1102daa3f37`
- **CIDR Block**: `10.0.0.0/16` (65,536 IPs)
- **Name**: `tpt-dev-vpc`
- **Region**: `{AWS_REGION}` (Seoul)
- **Availability Zones**: 2개 ({AWS_REGION}a, {AWS_REGION}c)

---

### Subnet Architecture

총 6개의 서브넷이 2개의 가용 영역에 걸쳐 배치되어 있습니다.

#### Public Subnets (2개)

| Name | CIDR | AZ | Subnet ID | Auto-assign Public IP |
|------|------|-------|-----------|----------------------|
| tpt-dev-public-subnet-a | 10.0.1.0/24 | {AWS_REGION}a | subnet-09ec6219744056447 | No (명시적으로 false) |
| tpt-dev-public-subnet-c | 10.0.2.0/24 | {AWS_REGION}c | subnet-09bf0aa5940395a1f | No (명시적으로 false) |

**용도**:
- Application Load Balancer (ALB) 배치
- NAT Gateway 배치
- Bastion Host 배치
- Internet Gateway로 직접 라우팅

#### App Private Subnets (2개)

| Name | CIDR | AZ | Subnet ID | 용도 |
|------|------|-------|-----------|------|
| tpt-dev-app-private-subnet-a | 10.0.3.0/24 | {AWS_REGION}a | subnet-03759d2a26ba8963e | Application Server |
| tpt-dev-app-private-subnet-c | 10.0.4.0/24 | {AWS_REGION}c | subnet-05c4b84143a3537d4 | (예비) |

**용도**:
- Spring Boot API 서버 (Docker 컨테이너)
- Private IP만 할당
- NAT Gateway를 통한 아웃바운드 인터넷 접속

#### DB Private Subnets (2개)

| Name | CIDR | AZ | Subnet ID | 용도 |
|------|------|-------|-----------|------|
| tpt-dev-db-private-subnet-a | 10.0.5.0/24 | {AWS_REGION}a | subnet-0250039ac381a6001 | RDS Subnet Group |
| tpt-dev-db-private-subnet-c | 10.0.6.0/24 | {AWS_REGION}c | subnet-00483740e8b6e7a68 | RDS MySQL (배치됨) |

**용도**:
- RDS MySQL 데이터베이스
- DB Subnet Group: `tpt-dev-db-subnet-group`
- 완전 격리된 네트워크 (앱 서버만 접근 가능)

---

### Internet Gateway & NAT Gateway

#### Internet Gateway
- **ID**: `igw-0114bc04a2ef5d9cf`
- **Name**: `tpt-dev-vpc-igw`
- **Attached to**: `vpc-052cdd1102daa3f37`
- **Purpose**:
  - Public 서브넷의 인터넷 연결 제공
  - ALB로 들어오는 외부 트래픽 처리
  - Bastion Host 외부 접속

#### NAT Gateway
- **ID**: `nat-0a155b614db973575`
- **Name**: `tpt-dev-nat-gateway-a`
- **Subnet**: `tpt-dev-public-subnet-a` (10.0.1.0/24)
- **Elastic IP**: `13.209.232.146`
- **Allocation ID**: `eipalloc-07727ab5c0ae6d23e`
- **State**: `available`
- **Purpose**:
  - Private 서브넷의 아웃바운드 인터넷 접속 제공
  - App 서버의 패키지 다운로드, API 호출 등

**⚠️ High Availability 고려사항**:
- 현재 NAT Gateway가 단일 AZ (2a)에만 배치됨
- Production 환경에서는 각 AZ마다 NAT Gateway 권장

---

### Route Tables

#### Public Route Table
- **ID**: `rtb-0ce286c2d8c199790`
- **Name**: `tpt-dev-public-routing-table`

**Routes**:
| Destination | Target | 설명 |
|-------------|--------|------|
| 10.0.0.0/16 | local | VPC 내부 통신 |
| 0.0.0.0/0 | igw-0114bc04a2ef5d9cf | 모든 외부 트래픽 → IGW |

**Associated Subnets**:
- tpt-dev-public-subnet-a (10.0.1.0/24)
- tpt-dev-public-subnet-c (10.0.2.0/24)

#### Private Route Table
- **ID**: `rtb-03dd031908108af95`
- **Name**: `tpt-dev-private-routing-table`

**Routes**:
| Destination | Target | 설명 |
|-------------|--------|------|
| 10.0.0.0/16 | local | VPC 내부 통신 |
| 0.0.0.0/0 | nat-0a155b614db973575 | 모든 외부 트래픽 → NAT Gateway |

**Associated Subnets**:
- tpt-dev-app-private-subnet-a (10.0.3.0/24)
- tpt-dev-app-private-subnet-c (10.0.4.0/24)
- tpt-dev-db-private-subnet-a (10.0.5.0/24)
- tpt-dev-db-private-subnet-c (10.0.6.0/24)

---

## 💻 Compute Resources (EC2)

### 1. Bastion Host (Jump Server)

**Instance Details**:
- **Name**: `tpt-dev-bastion-host`
- **Instance ID**: `i-0ad4bd4fb2fa30937`
- **Instance Type**: `t2.micro` (1 vCPU, 1GB RAM)
- **Subnet**: `tpt-dev-public-subnet-a` (10.0.1.0/24)
- **Private IP**: `10.0.1.31`
- **Public IP**: `3.39.6.130`
- **Security Group**: `sg-033ec763b4b709746` (tpt-dev-bastion-host-security)
- **State**: `running`

**Purpose**:
- Private 서브넷 인스턴스 접근을 위한 SSH Gateway
- 보안상 직접 SSH 접근 방지
- 2단계 인증 (Bastion → App Server)

**접속 방법**:
```bash
# Bastion Host 접속
ssh -i your-key.pem ubuntu@3.39.6.130

# Bastion에서 App Server 접속
ssh ubuntu@10.0.3.118
```

### 2. Application Server

**Instance Details**:
- **Name**: `tpt-dev-server-a`
- **Instance ID**: `i-0ff8c7f966ae3dbd8`
- **Instance Type**: `t2.micro` (1 vCPU, 1GB RAM)
- **Subnet**: `tpt-dev-app-private-subnet-a` (10.0.3.0/24)
- **Private IP**: `10.0.3.118`
- **Public IP**: None (Private subnet)
- **Security Group**: `sg-0beb047d32700fd8b` (tpt-dev-server-security)
- **State**: `running`

**Running Services**:
- **Docker Engine**: 컨테이너 런타임
- **Spring Boot Application**: `tpt-spring-app` 컨테이너
  - Port: 8080
  - Memory: 700MB
  - Logs: CloudWatch Logs (`/tpt/dev/application`)

**Purpose**:
- Spring Boot 3.5.5 API 서버 실행
- Docker 컨테이너 기반 배포
- ALB Target Group에 등록
- Health Check: `/actuator/health`

**⚠️ High Availability 고려사항**:
- 현재 단일 인스턴스 구성 (Single Point of Failure)
- Production 환경에서는 Auto Scaling Group + 다중 AZ 권장

---

## 🗄️ Database (RDS)

### RDS MySQL Instance

**Instance Details**:
- **DB Identifier**: `tpt-dev-db`
- **Instance Class**: `db.t4g.micro` (2 vCPU, 1GB RAM, ARM64)
- **Engine**: `MySQL 8.0.42`
- **Endpoint**: `tpt-dev-db.c5iossg2kg21.{AWS_REGION}.rds.amazonaws.com`
- **Port**: `3306`
- **Availability Zone**: `{AWS_REGION}c`
- **MultiAZ**: `False` (Single AZ deployment)

**Network Configuration**:
- **DB Subnet Group**: `tpt-dev-db-subnet-group`
  - Subnets: tpt-dev-db-private-subnet-a (2a), tpt-dev-db-private-subnet-c (2c)
- **Security Group**: `sg-05cf07de01199e3bb` (tpt-dev-db-security)
- **Publicly Accessible**: `No` (Private only)

**Storage**:
- Type: General Purpose SSD (gp2)
- Allocated: 20GB (추정)
- Auto-scaling: Enabled (추정)

**Backup**:
- Automated Backups: Enabled (추정)
- Retention Period: 7 days (추정)
- Backup Window: Configured

**Connection from App Server**:
```bash
# App Server (10.0.3.118) → RDS (port 3306)
mysql -h tpt-dev-db.c5iossg2kg21.{AWS_REGION}.rds.amazonaws.com \
      -P 3306 \
      -u admin \
      -p
```

**⚠️ High Availability 고려사항**:
- 현재 Single AZ 배포 (자동 장애 조치 미지원)
- Production 환경에서는 Multi-AZ 배포 권장 (자동 failover)

---

## ⚖️ Load Balancer (ALB)

### Application Load Balancer

**ALB Details**:
- **Name**: `{ALB_NAME}`
- **Type**: `Application Load Balancer` (Layer 7)
- **DNS Name**: `{ALB_NAME}-1249776874.{AWS_REGION}.elb.amazonaws.com`
- **ARN**: `arn:aws:elasticloadbalancing:{AWS_REGION}:{AWS_ACCOUNT_ID}:loadbalancer/app/{ALB_NAME}/d526d1421df8acab`
- **Scheme**: `internet-facing` (외부 인터넷 접근 가능)
- **State**: `active`

**Availability Zones**:
| AZ | Subnet | Subnet CIDR |
|----|--------|-------------|
| {AWS_REGION}a | subnet-09ec6219744056447 | 10.0.1.0/24 (public-subnet-a) |
| {AWS_REGION}c | subnet-09bf0aa5940395a1f | 10.0.2.0/24 (public-subnet-c) |

**Security Group**: `sg-02c1109775781afeb` (tpt-server-alb-security)

**Listeners** (추정):
- HTTP: Port 80 → Forward to Target Group
- HTTPS: Port 443 → Forward to Target Group (SSL/TLS 인증서 사용)

### Target Group

**Target Group Details**:
- **Name**: `tpt-dev-server-tg`
- **Target Type**: `instance`
- **Protocol**: `HTTP`
- **Port**: `8080`
- **VPC**: `vpc-052cdd1102daa3f37`

**Registered Targets**:
| Instance ID | IP | Port | Health Status |
|-------------|----|----- |---------------|
| i-0ff8c7f966ae3dbd8 | 10.0.3.118 | 8080 | ✅ healthy |

**Health Check Configuration** (추정):
- Path: `/actuator/health`
- Protocol: HTTP
- Port: 8080
- Interval: 30 seconds
- Timeout: 5 seconds
- Healthy Threshold: 2
- Unhealthy Threshold: 2

**Load Balancing Algorithm**: Round Robin (단일 타겟이므로 의미 없음)

---

## 🔒 Security Groups

### Security Group Summary

| Security Group ID | Name | 연결 리소스 | 용도 |
|-------------------|------|------------|------|
| sg-033ec763b4b709746 | tpt-dev-bastion-host-security | Bastion Host | SSH 접근 제어 |
| sg-02c1109775781afeb | tpt-server-alb-security | ALB | HTTP/HTTPS 트래픽 제어 |
| sg-0beb047d32700fd8b | tpt-dev-server-security | App Server | ALB 및 Bastion에서 접근 제어 |
| sg-05cf07de01199e3bb | tpt-dev-db-security | RDS MySQL | App Server에서 DB 접근 제어 |
| sg-0e0719a09944ec871 | tpt-dev-redis-db-security | (Redis ElastiCache) | Redis 접근 제어 |
| sg-05b33a64507f23fb4 | default | - | VPC 기본 보안 그룹 |

### Inbound Rules (추정)

#### Bastion Host Security Group (sg-033ec763b4b709746)
| Type | Protocol | Port | Source | 설명 |
|------|----------|------|--------|------|
| SSH | TCP | 22 | 0.0.0.0/0 or My IP | SSH 접근 (특정 IP로 제한 권장) |

#### ALB Security Group (sg-02c1109775781afeb)
| Type | Protocol | Port | Source | 설명 |
|------|----------|------|--------|------|
| HTTP | TCP | 80 | 0.0.0.0/0 | 인터넷에서 HTTP |
| HTTPS | TCP | 443 | 0.0.0.0/0 | 인터넷에서 HTTPS |

#### App Server Security Group (sg-0beb047d32700fd8b)
| Type | Protocol | Port | Source | 설명 |
|------|----------|------|--------|------|
| Custom TCP | TCP | 8080 | sg-02c1109775781afeb | ALB에서 App 접근 |
| SSH | TCP | 22 | sg-033ec763b4b709746 | Bastion에서 SSH |

#### DB Security Group (sg-05cf07de01199e3bb)
| Type | Protocol | Port | Source | 설명 |
|------|----------|------|--------|------|
| MySQL/Aurora | TCP | 3306 | sg-0beb047d32700fd8b | App Server에서 DB 접근 |

---

## 📊 CloudWatch & Monitoring

### CloudWatch Logs

**Log Groups**:

#### Application Logs
- **Log Group**: `/tpt/dev/application`
- **Source**: Docker 컨테이너 (awslogs driver)
- **Log Stream Pattern**: `tpt-spring-app-YYYYMMDD`
- **Retention**: 설정된 기간 (추정: 7일 또는 30일)

**로그 내용**:
- Spring Boot 애플리케이션 로그
- HTTP 요청/응답 로그
- 에러 스택 트레이스
- JPA 쿼리 로그 (설정 시)

**로그 확인 방법**:
```bash
# AWS CLI로 로그 확인
aws logs tail /tpt/dev/application --follow --region {AWS_REGION}

# 특정 날짜의 로그 스트림 확인
aws logs get-log-events \
  --log-group-name /tpt/dev/application \
  --log-stream-name tpt-spring-app-20251124 \
  --region {AWS_REGION}
```

### Monitoring Metrics (추정)

**EC2 Metrics**:
- CPU Utilization
- Network In/Out
- Disk Read/Write
- Status Check Failed

**RDS Metrics**:
- Database Connections
- CPU Utilization
- Free Storage Space
- Read/Write IOPS
- Read/Write Latency

**ALB Metrics**:
- Active Connection Count
- Processed Bytes
- HTTP 2xx/4xx/5xx Count
- Target Response Time
- Healthy/Unhealthy Target Count

---

## 🔐 AWS Systems Manager

### Parameter Store

**Path**: `/tpt-api/dev/`

**저장된 환경변수** (예시):
- Database Credentials:
  - `/tpt-api/dev/DB_USERNAME`
  - `/tpt-api/dev/DB_PASSWORD`
  - `/tpt-api/dev/DB_HOST`
- OAuth2 Credentials:
  - `/tpt-api/dev/{OAUTH_CLIENT_ID}`
  - `/tpt-api/dev/{OAUTH_CLIENT_ID}`
  - `/tpt-api/dev/{OAUTH_CLIENT_SECRET}`
- Security Keys:
  - `/tpt-api/dev/REMEMBER_ME_KEY`
- AWS Service Keys:
  - `/tpt-api/dev/AWS_ACCESS_KEY_ID`
  - `/tpt-api/dev/AWS_SECRET_ACCESS_KEY`
  - `/tpt-api/dev/S3_BUCKET_NAME`
- Email Configuration:
  - `/tpt-api/dev/MAIL_NAME`
  - `/tpt-api/dev/MAIL_PASSWORD`
- SMS API Keys:
  - `/tpt-api/dev/SOLAPI_API_KEY`
  - `/tpt-api/dev/SOLAPI_API_SECRET`
  - `/tpt-api/dev/SOLAPI_PHONE_NUMBER`

**Parameter Type**: `SecureString` (KMS 암호화)

**배포 시 동작**:
```bash
# start-server.sh에서 Parameter Store 조회
aws ssm get-parameters-by-path \
  --path "/tpt-api/dev/" \
  --recursive \
  --with-decryption \
  --region {AWS_REGION}
```

---

## 🌐 Traffic Flow Diagrams

### 1. 사용자 → 애플리케이션 트래픽 흐름

```
Internet User
    ↓ (HTTPS/HTTP)
Internet Gateway (igw-0114bc04a2ef5d9cf)
    ↓
Application Load Balancer ({ALB_NAME})
    ├─ Public Subnet A (10.0.1.0/24)
    └─ Public Subnet C (10.0.2.0/24)
    ↓ (HTTP:8080)
Target Group (tpt-dev-server-tg)
    ↓
App Server (i-0ff8c7f966ae3dbd8)
    └─ Docker Container: tpt-spring-app
       └─ Spring Boot App (port 8080)
```

### 2. SSH 접근 트래픽 흐름

```
Developer/Admin
    ↓ (SSH:22)
Bastion Host (3.39.6.130)
    └─ Public Subnet A (10.0.1.0/24)
    ↓ (SSH:22)
App Server (10.0.3.118)
    └─ App Private Subnet A (10.0.3.0/24)
```

### 3. 데이터베이스 접근 트래픽 흐름

```
App Server (10.0.3.118)
    └─ App Private Subnet A
    ↓ (MySQL:3306)
RDS MySQL (tpt-dev-db.*.rds.amazonaws.com)
    └─ DB Private Subnet C (10.0.6.0/24)
```

### 4. 아웃바운드 인터넷 트래픽 흐름

```
App Server (10.0.3.118)
    └─ App Private Subnet A
    ↓ (Outbound requests)
NAT Gateway (nat-0a155b614db973575)
    └─ Public Subnet A (10.0.1.0/24)
    └─ EIP: 13.209.232.146
    ↓
Internet Gateway (igw-0114bc04a2ef5d9cf)
    ↓
Internet
```

### 5. CI/CD 배포 트래픽 흐름

```
GitHub Repository (develop branch)
    ↓ (push/merge)
GitHub Actions
    ├─ Build JAR
    ├─ Build Docker Image
    ↓
ECR ({SERVER_NAME})
    └─ Docker Image Repository
    ↓
S3 (tpt-dev-deployments)
    └─ Deployment Package (appspec.yml, scripts, env)
    ↓
CodeDeploy ({SERVER_NAME})
    ↓ (Deploy to EC2)
App Server (10.0.3.118)
    ├─ Pull Docker Image from ECR
    ├─ Fetch Env Vars from Parameter Store
    ├─ Run Docker Container
    └─ Send Logs to CloudWatch
```

---

## 📈 Cost Estimation (월간 예상 비용)

### Compute (EC2)
| 리소스 | 스펙 | 수량 | 월 비용 (USD) |
|--------|------|------|---------------|
| Bastion Host | t2.micro | 1 | ~$8.50 |
| App Server | t2.micro | 1 | ~$8.50 |
| **소계** | | | **~$17** |

### Database (RDS)
| 리소스 | 스펙 | 수량 | 월 비용 (USD) |
|--------|------|------|---------------|
| MySQL 8.0 | db.t4g.micro | 1 | ~$13 |
| Storage (gp2) | 20GB | 1 | ~$2 |
| **소계** | | | **~$15** |

### Network
| 리소스 | 스펙 | 수량 | 월 비용 (USD) |
|--------|------|------|---------------|
| Application Load Balancer | ALB | 1 | ~$23 |
| NAT Gateway | - | 1 | ~$33 |
| NAT Data Transfer | Per GB | - | Variable (~$0.045/GB) |
| Elastic IP (unused) | - | 0 | $0 |
| **소계** | | | **~$56 + data** |

### Storage & Services
| 리소스 | 월 비용 (USD) |
|--------|---------------|
| S3 (tpt-dev-deployments) | ~$1 |
| ECR (Docker images) | ~$1 |
| CloudWatch Logs | ~$2 |
| Parameter Store | Free (Standard) |
| CodeDeploy | Free |
| **소계** | **~$4** |

### **총 예상 월 비용: ~$92-105 USD**

*(데이터 전송량에 따라 변동 가능)*

---

## 🎯 High Availability & Scalability 개선 권장사항

### 1. 현재 단일 장애점 (SPOF)

| 리소스 | 현재 상태 | 위험도 |
|--------|-----------|--------|
| App Server | 단일 인스턴스 (AZ 2a) | 🔴 High |
| RDS MySQL | Single AZ (AZ 2c) | 🔴 High |
| NAT Gateway | 단일 NAT (AZ 2a) | 🟡 Medium |

### 2. Production 환경 권장 아키텍처

#### A. Auto Scaling Group 구성
```
- Min: 2 instances
- Max: 4 instances
- Desired: 2 instances
- AZs: {AWS_REGION}a, {AWS_REGION}c
- Health Check: ALB Target Group
- Scaling Policy: CPU > 70% or RequestCount > threshold
```

#### B. RDS Multi-AZ 활성화
```
- Primary: {AWS_REGION}a
- Standby: {AWS_REGION}c
- Automatic Failover: Enabled
- Backup: 7-day retention
- Read Replica: Optional (읽기 성능 향상)
```

#### C. NAT Gateway 이중화
```
- NAT Gateway A: {AWS_REGION}a (existing)
- NAT Gateway B: {AWS_REGION}c (new)
- Route Table: AZ별 독립 라우팅
```

#### D. Redis ElastiCache 추가
```
- Cluster Mode: Enabled
- Node Type: cache.t3.micro
- Replicas: 1 per shard
- Purpose: Session storage, caching
```

### 3. 보안 강화 권장사항

#### A. Bastion Host 보안
```
- SSH 접근: 특정 IP 대역으로 제한 (0.0.0.0/0 → Company IP)
- Session Manager: AWS Systems Manager Session Manager 사용 권장
- MFA: Multi-Factor Authentication 활성화
- Key Rotation: 주기적인 SSH 키 교체
```

#### B. ALB 보안
```
- HTTPS Only: HTTP → HTTPS 리다이렉트
- SSL/TLS: ACM 인증서 사용
- WAF: AWS WAF 적용 (DDoS, SQL Injection 방어)
- Security Headers: HSTS, X-Frame-Options 등
```

#### C. RDS 보안
```
- Encryption: Storage 및 전송 암호화
- SSL/TLS: MySQL 연결 시 SSL 강제
- Secrets Manager: DB credentials 자동 로테이션
- Backup: Automated backup 활성화 (7일 보관)
```

### 4. 모니터링 & 알람 설정

#### CloudWatch Alarms 권장 설정
```yaml
EC2_High_CPU:
  Metric: CPUUtilization
  Threshold: > 80%
  Period: 5 minutes
  Action: SNS notification

RDS_Low_Storage:
  Metric: FreeStorageSpace
  Threshold: < 2 GB
  Period: 5 minutes
  Action: SNS notification

ALB_High_Latency:
  Metric: TargetResponseTime
  Threshold: > 2 seconds
  Period: 5 minutes
  Action: SNS notification

Health_Check_Failed:
  Metric: UnHealthyHostCount
  Threshold: >= 1
  Period: 1 minute
  Action: SNS notification + Auto-recovery
```

---

## 📚 관련 문서 및 파일

### GitHub Repository Files
- `.github/workflows/deploy-dev.yml` - GitHub Actions 워크플로우
- `appspec.yml` - CodeDeploy 배포 스펙
- `scripts/before-install.sh` - 배포 전 정리 스크립트
- `scripts/start-server.sh` - 서버 시작 스크립트
- `scripts/stop-server.sh` - 서버 중지 스크립트
- `scripts/validate-service.sh` - Health Check 스크립트
- `Dockerfile` - Docker 이미지 빌드 파일

### Generated Artifacts
- `tpt_dev_architecture.png` - 전체 아키텍처 다이어그램
- `generate_architecture_diagram.py` - 다이어그램 생성 스크립트

### AWS Console References
- **VPC**: `vpc-052cdd1102daa3f37`
- **ECR**: `{AWS_ACCOUNT_ID}.dkr.ecr.{AWS_REGION}.amazonaws.com/{SERVER_NAME}`
- **S3 Bucket**: `tpt-dev-deployments`
- **CodeDeploy Application**: `{SERVER_NAME}`
- **Parameter Store Path**: `/tpt-api/dev/`
- **CloudWatch Log Group**: `/tpt/dev/application`

---

## 🔄 Last Updated

**Date**: 2025-11-24
**Version**: 1.0
**Reviewer**: Architecture Documentation System

---

## 📞 Support & Troubleshooting

### 배포 실패 시 체크리스트

1. **GitHub Actions 실패**:
   - 빌드 로그 확인: GitHub Actions 탭
   - Gradle 빌드 오류 확인
   - AWS credentials 유효성 확인

2. **ECR Push 실패**:
   - ECR 로그인 확인
   - IAM 권한 확인 (ecr:PutImage)
   - 이미지 크기 확인 (ECR 제한: 10GB)

3. **CodeDeploy 실패**:
   - CodeDeploy 로그: `/var/log/aws/codedeploy-agent/`
   - appspec.yml 문법 확인
   - EC2 IAM Role 권한 확인

4. **Health Check 실패**:
   - 애플리케이션 로그: CloudWatch Logs
   - Docker 컨테이너 상태: `docker ps -a`
   - 컨테이너 로그: `docker logs tpt-spring-app`
   - Health Check endpoint 응답: `curl http://localhost:8080/actuator/health`

5. **Database 연결 실패**:
   - Security Group 규칙 확인
   - Parameter Store 환경변수 확인
   - RDS 엔드포인트 및 포트 확인
   - MySQL 사용자 권한 확인

### 긴급 복구 절차

```bash
# 1. 이전 버전 Docker 이미지로 롤백
docker stop tpt-spring-app
docker rm tpt-spring-app
docker run -d --name tpt-spring-app \
  --env-file /tmp/app.env \
  -p 8080:8080 \
  $ECR_REGISTRY/{SERVER_NAME}:previous-working-tag

# 2. CodeDeploy를 통한 이전 배포 재실행
aws deploy create-deployment \
  --application-name {SERVER_NAME} \
  --deployment-group-name Develop \
  --s3-location bucket=tpt-dev-deployments,bundleType=tgz,key=<previous-sha>.tar.gz

# 3. 수동 Health Check
curl http://localhost:8080/actuator/health
```

---

**End of Documentation**
