FROM bacula-base:trixie
RUN DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends bacula-common-mysql bacula-director-mysql bacula-director bacula-console && apt clean
EXPOSE 9101/tcp
VOLUME ["/etc/bacula"]
ENTRYPOINT ["bacula-dir"]
CMD ["-f", "-c", "/etc/bacula/bacula-dir.conf"]
