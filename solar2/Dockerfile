# Build : s'exécute sur la machine cible (aarch64) ou en cross-compilation (--platform).
FROM golang:1.23 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
COPY web ./web
# Les tests font partie du build : un code cassé ne produit pas d'image.
RUN go vet ./... && CGO_ENABLED=0 go test ./...
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /solar2 .

# Image finale : un seul binaire statique (fuseaux horaires et site web embarqués).
FROM scratch
COPY --from=build /solar2 /solar2
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 CMD ["/solar2", "-healthcheck"]
ENTRYPOINT ["/solar2"]
