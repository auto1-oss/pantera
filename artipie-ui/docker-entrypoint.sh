#!/bin/sh
set -e

# Apply defaults for unset environment variables
export API_BASE_URL="${API_BASE_URL:-/api/v1}"
export GRAFANA_URL="${GRAFANA_URL:-}"
export APP_TITLE="${APP_TITLE:-Artipie}"
export DEFAULT_PAGE_SIZE="${DEFAULT_PAGE_SIZE:-20}"

# Generate config.json from template with environment variable values
envsubst < /usr/share/nginx/html/config.json.template \
         > /usr/share/nginx/html/config.json

exec "$@"
