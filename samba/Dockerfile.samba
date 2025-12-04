FROM debian:trixie-slim
RUN DEBIAN_FRONTEND=noninteractive apt update && DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends sudo samba-ad-dc krb5-user smbclient winbind samba-ad-provision libpam-krb5 tdb-tools attr acl ldb-tools libnss-winbind libpam-krb5 dnsutils && apt autoclean && apt clean
EXPOSE 42 53 53/udp 88 88/udp 135 137-138/udp 139 389 389/udp 445 464 464/udp 636 3268-3269 49152-65535

ENTRYPOINT ["/usr/sbin/samba"]
CMD ["-i"]
