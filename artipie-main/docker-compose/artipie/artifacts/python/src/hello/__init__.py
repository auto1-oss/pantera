__all__ = ["greet"]

__version__ = "0.1.0"

def greet(name: str) -> str:
    """Return a greeting for the given name."""
    if not name:
        name = "World"
    return f"Hello, {name}!"
