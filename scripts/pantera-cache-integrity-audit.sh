#!/usr/bin/env bash
#
# pantera-cache-integrity-audit.sh
#
# WI-07 §9.5 admin tool: scan a file-backed proxy cache root for primary /
# sidecar pairs whose cached digest sidecar disagrees with the re-computed
# digest of the primary bytes. These are the pairs responsible for the
# production Maven ChecksumFailureException symptom on oss-parent-58.pom
# and similar artifacts.
#
# Thin wrapper around com.auto1.pantera.tools.CacheIntegrityAudit. Forwards
# every argument verbatim.
#
# Usage:
#     scripts/pantera-cache-integrity-audit.sh --root <storage-dir> \
#         [--repo <name>] [--dry-run | --fix] [--verbose]
#
# Exit codes (forwarded from the Java tool):
#     0 = clean (or --fix evicted all mismatches)
#     1 = mismatches detected in dry-run mode
#     2 = CLI usage error
#
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "${SCRIPT_DIR}/.." && pwd )"

# Resolve the classpath in priority order:
#   1. PANTERA_CLASSPATH env var (for production deployments).
#   2. a shaded / uber jar at pantera-main/target if one exists.
#   3. the Maven-built classpath via mvn dependency:build-classpath.
CP="${PANTERA_CLASSPATH:-}"

if [[ -z "${CP}" ]]; then
    for candidate in \
        "${REPO_ROOT}/pantera-main/target/pantera-main-jar-with-dependencies.jar" \
        "${REPO_ROOT}/pantera-main/target/pantera-main.jar"
    do
        if [[ -f "${candidate}" ]]; then
            CP="${candidate}"
            break
        fi
    done
fi

if [[ -z "${CP}" ]]; then
    if [[ ! -f "${REPO_ROOT}/pantera-main/target/pantera-main-2.1.3.jar" ]]; then
        cat >&2 <<EOF
error: pantera-main jar not found. Build with:

    mvn -pl pantera-main -am install -DskipTests -Dmaven.docker.plugin.skip=true

or set PANTERA_CLASSPATH=/path/to/classpath.
EOF
        exit 2
    fi
    # Compose runtime classpath from the module + its dependencies via Maven.
    CP_FILE="$( mktemp -t pantera-audit-cp.XXXXXX )"
    trap 'rm -f "${CP_FILE}"' EXIT
    (
        cd "${REPO_ROOT}"
        mvn -pl pantera-main -q \
            dependency:build-classpath \
            -Dmdep.outputFile="${CP_FILE}" \
            -DincludeScope=runtime >/dev/null
    )
    CP="${REPO_ROOT}/pantera-main/target/pantera-main-2.1.3.jar:$( cat "${CP_FILE}" )"
fi

JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
exec "${JAVA}" -cp "${CP}" \
    com.auto1.pantera.tools.CacheIntegrityAudit \
    "$@"
