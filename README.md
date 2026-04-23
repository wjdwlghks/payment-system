# Payment System

`Spring Boot 4.0.5 + Java 21` 기반의 멀티서비스 예제 프로젝트입니다.

## 구성

- `merchant`: 가맹점 요청 수신
- `payment`: 결제 오케스트레이션
- `fds`: 이상거래 탐지
- `card`: 카드 승인 인터페이스

각 서비스는 독립적인 Spring Boot 애플리케이션으로 구성되어 있고, Docker Compose로 함께 실행할 수 있습니다.

## 기술 기준

- Java 21
- Spring Boot 4.0.5
- Gradle 멀티모듈
- Embedded Tomcat 11
- Docker Compose

## 실행

로컬에 Gradle이 없어도 Docker만 있으면 실행할 수 있습니다.

```bash
docker compose up --build
```

로컬 빌드가 필요하면 Gradle Wrapper를 사용할 수 있습니다.

```bash
./gradlew clean build
```

서비스가 올라오면 기본 포트는 다음과 같습니다.

- `merchant`: `http://localhost:8081`
- `payment`: `http://localhost:8082`
- `fds`: `http://localhost:8083`
- `card`: `http://localhost:8084`

## 현재 포함한 것

- 루트 `build.gradle`과 4개 Gradle 서브모듈
- 각 서비스별 Spring Boot 메인 클래스
- 공통 Docker 빌드 파일과 Compose 설정

## 다음으로 정하면 좋은 것

- DB 선택: MySQL, PostgreSQL, H2 중 무엇을 쓸지
- 서비스 간 규약: REST JSON, 내부 인증 방식, 타임아웃 정책
- 도메인 설계: 주문, 승인, 취소, 정산 범위
- 운영 설정: 로그 포맷, 추적 ID, 설정 파일 분리 방식
- 테스트 전략: 단위 테스트부터 할지, 통합 테스트 환경까지 같이 잡을지

## 참고

현재는 예시 API 없이 부팅 가능한 Boot 앱 골격만 구성한 상태입니다. 이후 결제 도메인 API, DB, 서비스 간 호출 규약을 얹기 좋은 형태로 맞춰 두었습니다.
