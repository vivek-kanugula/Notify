#!/usr/bin/env bash
# ==========================================================================
# Notify Database Backup & Restore Verification (bash)
# ==========================================================================
# Exercises: pg_dump → integrity check → pg_restore to disposable DB →
# row-count comparison → Flyway schema version parity.
#
# Prerequisites:
# - Docker running with notify-postgres container healthy
#
# Usage:
# ./scripts/db-backup-restore.sh # defaults
# ./scripts/db-backup-restore.sh --container my-pg --dir /backups
# ==========================================================================
set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-notify-postgres}"
DB_NAME="${DB_NAME:-notify}"
DB_USER="${DB_USER:-notify_user}"
VERIFY_DB="${VERIFY_DB:-notify_verify}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"

# Parse arguments
while [[ $# -gt 0 ]]; do
 case "$1" in
 --container) CONTAINER_NAME="$2"; shift 2 ;;
 --db) DB_NAME="$2"; shift 2 ;;
 --user) DB_USER="$2"; shift 2 ;;
 --verify-db) VERIFY_DB="$2"; shift 2 ;;
 --dir) BACKUP_DIR="$2"; shift 2 ;;
 *) echo "Unknown option: $1"; exit 1 ;;
 esac
done

if [ "${VERIFY_DB,}" = "${DB_NAME,}" ]; then
 echo "FATAL: Verification database name ('$VERIFY_DB') cannot match target database name ('$DB_NAME'). Aborting to prevent data loss." >&2
 exit 1
fi

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="notify_backup_${TIMESTAMP}.dump"
ALL_PASSED=true

echo "=========================================="
echo " Notify Database Backup & Restore Proof "
echo "=========================================="
echo ""

# ── Helper ──────────────────────────────────────────────────────────────
pg_cmd { docker exec "$CONTAINER_NAME" sh -c "$1"; }
pg_sql { docker exec "$CONTAINER_NAME" psql -U "$DB_USER" -d "${2:-$DB_NAME}" -t -A -c "$1"; }

# ── 1. Create backup directory ──────────────────────────────────────────
echo "[1/6] Creating backup directory..."
mkdir -p "$BACKUP_DIR"
echo " -> Directory: $BACKUP_DIR"

# ── 2. Database dump ────────────────────────────────────────────────────
echo ""
echo "[2/6] Running pg_dump (custom format)..."
if pg_cmd "pg_dump -U $DB_USER -Fc $DB_NAME > /tmp/$BACKUP_FILE"; then
 docker cp "${CONTAINER_NAME}:/tmp/${BACKUP_FILE}" "${BACKUP_DIR}/${BACKUP_FILE}"
 FILE_SIZE=$(stat -c%s "${BACKUP_DIR}/${BACKUP_FILE}" 2>/dev/null || stat -f%z "${BACKUP_DIR}/${BACKUP_FILE}" 2>/dev/null || echo "0")
 echo " -> Backup created: $BACKUP_FILE ($FILE_SIZE bytes)"
 if [ "$FILE_SIZE" -lt 1024 ]; then
 echo " -> WARNING: Backup file suspiciously small ($FILE_SIZE bytes)"
 ALL_PASSED=false
 fi
else
 echo " -> pg_dump FAILED"
 ALL_PASSED=false
fi

# ── 3. Integrity check ─────────────────────────────────────────────────
echo ""
echo "[3/6] Verifying dump integrity (pg_restore --list)..."
if docker cp "${BACKUP_DIR}/${BACKUP_FILE}" "${CONTAINER_NAME}:/tmp/${BACKUP_FILE}" && \
 TOC_OUTPUT=$(docker exec "$CONTAINER_NAME" pg_restore --list "/tmp/$BACKUP_FILE" 2>&1); then
 TOC_LINES=$(echo "$TOC_OUTPUT" | wc -l)
 echo " -> TOC valid: $TOC_LINES entries"
else
 echo " -> Integrity check FAILED"
 ALL_PASSED=false
