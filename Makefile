.PHONY: local-up local-down migrate run-core run-point-command run-point-query run-event test

local-up:
	docker compose up -d

local-down:
	docker compose down

# DB 스키마·데모 시드 적용 (db-migration 모듈, 적용 후 종료). run-core 전에 한 번 실행
migrate:
	./gradlew :db-migration:bootRun

run-core:
	./gradlew :core-service:bootRun

# 포인트 쓰기 (:8082) — 포인트 사용 + 보상 Stream 적립. JWT 는 core-service JWKS 로 검증
run-point-command:
	./gradlew :point-command:bootRun

# 포인트 조회 (:8083). JWT 는 core-service(:8080) JWKS 로 검증하므로 run-core 를 먼저 띄운다
run-point-query:
	./gradlew :point-query:bootRun

run-event:
	./gradlew :event-service:bootRun

test:
	./gradlew test
