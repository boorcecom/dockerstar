#!/bin/sh

cd `dirname $0`
docker-compose up -d --build --force-recreate
docker image prune -f
cd -
