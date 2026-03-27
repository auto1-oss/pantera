#!/bin/sh
set -e

# Apply defaults for unset environment variables
export API_BASE_URL="${API_BASE_URL:-/api/v1}"
export GRAFANA_URL="${GRAFANA_URL:-}"
export APP_TITLE="${APP_TITLE:-Pantera}"
export DEFAULT_PAGE_SIZE="${DEFAULT_PAGE_SIZE:-20}"
export APM_ENABLED="${APM_ENABLED:-false}"
export APM_SERVER_URL="${APM_SERVER_URL:-}"
export APM_SERVICE_NAME="${APM_SERVICE_NAME:-pantera-ui}"
export APM_ENVIRONMENT="${APM_ENVIRONMENT:-production}"

# Generate config.json from template with environment variable values
envsubst < /usr/share/nginx/html/config.json.template \
         > /usr/share/nginx/html/config.json

# Generate nginx config — include API proxy block only when API_UPSTREAM is set
if [ -n "${API_UPSTREAM}" ]; then
    PROXY_BLOCK="location /api/ {
        proxy_pass http://${API_UPSTREAM}/api/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 10s;
    }"
else
    PROXY_BLOCK=""
fi
sed "s|# API_PROXY_BLOCK|${PROXY_BLOCK}|" /etc/nginx/conf.d/default.conf.template \
    > /etc/nginx/conf.d/default.conf

exec "$@"
