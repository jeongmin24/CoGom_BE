# CoGom BE 배포 가이드

GitHub Actions로 Spring Boot 백엔드를 빌드해 AWS EC2(ARM64)에 배포하는 절차입니다.

| 구성 요소 | 파일 |
| --- | --- |
| 이미지 빌드 | [`Dockerfile`](../Dockerfile), [`.dockerignore`](../.dockerignore) |
| 컨테이너 구성 | [`docker-compose.yml`](../docker-compose.yml), [`.env.example`](../.env.example) |
| 서버 배포 스크립트 | [`deploy/deploy.sh`](../deploy/deploy.sh) |
| CI/CD 파이프라인 | [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) |

## 전체 구조

```text
push(main) ─▶ GitHub Actions (x86_64 러너)
                ├─ mvn verify                      (JDK 21)
                ├─ docker buildx                   (linux/arm64 + linux/amd64)
                └─ push ─▶ ghcr.io/<owner>/<repo>:<commit sha>
                               │
              SSH ─────────────┼──▶ EC2 c6g.2xlarge (Ubuntu, ARM64)  ~/cogom
                               ▼        ├─ cogom-api  (Spring Boot, 8080: HTTP + WebSocket)
                        docker compose  └─ cogom-db   (PostgreSQL 17, 볼륨 cogom_pgdata, 외부 미노출)
```

