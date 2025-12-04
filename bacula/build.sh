#!/bin/sh

cd `dirname $0`
docker pull debian:trixie-slim
docker build -t bacula-base:trixie -f Dockerfile.base .
docker-compose up -d --build --force-recreate
docker image prune -f
cd -
