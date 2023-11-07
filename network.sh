#!/bin/sh

sudo ip link add cnt-bridge link enp0s3 type macvlan  mode bridge
sudo ip addr add 192.168.0.249/32 dev cnt-bridge
sudo ip link set cnt-bridge up
sudo ip route add 192.168.1.240/28 dev cnt-bridge

docker network create  --ipv6 -d macvlan \
  --subnet=192.168.0.0/24 \
  --gateway=192.168.0.254 \
  --subnet=2a01:e0a:809:d140::/60 \
  --ip-range 192.168.0.240/28 \
  --aux-address 'host=192.168.0.249' \
  -o parent=enp0s3 net_local
