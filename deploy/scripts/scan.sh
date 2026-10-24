#!/usr/bin/env bash
# Local security scans that need no account: gitleaks on the Git history and Trivy on the pinned
# images and the deployment configuration, both run from pinned containers. OWASP
# Dependency-Check and the SBOM run through Maven (make security). The first NVD download is slow
# without NVD_API_KEY; blank-nvd-key.sh drops the empty key Dependency-Check 13 would otherwise send.
source "$(dirname "$0")/lib.sh"

GITLEAKS_IMAGE="${GITLEAKS_IMAGE:-zricethezav/gitleaks:v8.30.1}"
TRIVY_IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.76.1}"

log "gitleaks: scanning the Git history"
docker run --rm -v "$ROOT_DIR:/repo:ro" "$GITLEAKS_IMAGE" git /repo --redact --no-banner

log "trivy: deployment misconfigurations"
docker run --rm -v "$DEPLOY_DIR:/deploy:ro" "$TRIVY_IMAGE" config --severity HIGH,CRITICAL --exit-code 1 /deploy

# shellcheck disable=SC1091
source "$DEPLOY_DIR/images.env"
for image in "$RABBITMQ_3_13" "$RABBITMQ_4_4" "$TOXIPROXY"; do
  log "trivy: $image"
  docker run --rm -v rmqv-trivy-cache:/root/.cache "$TRIVY_IMAGE" image --severity HIGH,CRITICAL \
    --ignore-unfixed --exit-code 0 "$image"
done
