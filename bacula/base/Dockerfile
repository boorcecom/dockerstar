FROM debian:trixie-slim
RUN DEBIAN_FRONTEND=noninteractive apt update && DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends bacula-common && apt clean
RUN mkdir /run/bacula