fi

# ── 4. Restore to disposable database ──────────────────────────────────
echo ""
echo "[4/6] Restoring to disposable database '$VERIFY_DB'..."
if docker exec "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS $VERIFY_DB;" 2>/dev/null && \
 docker exec "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -c "CREATE DATABASE $VERIFY_DB OWNER $DB_USER;"; then
 docker exec "$CONTAINER_NAME" pg_restore -U "$DB_USER" -d "$VERIFY_DB" --no-owner --no-acl "/tmp/$BACKUP_FILE" 2>&1 || true
 docker exec "$CONTAINER_NAME" psql -U "$DB_USER" -d "$VERIFY_DB" -c "ANALYZE;" 2>/dev/null || true
 echo " -> Restore complete"
else
 echo " -> Restore FAILED"
 ALL_PASSED=false
fi

# ── 5. Row count comparison ────────────────────────────────────
echo ""
echo "[5/6] Comparing table row counts..."
COUNT_QUERY="SELECT schemaname || '.' || relname || '|' || n_live_tup FROM pg_stat_user_tables WHERE schemaname = 'public' ORDER BY relname;"

ORIG_COUNTS=$(pg_sql "$COUNT_QUERY" "$DB_NAME" 2>/dev/null || echo "")
VERIFY_COUNTS=$(pg_sql "$COUNT_QUERY" "$VERIFY_DB" 2>/dev/null || echo "")

if [ -z "$ORIG_COUNTS" ]; then
 echo " -> Row count comparison FAILED: No tables found in source database '$DB_NAME'"
 ALL_PASSED=false
elif [ "$ORIG_COUNTS" = "$VERIFY_COUNTS" ]; then
 TABLE_COUNT=$(echo "$ORIG_COUNTS" | grep -c '|' || echo "0")
 echo " -> All table row counts match ($TABLE_COUNT tables)"
else
 echo " -> Row count comparison FAILED (see diff)"
 diff <(echo "$ORIG_COUNTS") <(echo "$VERIFY_COUNTS") || true
 ALL_PASSED=false
fi

# ── 6. Flyway schema version parity ────────────────────────────────────
echo ""
echo "[6/6] Checking Flyway schema version parity..."
FLYWAY_QUERY="SELECT version || '|' || description || '|' || success FROM flyway_schema_history ORDER BY installed_rank;"

ORIG_FLYWAY=$(pg_sql "$FLYWAY_QUERY" "$DB_NAME" 2>/dev/null || echo "")
VERIFY_FLYWAY=$(pg_sql "$FLYWAY_QUERY" "$VERIFY_DB" 2>/dev/null || echo "")

if [ -z "$ORIG_FLYWAY" ]; then
 echo " -> Flyway check FAILED: No migrations found in flyway_schema_history"
 ALL_PASSED=false
elif [ "$ORIG_FLYWAY" = "$VERIFY_FLYWAY" ]; then
 VERSION_COUNT=$(echo "$ORIG_FLYWAY" | grep -c '|' || echo "0")
 echo " -> Flyway history matches ($VERSION_COUNT migrations)"
else
 echo " -> Flyway schema history MISMATCH"
 ALL_PASSED=false
fi

# ── Cleanup ─────────────────────────────────────────────────────────────
echo ""
echo "[Cleanup] Dropping disposable database '$VERIFY_DB'..."
docker exec "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS $VERIFY_DB;" 2>/dev/null || true
docker exec "$CONTAINER_NAME" sh -c "rm -f /tmp/$BACKUP_FILE" 2>/dev/null || true
echo " -> Cleanup complete"

# ── Result ──────────────────────────────────────────────────────────────
echo ""
echo "=========================================="
if $ALL_PASSED; then
 echo " BACKUP & RESTORE PROOF: PASSED"
 echo " Backup file: ${BACKUP_DIR}/${BACKUP_FILE}"
 exit 0
else
 echo " BACKUP & RESTORE PROOF: FAILED"
 exit 1
fi

