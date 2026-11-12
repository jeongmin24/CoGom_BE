# CoGom BE

Java 21과 Spring Boot를 사용하는 CoGom API와 실시간 스터디룸 서버입니다.

## 실행

JDK 21과 Maven 3.6.3 이상이 필요합니다.

~~~powershell
mvn spring-boot:run
~~~

IntelliJ에서는 `pom.xml`을 Maven 프로젝트로 열고 `CogomApplication`을 실행할 수 있습니다.

~~~powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
~~~

`status`는 `UP`, `serverNow`는 서버의 UTC 시각을 반환합니다. FE 개발 서버가 켜져 있으면 `http://127.0.0.1:5173/api/v1/health`에서도 같은 API를 확인할 수 있습니다.

~~~powershell
mvn package
java -jar target/cogom-api-0.1.0-SNAPSHOT.jar
~~~

## API 계약

[OpenAPI 명세](docs/openapi.yaml)에서 요청·응답 예시와 화면별 계약을 확인할 수 있습니다. 헬스 체크, 회원가입, 로그인, 내 계정 조회, WebSocket 스터디룸이 실행됩니다. 테이블과 라운드 REST 경로는 계약 초안입니다.

### 회원가입과 로그인

| 메서드와 경로 | 요청 | 성공 응답 |
| --- | --- | --- |
| `POST /api/v1/auth/signup` | `email`, `password`, `nickname` | `201` 및 `id`, `email`, `nickname` |
| `POST /api/v1/auth/login` | `email`, `password` | `200` 및 `accessToken`, `tokenType`, `expiresIn`, `user` |
| `GET /api/v1/auth/me` | `Authorization: Bearer <accessToken>` | `200` 및 `id`, `email`, `nickname` |

회원가입과 로그인은 `Content-Type: application/json`으로 요청합니다. 프런트엔드는 Vite 프록시와 동일 출처 배포에서 상대 경로 `/api/v1/auth/...`를 사용합니다. 로그인으로 발급된 토큰은 30분간 유효하며, 프런트엔드 메모리에 보관하고 내 계정을 조회할 때 `Authorization` 헤더에 담습니다.

계정은 기본적으로 실행 디렉터리의 `./data/cogom` H2 데이터베이스에 저장됩니다. 저장 위치를 바꾸려면 `COGOM_DB_URL`에 H2 JDBC URL을 지정합니다. 비밀번호는 Argon2id로 해시해 저장합니다.

배포 환경에서는 HTTPS를 사용하고 `COGOM_JWT_SECRET`에 디코딩했을 때 32바이트 이상인 표준 Base64 서명 키를 설정합니다. 설정을 생략하면 서버 시작마다 임의의 키를 생성해 이전에 발급한 토큰은 재시작 후 검증할 수 없습니다.

## 실시간 스터디룸

[WebSocket 계약](docs/websocket.md)에 입장, 이동, 타이머, 완료한 일, 깨우기의 메시지를 정의했습니다. 기본 주소는 ws://127.0.0.1:8080/ws/study-room 입니다. FE의 Vite 프록시를 통하면 현재 브라우저 주소의 /ws/study-room 으로 연결합니다.

방은 최대 6명이며 서버 메모리에 플레이어와 최근 완료한 일 12개를 보관합니다. 서버 재시작 시 초기화되고 연결을 끊으면 해당 플레이어가 퇴장합니다. 사용자의 식별자는 서버가 각 연결에 발급합니다.

