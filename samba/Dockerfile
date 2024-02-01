FROM debian:trixie-slim
RUN DEBIAN_FRONTEND=noninteractive apt update && DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends sudo samba-ad-dc krb5-user smbclient winbind bind9

ENTRYPOINT ["/usr/sbin/apachectl"]
CMD ["-D", "FOREGROUND"]
