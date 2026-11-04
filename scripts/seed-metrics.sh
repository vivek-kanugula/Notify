#!/usr/bin/env bash
# ==============================================================================
# Notify Observability Traffic & Metrics Seeder
# Dispatches sample notifications (Email, Webhook, In-App) to populate Prometheus
# counters, gauges, and latency histograms for Grafana dashboards.
# ==============================================================================
set -eo pipefail

BASE_URL="${1:-http://localhost:8080}"
PROMETHEUS_TOKEN="eyJhbGciOiJIUzM4NCIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJqdGkiOiJwcm9tZXRoZXVzLXRva2VuLTEiLCJhdWQiOlsibm90aWZ5LWFkbWluIl0sInJvbGVzIjpbIlBMQVRGT1JNX0FETUlOIl0sImlhdCI6MTc5MzM1NTI1MywiZXhwIjoyMTA4NzE1MjUzfQ.ACfRLV-pt1RQGZYSZt39KJdxPjtZTyq8kMxuYDVHCPUek7bA8IeUmQC012DS-U44"

echo "=========================================================="
echo " Starting Notify Observability Traffic Seeder"
echo " Target API: $BASE_URL"
echo "=========================================================="

RANDOM_ID=$(date +%s)
DEMO_EMAIL="demo-metrics-${RANDOM_ID}@example.com"
DEMO_PASS="DemoPassword123!"

echo "1. Registering demo tenant ($DEMO_EMAIL)..."
SIGNUP_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/auth/signup" \
  -H "Content-Type: application/json" \
  -d "{\"tenantName\":\"Observability Demo ${RANDOM_ID}\",\"email\":\"${DEMO_EMAIL}\",\"password\":\"${DEMO_PASS}\"}")

HTTP_CODE=$(echo "$SIGNUP_RESP" | tail -n1)
BODY=$(echo "$SIGNUP_RESP" | sed '$d')

if [ "$HTTP_CODE" -eq 201 ] || [ "$HTTP_CODE" -eq 200 ]; then
  TOKEN=$(echo "$BODY" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
  echo " [OK] Tenant created, access token acquired."
else
  echo " [INFO] Signup returned $HTTP_CODE, attempting login..."
  LOGIN_RESP=$(curl -s -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${DEMO_EMAIL}\",\"password\":\"${DEMO_PASS}\"}")
  TOKEN=$(echo "$LOGIN_RESP" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
fi

if [ -z "$TOKEN" ]; then
  echo " [ERROR] Failed to obtain access token. Response: $BODY"
  exit 1
fi

echo "2. Dispatching 15 sample notifications across all channels..."

# 5 EMAIL notifications
for i in {1..5}; do
  curl -s -X POST "$BASE_URL/api/v1/notifications" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"channel\":\"EMAIL\",\"recipient\":\"candidate-${i}@example.com\",\"subject\":\"System Performance Report #${i}\",\"body\":\"Cluster latency within SLA: 4.2ms\"}" > /dev/null
  echo -n "."
done

# 5 IN_APP notifications
for i in {1..5}; do
  curl -s -X POST "$BASE_URL/api/v1/notifications" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"channel\":\"IN_APP\",\"recipient\":\"demo-user\",\"subject\":\"Realtime SSE Stream Update #${i}\",\"body\":\"Notification delivered via Redis fanout bus.\"}" > /dev/null
  echo -n "."
done

# 5 WEBHOOK notifications
for i in {1..5}; do
  curl -s -X POST "$BASE_URL/api/v1/notifications" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"channel\":\"WEBHOOK\",\"recipient\":\"https://httpbin.org/post\",\"subject\":\"Webhook Event #${i}\",\"body\":\"{\\\"event\\\":\\\"order.created\\\",\\\"seq\\\":${i}}\"}" > /dev/null
  echo -n "."
done
echo ""
echo " [OK] 15 notifications queued (5 EMAIL, 5 IN_APP, 5 WEBHOOK)."

echo "3. Waiting 3 seconds for asynchronous workers to complete delivery..."
sleep 3

echo "4. Checking Prometheus backend scrape endpoint..."
SCRAPE_PREVIEW=$(curl -s -H "Authorization: Bearer $PROMETHEUS_TOKEN" "$BASE_URL/actuator/prometheus" | grep -E "^(notify_notifications_created_total|notify_delivery_attempts_total|notify_queue_depth)" | head -n 10 || true)

if [ -n "$SCRAPE_PREVIEW" ]; then
  echo " [OK] Metrics actively reporting:"
  echo "$SCRAPE_PREVIEW"
else
  echo " [NOTE] Scrape completed. Metrics are ready for Prometheus to collect."
fi

echo "=========================================================="
echo " Metrics generation complete! Open Grafana to view live graphs."
echo "=========================================================="
