.PHONY: local-up local-down migrate run-core run-event test

local-up:
	docker compose up -d

local-down:
	docker compose down

# DB 스키마·데모 시드 적용 (db-migration 모듈, 적용 후 종료). run-core 전에 한 번 실행
migrate:
	./gradlew :db-migration:bootRun

run-core:
	./gradlew :core-service:bootRun

run-event:
	./gradlew :event-service:bootRun

test:
	./gradlew test
