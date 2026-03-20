#!/bin/bash -ex
docker push pantera/conan-tests:1.0
docker push pantera/conda-tests:1.0
docker push pantera/deb-tests:1.0
docker push pantera/docker-tests:1.0
docker push pantera/file-tests:1.0
docker push pantera/helm-tests:1.0
docker push pantera/maven-tests:1.0
docker push pantera/pypi-tests:1.0
docker push pantera/rpm-tests-fedora:1.0
docker push pantera/rpm-tests-ubi:1.0
