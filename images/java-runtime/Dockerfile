# Base runtime image for the demo services. cloudbuild.yaml builds it and pushes it to
# Artifact Registry as java-runtime:<tag>; the service Dockerfiles use it as BASE_IMAGE.
#
# Distroless Java 21 on Debian 12: no shell, no package manager, runs as uid 65532 (nonroot).
ARG UPSTREAM_IMAGE=gcr.io/distroless/java21-debian12:nonroot
FROM ${UPSTREAM_IMAGE}

LABEL org.opencontainers.image.title="spiffe-demo java-runtime" \
      org.opencontainers.image.description="Distroless Java 21 base for the SPIFFE mTLS demo services" \
      org.opencontainers.image.source="https://github.com/aprassanna/kube-spiffe-demo"

WORKDIR /app
USER 65532:65532
