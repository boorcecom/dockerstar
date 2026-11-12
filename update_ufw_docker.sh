#!/bin/sh
sudo ufw-docker allow publichttpd 443/tcp
sudo ufw-docker allow publichttpd 80/tcp
sudo ufw-docker allow httpd 443/tcp
sudo ufw-docker allow httpd 80/tcp
sudo ufw-docker allow omniktcp 8989/tcp
