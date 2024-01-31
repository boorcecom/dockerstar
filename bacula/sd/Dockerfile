FROM bacula-base:trixie
RUN DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends bacula-sd
EXPOSE 9103/tcp
VOLUME ["/etc/bacula"]
ENTRYPOINT ["bacula-sd"]
CMD ["-f", "-c", "/etc/bacula/bacula-sd.conf"]
