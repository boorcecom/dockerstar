#!/bin/sh

cd "$(dirname "$0")" || exit 1
docker compose up -d --build --force-recreate
docker image prune -f