- **배포 대상**은 ARM64 서버 한 대이고, 백엔드와 PostgreSQL을 같은 Compose 프로젝트로 띄웁니다.
- **빌드**는 GitHub 호스티드 러너에서 수행합니다. 추가 서버(c6g.2xlarge)를 빌드·테스트 전용으로 쓰는 방법은 [3-3](#3-3-arm64-빌드-방식과-대안)에 있습니다.
- 파이프라인은 Jenkins 없이 GitHub Actions만 사용합니다.

---

## 1. EC2 웹 콘솔 및 서버 초기 세팅 (수동 작업)

### 1-1. 보안 그룹 인바운드 규칙

경로: **EC2 콘솔 → 인스턴스 → 대상 인스턴스 선택 → 보안 탭 → 보안 그룹 링크 → 인바운드 규칙 편집 → 규칙 추가**

| 용도 | 유형 | 포트 | 소스 | 비고 |
| --- | --- | --- | --- | --- |
| SSH | SSH | 22 | `0.0.0.0/0` | GitHub 호스티드 러너의 IP는 고정되지 않아 IP 제한이 어렵습니다. 키 인증만 허용합니다(1-4). |
| HTTP | HTTP | 80 | `0.0.0.0/0`, `::/0` | HTTPS 리다이렉트와 인증서 발급용 |
| HTTPS | HTTPS | 443 | `0.0.0.0/0`, `::/0` | 서비스 트래픽 |
| Spring Boot | 사용자 지정 TCP | 8080 | **내 IP** | 초기 동작 확인용. Nginx를 붙인 뒤 이 규칙을 삭제합니다. |
| WebSocket | 추가 규칙 없음 | | | `/ws/study-room`은 HTTP와 같은 포트(8080, Nginx 뒤에서는 443)를 씁니다. |
| PostgreSQL | **규칙을 만들지 않음** | 5432 | | 아래 설명 참고 |

- **PostgreSQL 외부 접근 차단**은 두 겹으로 적용됩니다. 보안 그룹에 5432 규칙이 없고, `docker-compose.yml`의 `db` 서비스에 `ports`가 없어 호스트에도 포트가 열리지 않습니다. DB에 접속해야 하면 SSH로 들어가 `docker exec -it cogom-db psql -U cogom`을 사용합니다.
- **주의:** Docker가 게시한 포트는 `ufw` 규칙을 우회합니다. 서버 방화벽이 아니라 보안 그룹과 Compose의 `ports` 설정으로 통제하세요.
- **아웃바운드 규칙**은 기본값(모두 허용)을 유지합니다. 이미지 pull에 필요합니다.
- **탄력적 IP**를 할당해 인스턴스에 연결하세요. 경로는 **EC2 콘솔 → 네트워크 및 보안 → 탄력적 IP → 탄력적 IP 주소 할당 → 작업 → 탄력적 IP 주소 연결**입니다. 연결하지 않으면 재시작 때 IP가 바뀌어 `EC2_HOST`를 매번 고쳐야 합니다.

### 1-2. 접속 및 ARM64 사전 점검

```bash
ssh -i <키페어>.pem ubuntu@<EC2 퍼블릭 IP>
```

```bash
uname -m                    # aarch64 여야 합니다
dpkg --print-architecture   # arm64 여야 합니다
lsb_release -ds             # Ubuntu 22.04 또는 24.04 LTS
nproc && free -h            # 8 vCPU, 약 16GiB
df -h /                     # 이미지와 DB 볼륨용으로 여유 20GB 이상 권장
timedatectl                 # 시간 동기화 확인 (JWT 만료 검증에 영향)
```

- `x86_64`가 나오면 인스턴스 유형이나 AMI를 잘못 고른 것입니다. c6g 계열은 **64비트(Arm)** AMI로만 실행됩니다.
- 사용하는 이미지(`eclipse-temurin:21-jre`, `postgres:17`)는 모두 `linux/arm64`를 공식 지원합니다.
- 이후 직접 추가하는 이미지나 네이티브 모듈(tree-sitter 등)은 arm64 빌드 제공 여부를 먼저 확인하세요. `docker manifest inspect <이미지>` 출력에 `arm64`가 있어야 합니다.

### 1-3. Docker와 Docker Compose 설치

Docker 공식 apt 저장소를 사용합니다. Compose는 플러그인(`docker compose`)으로 함께 설치됩니다.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker ubuntu      # sudo 없이 docker 실행. 적용하려면 재접속합니다.
sudo systemctl enable --now docker
```

재접속 후 확인합니다.

```bash
docker version --format '{{.Server.Os}}/{{.Server.Arch}}'   # linux/arm64
docker compose version
docker run --rm hello-world
mkdir -p ~/cogom                                            # 배포 디렉터리
```

### 1-4. 배포 전용 SSH 키 등록

EC2 생성 때 받은 `.pem` 대신 배포 전용 키를 따로 만듭니다. 유출 시 이 키만 폐기하면 됩니다.

로컬 PC에서 실행합니다.

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f cogom_deploy_key -N ""
```

- `cogom_deploy_key`는 개인 키이며 GitHub Secret `EC2_SSH_KEY`에 넣습니다.
- `cogom_deploy_key.pub`은 공개 키이며 서버에 등록합니다.

서버에서 공개 키 한 줄을 추가합니다.

```bash
echo "<cogom_deploy_key.pub 내용 한 줄>" >> ~/.ssh/authorized_keys
```

22번 포트가 전체에 열려 있으므로 비밀번호 로그인이 꺼져 있는지 확인합니다. Ubuntu AMI의 기본값은 꺼짐입니다.

```bash
sudo sshd -T | grep -E '^(passwordauthentication|permitrootlogin)'
# passwordauthentication no / permitrootlogin without-password(또는 no) 여야 합니다
```

### 1-5. Nginx 리버스 프록시와 HTTPS (운영 공개 전 필수)

백엔드의 WebSocket 핸들러는 허용 출처가 개발 주소(`localhost:5173`)로 고정되어 있어, 운영에서는 **프런트엔드와 같은 출처**로 접속해야 연결됩니다. Nginx 한 곳에서 FE 정적 파일을 제공하고 `/api`와 `/ws`를 백엔드로 넘기면 조건이 충족됩니다.

```bash
sudo apt-get install -y nginx
sudo tee /etc/nginx/sites-available/cogom > /dev/null <<'NGINX'
server {
    listen 80;
    server_name <도메인>;

    root /var/www/cogom;          # CoGom_FE 의 dist 내용을 여기에 올립니다
    index index.html;

    location / {
        try_files $uri /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;   # 유휴 WebSocket 이 60초 만에 끊기지 않게 합니다
    }
}
NGINX
sudo ln -sf /etc/nginx/sites-available/cogom /etc/nginx/sites-enabled/cogom
sudo nginx -t && sudo systemctl reload nginx
```

HTTPS 인증서는 도메인의 A 레코드를 탄력적 IP로 연결한 뒤 발급합니다.

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d <도메인>
```

Nginx 적용 후 마무리합니다.

1. GitHub Repository variable `APP_BIND`를 `127.0.0.1`로 등록하고 다시 배포합니다(4-2).
2. 보안 그룹에서 8080 규칙을 삭제합니다.

Compose에 설정된 `SERVER_FORWARD_HEADERS_STRATEGY=native`가 Nginx의 `X-Forwarded-*` 헤더를 반영합니다. 이 설정이 없으면 HTTPS에서 WebSocket 출처 판정이 실패하고, 로그인 시도 제한이 모든 사용자를 같은 IP로 취급합니다.

---

## 2. 프로젝트 배포 설정 파일

### 2-1. `Dockerfile`

- **빌드 단계**는 `--platform=$BUILDPLATFORM`으로 빌드 머신의 네이티브 아키텍처에서 Maven을 실행합니다. JAR는 아키텍처와 무관하므로 x86_64 러너에서도 에뮬레이션 없이 빌드됩니다.
- **실행 단계**는 타깃 아키텍처의 `eclipse-temurin:21-jre`에 파일만 복사합니다. `RUN`이 없어 `linux/arm64` 이미지를 QEMU 없이 만들 수 있습니다.
- Spring Boot 레이어 추출을 사용해 의존성 레이어와 애플리케이션 레이어를 분리합니다. 코드만 바뀐 배포는 작은 레이어만 내려받습니다.
- root가 아닌 UID 10001로 실행합니다.

### 2-2. `docker-compose.yml`

| 항목 | 내용 |
| --- | --- |
| 영속성 | 이름 있는 볼륨 `cogom_pgdata`를 `/var/lib/postgresql/data`에 연결 |
| 헬스체크 | `db`는 `pg_isready`, `app`은 `GET /api/v1/health` |
| 기동 순서 | `app`은 `db`가 healthy가 된 뒤 시작 |
| 네트워크 | 전용 네트워크 `backend`. `db`는 포트를 게시하지 않음 |
| 재시작 | `restart: unless-stopped`로 서버 재부팅 후 자동 기동 |
| 로그 | 컨테이너당 10MB × 5개로 회전 |

애플리케이션 코드와 `application.yml`은 수정하지 않고 환경 변수로 운영 값을 덮어씁니다.

| 환경 변수 | 역할 |
| --- | --- |
| `COGOM_DB_URL` | `jdbc:postgresql://db:5432/<DB_NAME>` |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | `application.yml`의 H2용 `sa` 계정을 덮어씀 |
| `COGOM_JWT_SECRET` | 재시작 후에도 토큰이 유효하도록 고정 서명 키 지정 |
| `SERVER_ADDRESS=0.0.0.0` | `application.yml`의 `127.0.0.1` 바인딩을 컨테이너에서 해제 |

`pom.xml`에는 PostgreSQL JDBC 드라이버(`org.postgresql:postgresql`, runtime)를 추가했습니다. H2 의존성은 그대로라서 로컬의 `mvn spring-boot:run`은 이전과 같이 동작합니다.

### 2-3. 로컬에서 같은 구성으로 실행

```bash
cp .env.example .env        # DB_PASSWORD, JWT_SECRET 을 채웁니다
docker compose up -d --build
curl http://127.0.0.1:8080/api/v1/health
```

정리할 때 `docker compose down`은 데이터를 남기고, `docker compose down -v`는 DB 볼륨까지 삭제합니다.

---

## 3. GitHub Actions 워크플로

### 3-1. 동작 순서

`main`에 push하면 실행됩니다. 문서만 바뀐 커밋(`**.md`, `docs/**`)은 건너뜁니다. Actions 탭의 **Run workflow**로 수동 실행도 가능합니다.

**build job**

1. JDK 21로 `mvn verify`를 실행합니다. 테스트가 실패하면 여기서 중단됩니다.
2. buildx로 `linux/arm64`, `linux/amd64` 이미지를 빌드해 GHCR에 올립니다. 태그는 커밋 SHA와 `latest`입니다.

**deploy job**

1. `docker-compose.yml`과 `deploy.sh`를 서버의 `~/cogom`에 복사합니다.
2. Secrets로 `~/cogom/.env`를 생성합니다. 권한은 600입니다.
3. 서버에서 `deploy.sh <이미지>`를 실행합니다.
4. 서버의 GHCR 로그인 정보와 러너의 SSH 키를 삭제합니다.

### 3-2. 재시작 방식과 롤백

`deploy.sh`는 다음 순서로 동작합니다.

1. 현재 실행 중인 이미지를 기록합니다.
2. 새 이미지를 **먼저 pull**한 뒤 `app` 컨테이너만 교체합니다. 설정이 바뀌지 않은 `db`는 재시작되지 않습니다.
3. 헬스체크 통과를 최대 180초 기다립니다.
4. 실패하면 로그를 출력하고 **직전 이미지로 자동 롤백**한 뒤 워크플로를 실패 처리합니다.

교체 중에는 애플리케이션 기동 시간만큼 요청이 끊깁니다. 수 초에서 십수 초 수준입니다. 완전한 무중단(블루·그린)을 적용하지 않은 이유는 스터디룸 상태가 서버 메모리에 있어, 컨테이너를 두 벌 띄워도 WebSocket 연결과 방 상태가 새 인스턴스로 넘어가지 않기 때문입니다. 방 상태를 Redis 등 외부 저장소로 옮긴 뒤에 블루·그린 전환을 검토하세요.

수동 롤백은 서버에서 원하는 커밋의 이미지를 지정합니다.

```bash
cd ~/cogom && ./deploy.sh ghcr.io/<owner>/<repo 소문자>:<되돌릴 커밋 SHA>
```

비공개 패키지라면 먼저 `docker login ghcr.io`가 필요합니다.

### 3-3. ARM64 빌드 방식과 대안

| 방식 | 설정 | 특징 |
| --- | --- | --- |
| **크로스 빌드 (현재 적용)** | `runs-on: ubuntu-latest` + buildx | 추가 인프라 없음. Dockerfile 구조 덕분에 에뮬레이션 없이 빠름 |
| GitHub ARM 러너 | `runs-on: ubuntu-24.04-arm` | 네이티브 arm64. 통합 테스트까지 arm64에서 돌릴 때 적합 |
| 추가 서버를 self-hosted 러너로 | `runs-on: [self-hosted, linux, ARM64]` | c6g.2xlarge에서 빌드와 통합·부하 테스트 수행. tree-sitter 같은 네이티브 모듈 빌드에 유리 |
| 서버에서 직접 빌드 | 서버에서 `docker compose up -d --build` | 레지스트리 불필요. 운영 서버 자원을 빌드에 사용 |

- 현재 Dockerfile은 어느 방식에서도 수정 없이 동작합니다.
- 실행 단계에 `RUN`을 추가하면 x86_64 러너에서 QEMU 에뮬레이션이 필요합니다. 워크플로에 `docker/setup-qemu-action`을 넣어 두어 빌드는 유지되지만 느려집니다.
- **self-hosted 러너 주의:** 이 저장소는 공개 저장소입니다. 포크의 Pull Request가 러너에서 임의 코드를 실행할 수 있으므로, **Settings → Actions → General**에서 외부 기여자 워크플로에 승인을 요구하도록 설정하고 러너를 운영 서버와 분리하세요.

---

## 4. GitHub 저장소 설정 (수동 작업)

경로: **저장소 → Settings → Secrets and variables → Actions**

### 4-1. Secrets 탭 → New repository secret

| 이름 | 필수 | 값 | 값을 얻는 방법 |
| --- | --- | --- | --- |
| `EC2_HOST` | 필수 | 서버 주소 | EC2 콘솔 → 인스턴스 → **퍼블릭 IPv4 주소**(탄력적 IP) 또는 연결한 도메인 |
| `EC2_SSH_KEY` | 필수 | 배포용 개인 키 전체 | 1-4에서 만든 `cogom_deploy_key` 파일 내용. `-----BEGIN OPENSSH PRIVATE KEY-----`부터 `-----END OPENSSH PRIVATE KEY-----`까지 줄바꿈 포함 |
| `DB_PASSWORD` | 필수 | PostgreSQL 비밀번호 | `openssl rand -hex 24`로 새로 생성 |
| `JWT_SECRET` | 필수 | JWT 서명 키 | `openssl rand -base64 48`로 새로 생성. 디코딩 시 32바이트 이상인 표준 Base64여야 함 |
| `EC2_USER` | 선택 | SSH 사용자 | 생략하면 `ubuntu`. Ubuntu AMI의 기본 사용자 |
| `EC2_KNOWN_HOSTS` | 권장 | 서버 호스트 키 | 로컬에서 `ssh-keyscan <EC2_HOST>` 출력 전체 |

개인 키를 클립보드로 복사하는 명령입니다.

```bash
# Windows (Git Bash)
cat cogom_deploy_key | clip
# macOS
pbcopy < cogom_deploy_key
```

- **`DB_PASSWORD`는 영문과 숫자만 사용하세요.** 작은따옴표가 들어가면 `.env` 생성이 깨집니다. `openssl rand -hex`의 출력은 안전합니다.
- **`DB_PASSWORD`는 첫 배포 이후 바꾸지 마세요.** PostgreSQL은 볼륨을 처음 초기화할 때만 비밀번호를 설정합니다. 나중에 Secret만 바꾸면 앱이 DB에 접속하지 못합니다. 변경하려면 먼저 `docker exec -it cogom-db psql -U cogom -c "ALTER USER cogom PASSWORD '<새 값>';"`을 실행한 뒤 Secret을 고칩니다.
- **`JWT_SECRET`을 바꾸면** 이미 발급된 토큰이 모두 무효가 됩니다.
- **`EC2_KNOWN_HOSTS`를 생략하면** 워크플로가 접속 시점의 호스트 키를 그대로 신뢰합니다. 등록해 두면 서버 위장 공격을 막을 수 있습니다.
- GHCR 푸시와 pull은 워크플로가 자동 발급받는 `GITHUB_TOKEN`을 사용합니다. 별도의 토큰 Secret은 필요 없습니다.

### 4-2. Variables 탭 → New repository variable (모두 선택)

| 이름 | 기본값 | 용도 |
| --- | --- | --- |
| `APP_BIND` | `0.0.0.0` | Nginx 적용 후 `127.0.0.1`로 변경해 8080 직접 접근 차단 |
| `APP_PORT` | `8080` | 호스트에 게시할 포트 |
| `DB_NAME` | `cogom` | 데이터베이스 이름. 첫 배포 이후 변경하지 않음 |
| `DB_USER` | `cogom` | 데이터베이스 사용자. 첫 배포 이후 변경하지 않음 |

### 4-3. 그 밖의 확인 사항

- **Settings → Actions → General → Actions permissions**에서 Actions 실행이 허용되어 있어야 합니다. 패키지 쓰기 권한은 워크플로 파일의 `permissions`에 선언되어 있어 따로 바꿀 필요가 없습니다.
- 첫 배포 후 저장소 메인 화면의 **Packages**에 이미지가 생깁니다. 패키지는 기본적으로 비공개이며, 서버는 배포 때마다 임시 토큰으로 로그인해 받아 옵니다.

### 4-4. 첫 배포 순서

1. 1장의 서버 준비를 마칩니다. 보안 그룹, Docker 설치, 배포 키 등록, `~/cogom` 생성이 포함됩니다.
2. 4-1의 Secrets를 등록합니다.
3. 배포 파일을 `main`에 push합니다. 또는 Actions 탭 → **Deploy** → **Run workflow**를 누릅니다.
4. 결과를 확인합니다.

```bash
curl http://<EC2_HOST>:8080/api/v1/health      # {"status":"UP",...}
```

```bash
ssh ubuntu@<EC2_HOST> 'cd ~/cogom && docker compose ps'   # 두 컨테이너 모두 healthy
```

### 4-5. 문제 해결

| 증상 | 확인할 것 |
| --- | --- |
| `Permission denied (publickey)` | `EC2_SSH_KEY`에 개인 키 전체가 들어갔는지, 공개 키가 서버 `authorized_keys`에 있는지, `EC2_USER`가 맞는지 |
| SSH 접속 시간 초과 | 보안 그룹 22번 규칙, `EC2_HOST` 값, 인스턴스 상태 |
| `exec format error` | 이미지 아키텍처 불일치. `docker image inspect <이미지> --format '{{.Architecture}}'`가 `arm64`인지 확인 |
| `app`이 unhealthy | 서버에서 `docker compose logs app`. DB 비밀번호 불일치나 `JWT_SECRET` 형식 오류가 흔한 원인 |
| `password authentication failed` | 첫 배포 이후 `DB_PASSWORD`를 바꾼 경우. 4-1의 변경 절차 참고 |
| WebSocket 403 | FE와 백엔드의 출처가 다름. 1-5의 Nginx 구성으로 같은 출처에서 제공 |
