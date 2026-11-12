#!/bin/sh

cd `dirname $0`
docker pull debian:trixie-slim
docker-compose up -d --build --force-recreate
docker image prune -f
cd -
