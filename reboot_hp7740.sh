#!/bin/sh
cd "$(dirname "$0")" || exit 1
set -a; . ./.env; set +a

curl -v --insecure -u "${HP_USER}:${HP_PASSWORD}" -X PUT "https://${HP_HOST}/ProductActions/PowerCycle"
