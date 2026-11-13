#!/usr/bin/env bash
# 서버(~/cogom)에서 실행되는 배포 스크립트.
#   ./deploy.sh <이미지 참조>     예) ./deploy.sh ghcr.io/owner/repo:<commit sha>
#
# 새 이미지를 받아 app 컨테이너를 교체하고 헬스체크 통과를 기다린다.
# 실패하면 직전에 돌던 이미지로 되돌린다. 같은 방식으로 수동 롤백에도 쓸 수 있다.
set -Eeuo pipefail

cd "$(dirname "$(readlink -f "$0")")"

NEW_IMAGE="${1:?사용법: ./deploy.sh <이미지 참조>}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-180}"

[ -f .env ] || { echo "오류: $(pwd)/.env 가 없습니다." >&2; exit 1; }

# .env 의 APP_IMAGE 를 바꿔 두면 이후 수동 `docker compose up -d` 도 같은 이미지를 쓴다.
set_image() {
  if grep -q '^APP_IMAGE=' .env; then
    sed -i "s|^APP_IMAGE=.*|APP_IMAGE=$1|" .env
  else
    printf 'APP_IMAGE=%s\n' "$1" >> .env
  fi
}

up() {
  docker compose up -d --no-build --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT"
}

PREV_IMAGE="$(docker inspect --format '{{.Config.Image}}' cogom-api 2>/dev/null || true)"

echo "==> 배포 이미지: $NEW_IMAGE"
echo "==> 직전 이미지: ${PREV_IMAGE:-없음}"

set_image "$NEW_IMAGE"
docker compose pull --quiet app

if up; then
  echo "==> 헬스체크 통과"
else
  echo "==> 헬스체크 실패. app 로그:" >&2
  docker compose logs --tail 80 app >&2 || true
  if [ -n "$PREV_IMAGE" ] && [ "$PREV_IMAGE" != "$NEW_IMAGE" ]; then
    echo "==> 직전 이미지로 롤백: $PREV_IMAGE" >&2
    set_image "$PREV_IMAGE"
    up || echo "==> 롤백 기동도 실패했습니다. 서버에서 직접 확인하세요." >&2
  fi
  exit 1
fi

docker compose ps
# 일주일 넘게 쓰이지 않은 이전 이미지를 정리한다. 볼륨은 건드리지 않는다.
docker image prune -af --filter "until=168h" >/dev/null || true
