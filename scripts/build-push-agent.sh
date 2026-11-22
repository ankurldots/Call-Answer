#!/usr/bin/env bash
# Build the :agent container image and push it to ECR.
# Credentials come from the AWS CLI profile; nothing is embedded here.
#
# Usage: scripts/build-push-agent.sh [-p profile] [-r region] [-n repo] [-t tag] [--build-only]
set -euo pipefail

PROFILE="ldots"
REGION="ap-northeast-1"
REPO="call-answer-agent"
TAG=""
BUILD_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    -p|--profile) PROFILE="$2"; shift 2 ;;
    -r|--region) REGION="$2"; shift 2 ;;
    -n|--repo) REPO="$2"; shift 2 ;;
    -t|--tag) TAG="$2"; shift 2 ;;
    --build-only) BUILD_ONLY=1; shift ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -z "$TAG" ]; then
  TAG="$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)"
fi

./gradlew :agent:installDist

# Fargate x86_64 by default; override with DOCKER_PLATFORM=linux/arm64 for a Graviton task.
PLATFORM="${DOCKER_PLATFORM:-linux/amd64}"
LOCAL_IMAGE="${REPO}:${TAG}"
docker build --platform "$PLATFORM" -f agent/Dockerfile -t "$LOCAL_IMAGE" .

if [ "$BUILD_ONLY" -eq 1 ]; then
  echo "Built ${LOCAL_IMAGE} (not pushed)"
  exit 0
fi

ACCOUNT="$(aws sts get-caller-identity --profile "$PROFILE" --region "$REGION" --query Account --output text)"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
REMOTE_IMAGE="${REGISTRY}/${REPO}"

# The repository is created by the stack; fail clearly if it does not exist.
aws ecr describe-repositories --repository-names "$REPO" --profile "$PROFILE" --region "$REGION" >/dev/null

aws ecr get-login-password --profile "$PROFILE" --region "$REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"

docker tag "$LOCAL_IMAGE" "${REMOTE_IMAGE}:${TAG}"
docker tag "$LOCAL_IMAGE" "${REMOTE_IMAGE}:latest"
docker push "${REMOTE_IMAGE}:${TAG}"
docker push "${REMOTE_IMAGE}:latest"

echo "Pushed ${REMOTE_IMAGE}:${TAG}"
