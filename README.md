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

[OpenAPI 명세](docs/openapi.yaml)를 Swagger Editor에서 열어 요청·응답 스키마와 화면별 계약을 확인할 수 있습니다. REST 헬스 체크와 WebSocket 스터디룸이 실행됩니다. OpenAPI에 적힌 나머지 REST 경로는 계약 초안입니다.

## 실시간 스터디룸

[WebSocket 계약](docs/websocket.md)에 입장, 이동, 타이머, 완료한 일, 깨우기의 메시지를 정의했습니다. 기본 주소는 ws://127.0.0.1:8080/ws/study-room 입니다. FE의 Vite 프록시를 통하면 현재 브라우저 주소의 /ws/study-room 으로 연결합니다.

방은 최대 6명이며 서버 메모리에 플레이어와 최근 완료한 일 12개를 보관합니다. 서버 재시작 시 초기화되고 연결을 끊으면 해당 플레이어가 퇴장합니다. 사용자의 식별자는 서버가 각 연결에 발급합니다.

