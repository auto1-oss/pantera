#!/bin/bash

set -e
set -x

# Push a gem into pantera.
export GEM_HOST_API_KEY=$(echo -n "hello:world" | base64)
cd /test/sample-project
gem build sample-project.gemspec
gem push sample-project-1.0.0.gem --host http://pantera.pantera:8080/my-gem
cd ..

# Fetch the uploaded earlier gem from pantera.
gem fetch sample-project --source http://pantera.pantera:8080/my-gem
