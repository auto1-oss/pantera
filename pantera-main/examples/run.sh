#!/bin/bash
set -eo pipefail
cd ${0%/*}
echo "running in $PWD"
workdir=$PWD

# environment variables:
#  - PANTERA_NOSTOP - don't stop docker containers
#       don't remove docker network on finish
#  - DEBUG - show debug messages
#  - CI - enable CI mode (debug and `set -x`)
#  - PANTERA_IMAGE - docker image name for pantera
#       (default pantera/pantera-tests:1.0-SNAPSHOT)

# print error message and exist with error code
function die {
  printf "FATAL: %s\n" "$1"
  exit 1
}

# set pidfile to prevent parallel runs
pidfile=/tmp/test-pantera.pid
if [[ -f $pidfile ]]; then
  pid=$(cat $pidfile)
  set +e
  ps -p $pid > /dev/null 2>&1
  [[ $? -eq 0 ]] || die "script is already running"
  set -e
fi
echo $$ > $pidfile
trap "rm -v $pidfile" EXIT

# set debug on CI builds
if [[ -n "$CI" ]]; then
  export DEBUG=true
fi

# print debug message if DEBUG mode enabled
function log_debug {
  if [[ -n "$DEBUG" ]]; then
    printf "DEBUG: %s\n" "$1"
  fi
}

# check if first param is equal to second or die
function assert {
  [[ "$1" -ne "$2" ]] && die "assertion failed: ${1} != ${2}"
}

if [[ -n "$DEBUG" ]]; then
  log_debug "debug enabled"
fi

# start pantera docker image. image name and port are optional
# parameters. register callback to stop image on exist.
function start_pantera {
  local image="$1"
  if [[ -z "$image" ]]; then
    image=$PANTERA_IMAGE
  fi
  if [[ -z "$image" ]]; then
    image="pantera/pantera-tests:1.0-SNAPSHOT"
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
    -v "$PWD/pantera.yml:/etc/pantera/pantera.yml" \
    -v "$PWD/.cfg:/var/pantera/cfg" \
    -e PANTERA_USER_NAME=alice \
    -e PANTERA_USER_PASS=qwerty123 \
    --mount source=pantera-data,destination=/var/pantera/data \
    --user 2020:2021 \
    --net=pantera \
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

# create docker network named `pantera` for containers communication
# register callback to remove it on script exit if no PANTERA_NOSTOP
# environment is set
function create_network {
  rm_network
  log_debug "creating pantera network"
  docker network create pantera
  if [[ -z "$PANTERA_NOSTOP" ]]; then
    trap rm_network EXIT
  fi
}

# remove `pantera` network if exist
function rm_network {
  local net=$(docker network ls -q --filter name=pantera)
  if [[ -n "${net}" ]]; then
    log_debug "removing pantera network"
    docker network rm $net
  fi
}

function create_volume {
  rm_volume
  log_debug "creating volume $(docker volume create pantera-data)"
  log_debug "fill out volume data"
  docker run --rm --name=pantera-volume-maker \
    -v "$PWD/.data:/data-src" \
    --mount source=pantera-data,destination=/data-dst \
    alpine:3.13 \
    /bin/sh -c 'addgroup -S -g 2020 pantera && adduser -S -g 2020 -u 2021 pantera && cp -r /data-src/* /data-dst && chown -R 2020:2021 /data-dst'
  if [[ -z "$PANTERA_NOSTOP" ]]; then
    trap rm_volume EXIT
  fi
}

# remove pantera data volume if exist
function rm_volume {
  local img=$(docker volume ls -q --filter name=pantera-data)
  if [[ -n "${img}" ]]; then
    log_debug "removing volume "
    docker volume rm ${img}
  fi
}

# run single smoke-test
function run_test {
  local name=$1
  log_debug "running smoke test $name"
  pushd "./${name}"
  docker build -t "test/${name}" .
  docker run --name="smoke-${name}" --rm \
    --net=pantera \
    -v /var/run/docker.sock:/var/run/docker.sock \
    "test/${name}" | tee -a "$workdir/out.log"
  if [[ "${PIPESTATUS[0]}" == "0" ]]; then
    echo "test ${name} - PASSED" | tee -a "$workdir/results.txt"
  else
    echo "test ${name} - FAILED" | tee -a "$workdir/results.txt"
  fi
  popd
}

create_network
create_volume
start_pantera

sleep 3 #sometimes pantera container needs extra time to load

if [[ -z "$1" ]]; then
#TODO: hexpm is removed from the list due to the issue: https://github.com/pantera/pantera/issues/1464
  declare -a tests=(binary debian docker go helm maven npm nuget php rpm conda pypi conan)
else
  declare -a tests=("$@")
fi

log_debug "tests: ${tests[@]}"

rm -fr "$workdir/out.log" "$workdir/results.txt"
touch "$workdir/out.log"

for t in "${tests[@]}"; do
  run_test $t || echo "test $t failed"
done

echo "all tests finished:"
cat "$workdir/results.txt"
r=0
grep "FAILED" "$workdir/results.txt" > /dev/null || r="$?"
if [ "$r" -eq 0 ] ; then
  rm -fv "$pidfile"
  echo "Pantera container logs:"
  container=$(docker ps --filter name=pantera -q 2> /dev/null)
  if [[ -n "$container" ]] ; then
    docker logs "$container" || echo "failed to log pantera"
  fi
  die "One or more tests failed"
else
  rm -fv "$pidfile"
  echo "SUCCESS"
fi

