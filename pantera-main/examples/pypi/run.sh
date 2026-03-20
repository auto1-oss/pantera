#!/bin/bash
set -x
set -e

# Build and upload python project to artipie.
cd sample-project
python3 -m pip install --user --upgrade setuptools wheel
python3 setup.py sdist bdist_wheel
python3 -m pip install --user --upgrade twine
python3 -m twine upload --repository-url http://pantera.pantera:8080/my-pypi \
  -u alice -p qwerty123 dist/*
cd ..

# Install earlier uploaded python package from pantera.
python3 -m pip install --trusted-host pantera.pantera \
  --index-url http://pantera.pantera:8080/my-pypi sample_project
