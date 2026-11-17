.PHONY: local-up local-down run-monolith run-event test settle settle-status

local-up:
	docker compose up -d

local-down:
	docker compose down

run-monolith:
	./gradlew :monolith:bootRun

run-event:
	./gradlew :event-service:bootRun

test:
	./gradlew test

# 퀴즈 이벤트 일괄 정산 (로컬). 당첨자 수는 event-service 현황 API(local 전용)에서 읽는다
#   make settle EVENT=evt-local
EVENT ?= evt-local
ADMIN_TOKEN ?= local-admin-token

settle:
	@winners=$$(curl -s http://localhost:8081/api/events/$(EVENT)/status | sed -n 's/.*"winners":\([0-9]*\).*/\1/p'); \
	echo "event=$(EVENT) winners=$$winners"; \
	curl -s -X POST http://localhost:8080/api/admin/events/$(EVENT)/settlement \
	  -H "X-Admin-Token: $(ADMIN_TOKEN)" -H "Content-Type: application/json" \
	  -d "{\"expectedCount\":$$winners}"; echo

settle-status:
	@curl -s http://localhost:8080/api/admin/events/$(EVENT)/settlement -H "X-Admin-Token: $(ADMIN_TOKEN)"; echo
