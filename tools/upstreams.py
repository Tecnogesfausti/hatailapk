#!/usr/bin/env python3
"""Read, obtain or verify pinned source checkouts without overwriting local work."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def git(*args, cwd=None):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["list", "bootstrap", "verify"])
    args = parser.parse_args()
    lock = json.loads((ROOT / "upstream.lock.json").read_text())
    for name, source in lock["sources"].items():
        if not re.fullmatch(r"[a-z-]+", name) or not re.fullmatch(r"[0-9a-f]{40}", source["commit"]):
            raise ValueError("Invalid source name or commit in upstream.lock.json")
        if args.command == "list":
            print(f"{name}: {source['commit']} ({source['url']})")
            continue
        path = ROOT / "upstream" / name
        if not path.exists() and args.command == "bootstrap":
            path.parent.mkdir(exist_ok=True)
            subprocess.run(["git", "clone", "--no-checkout", "--filter=blob:none",
                            source["url"], str(path)], check=True)
            subprocess.run(["git", "-C", str(path), "checkout", "--detach", source["commit"]], check=True)
        if not (path / ".git").exists():
            raise ValueError(f"Missing checkout: {path}; run bootstrap with network access")
        if git("rev-parse", "--show-toplevel", cwd=path) != str(path):
            raise ValueError(f"Not an independent source checkout: {path}")
        if git("remote", "get-url", "origin", cwd=path) != source["url"]:
            raise ValueError(f"Unexpected origin: {path}")
        if git("status", "--porcelain", cwd=path):
            raise ValueError(f"Local changes in {path}; preserve and review them before updating")
        if git("rev-parse", "HEAD", cwd=path) != source["commit"]:
            raise ValueError(f"Pinned revision mismatch: {path}; no checkout was forced")
        print(f"OK {name}: {source['commit']}")
    if args.command != "list":
        go_mod = (ROOT / "upstream/tailscale-android/go.mod").read_text()
        if not re.search(r"^\s*(?:require\s+)?tailscale\.com\s+" +
                         re.escape(lock["sources"]["tailscale"]["moduleVersion"]) + r"\s*$", go_mod, re.M):
            raise ValueError("Tailscale engine pin differs from the Android client's go.mod")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print(f"Upstream verification failed: {exc}", file=sys.stderr)
        sys.exit(1)
