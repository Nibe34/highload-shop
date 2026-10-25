#!/bin/bash
# Entrypoint Read-Replica: при порожньому PGDATA робить pg_basebackup з Primary
# (-R створює standby.signal і primary_conninfo, -C -S — фізичний replication slot),
# далі запускає postgres у режимі hot standby, який безперервно програє WAL з Primary.
set -e
: "${PRIMARY_HOST:=postgres}"
: "${REPLICATION_USER:=replicator}"
: "${REPLICATION_PASSWORD:=replicator}"
: "${REPLICA_SLOT:=replica_1}"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "replica: waiting for primary $PRIMARY_HOST ..."
  until pg_isready -h "$PRIMARY_HOST" -U "$REPLICATION_USER" -q; do sleep 1; done
  echo "replica: pg_basebackup from $PRIMARY_HOST (slot $REPLICA_SLOT)"
  export PGPASSWORD="$REPLICATION_PASSWORD"
  # Роль replicator створює міграція Flyway при старті застосунку — повторюємо, доки вона не з'явиться.
  # Slot створюється лише якщо його ще немає (повторне розгортання репліки).
  until {
    slot_args="-C -S $REPLICA_SLOT"
    if psql -h "$PRIMARY_HOST" -U "$REPLICATION_USER" -d "replication=database dbname=postgres" -tAc \
         "SELECT 1 FROM pg_replication_slots WHERE slot_name='$REPLICA_SLOT'" 2>/dev/null | grep -q 1; then
      slot_args="-S $REPLICA_SLOT"
    fi
    pg_basebackup -h "$PRIMARY_HOST" -U "$REPLICATION_USER" -D "$PGDATA" -R -X stream $slot_args --checkpoint=fast
  }; do
    echo "replica: pg_basebackup failed, retry in 3 s"; rm -rf "${PGDATA:?}"/*; sleep 3
  done
  chmod 0700 "$PGDATA"
  chown -R postgres:postgres "$PGDATA"
fi
exec gosu postgres docker-entrypoint.sh postgres "$@"
