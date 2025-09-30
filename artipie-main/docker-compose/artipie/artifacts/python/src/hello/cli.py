import argparse
from . import greet, __version__

def main() -> None:
    parser = argparse.ArgumentParser(description="Say hello from python.")
    parser.add_argument("--name", default="World", help="Name to greet")
    parser.add_argument("--version", action="store_true", help="Show version and exit")
    args = parser.parse_args()

    if args.version:
        print(__version__)
        return

    print(greet(args.name))

if __name__ == "__main__":
    main()
