# Build : s'exécute sur la machine cible (aarch64) ou en cross-compilation (--platform).
FROM golang:1.23 AS build
WORKDIR /src
COPY go.mod ./
COPY *.go stations.json ./
COPY web ./web
# Les tests font partie du build : un code cassé ne produit pas d'image.
RUN go vet ./... && CGO_ENABLED=0 go test ./...
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /carbu . && mkdir /data

# Image finale : un seul binaire statique (fuseaux horaires, stations et site web embarqués).
FROM scratch
COPY --from=build /carbu /carbu
# Certificats racine : indispensables aux appels HTTPS sortants (open data, carbu.com).
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# /data : dernier état connu (un fichier, pas d'historique). Un volume nommé hérite de ces droits.
COPY --from=build --chown=65532:65532 /data /data
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 CMD ["/carbu", "-healthcheck"]
ENTRYPOINT ["/carbu"]
