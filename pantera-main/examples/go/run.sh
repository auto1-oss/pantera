#!/bin/bash

set -e
set -x

# Force go client to use Aritpie Go registry.
export GO111MODULE=on
export GOPROXY=http://pantera.pantera:8080/my-go
export GOSUMDB=off
export "GOINSECURE=pantera.pantera*"

# Install from Artipie Go registry.
go get -x golang.org/x/time
