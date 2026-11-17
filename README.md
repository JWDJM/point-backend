# point-backend

금융 포인트 플랫폼 백엔드 — **모듈러 모놀리스 + 이벤트 MSA** (Java 21 / Spring Boot 3)

| 모듈 | 역할 | 포트 | DR 배포 |
|---|---|---|---|
| `monolith` | user / asset / point / cs | 8080 | ✅ |
| `event-service` | 출석·퀴즈·대기열 (Redis + Kafka) | 8081 | ❌ (DR 시 점검) |

- 설계 문서·ADR: [point-infra/docs](https://github.com/ZZGG2/point-infra/tree/main/docs)
- API 계약: [`api/openapi.yaml`](api/openapi.yaml) · Kafka 스키마: [`api/events/`](api/events/)

## 로컬 실행
상세 가이드 (Windows, 데모 시나리오 포함): 상위 폴더의 `LOCAL-DEV.md`
```bash
docker compose up -d                      # PostgreSQL, Redis, Kafka
./gradlew :monolith:bootRun               # :8080  (Windows: .\gradlew.bat)
./gradlew :event-service:bootRun          # :8081
```

## 현재 구현 범위
| 기능 | 상태 |
|---|---|
| 포인트 잔액/내역/사용 API (멱등키, 행 잠금, 음수 잔액 차단) | ✅ |
| 퀴즈 선착순 (Redis Lua: 마감·중복·재고 원자 처리) | ✅ |
| 출석 보상 → Kafka → 원장 멱등 반영 (즉시) | ✅ |
| 퀴즈 당첨 → Kafka → 정산 대기열 → **이벤트 종료 후 일괄 정산** (한 트랜잭션, DR 재정산 가능, ADR-0017) | ✅ (`make settle EVENT=evt-local`) |
| 로컬 데모 데이터 / 데모 이벤트 자동 생성 | ✅ (`local` 프로필) |
| 운영·인프라 검증 API (`/api/system/{info,whoami,ledger}`, `/api/events/system/{info,whoami}`) — Pod·DB·Redis·Kafka 상태, 원장 집계 | ✅ (`OPS_API_ENABLED=false` 로 끔) |
| 회원가입·로그인 (JWT RS256 access token + HttpOnly refresh 쿠키, rotation) — event-service 는 공개키로 자체 검증 | ✅ |
| 자산조회, 고객센터, 대기열 | ⏳ |
| DLQ, 대사 배치, Testcontainers 통합 테스트 | ⏳ |

## 인증 설정
| 환경변수 | 서비스 | 설명 |
|---|---|---|
| `JWT_PRIVATE_KEY` | monolith | RS256 서명 키 (PKCS#8 PEM). **local 외 프로필은 필수** — local 에서 비우면 임시 키 생성 |
| `JWT_PUBLIC_KEY` | event-service | 위 키의 공개키 (X.509 PEM). 운영은 이것으로 검증 |
| `JWT_JWK_SET_URI` | event-service | local 기본값 `http://localhost:8080/api/auth/jwks.json` (monolith 임시 키 조회) |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | monolith | 기본 15m / 14d |
| `AUTH_COOKIE_SECURE` | monolith | refresh 쿠키 Secure (기본 true, local 프로필은 false) |
| `ADMIN_API_TOKEN` | monolith | `/api/admin/**` (퀴즈 정산) 의 `X-Admin-Token`. 비우면 관리자 API 는 항상 403. local 프로필은 `local-admin-token` |

키 생성 예: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem` → 공개키 `openssl pkey -in jwt.pem -pubout`

## 배포 흐름
`main` 머지 → 이미지 빌드·스캔 → ECR + Artifact Registry 이중 푸시 → **point-infra에 태그 bump PR 자동 생성** → 머지 시 ArgoCD 배포
