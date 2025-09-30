# ayd-hello

A minimal Python package you can publish to PyPI. It exposes a console script `hello`.

## Usage

```bash
hello --name Ayd
```

## Building and publishing
```bash
#Install build and twine
python -m pip install --upgrade pip
python -m pip install twine build pytest

#Run tests
python -m pip install -e .
pytest -q

#Build artifacts
python -m build

#Verify metadata
twine check dist/*

#Publish to Artipie
twine upload --repository-url http://localhost:8081/py_local -u artipie -p artipie dist/*
```