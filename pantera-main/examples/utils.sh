set -e

function die {
  printf "FATAL: %s\n" "$1"
  exit 1
}

function require_env {
  local name="$1"
  local val=$(eval "echo \${$name}")
  if [[ -z "$val" ]]; then
    die "${name} env should be set"
  fi
}

require_env basedir

# set debug on CI builds
if [[ -n "$CI" ]]; then
  export DEBUG=true
fi

function log_debug {
  if [[ -n "$DEBUG" ]]; then
    printf "DEBUG: %s\n" "$1"
  fi
}

function assert {
  [[ "$1" -ne "$2" ]] && die "assertion failed: ${1} != ${2}"
}

if [[ -n "$DEBUG" ]]; then
  [[ -z "$DEBUG_NOX" ]] && set -x
  log_debug "debug enabled"
fi

function start_pantera {
  local image="$1"
  if [[ -z "$image" ]]; then
    image=$PANTERA_IMAGE
  fi
  if [[ -z "$image" ]]; then
    image="pantera/pantera:1.0-SNAPSHOT"
  fi
  local port="$2"
  if [[ -z "$port" ]]; then
    port=8080
  fi
  log_debug "using image: '${image}'"
  log_debug "using port:  '${port}'"
  [[ -z "$image" || -z "$port" ]] && die "invalid image or port params"
  stop_pantera
  docker run --rm --detach --name pantera \
    -v "${basedir}/../pantera.yml:/etc/pantera/pantera.yml" \
    -v "${basedir}/cfg:/var/pantera/cfg" \
    -v "${basedir}/data:/var/pantera/data" \
    -p "${port}:8080" "$image"
  log_debug "pantera started"
  # stop pantera docker container on script exit
  if [[ -z "$PANTERA_NOSTOP" ]]; then
    trap stop_pantera EXIT
  fi
}

function stop_pantera {
  local container=$(docker ps --filter name=pantera -q 2> /dev/null)
  if [[ -n "$container" ]]; then
    log_debug "stopping pantera container ${container}"
    docker stop "$container" || echo "failed to stop"
  fi
}

