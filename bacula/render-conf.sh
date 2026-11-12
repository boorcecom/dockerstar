#!/bin/sh
# Génère les fichiers de configuration Bacula à partir des *.in et de .env
# Usage: ./render-conf.sh [répertoire_destination]   (défaut: /opt/bacula/etc/bacula)
set -eu
cd "$(dirname "$0")"
DEST="${1:-/opt/bacula/etc/bacula}"
set -a; . ./.env; set +a
VARS='${BACULA_DIR_CONSOLE_PASSWORD} ${BACULA_DIR_MON_PASSWORD} ${BACULA_SD_PASSWORD} ${BACULA_SD_MON_PASSWORD} ${BACULA_FD_PASSWORD} ${BACULA_DB_PASSWORD}'
for f in etc/bacula/*.in; do
    out="$DEST/$(basename "${f%.in}")"
    envsubst "$VARS" < "$f" > "$out"
    chmod 640 "$out"
    echo "écrit: $out"
done
