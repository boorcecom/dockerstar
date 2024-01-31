FROM debian:bullseye-slim
RUN apt update
RUN apt upgrade -y
RUN DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends bacula-common
RUN mkdir /run/bacula
