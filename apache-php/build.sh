#!/bin/sh

cd `dirname $0`
docker pull php:apache
docker pull mariadb:latest
docker-compose up -d --build --force-recreate
docker image prune -f
sudo ufw-docker allow publichttpd 443/tcp
sudo ufw-docker allow publichttpd 80/tcp
sudo ufw-docker allow httpd 443/tcp
sudo ufw-docker allow httpd 80/tcp
cd -
