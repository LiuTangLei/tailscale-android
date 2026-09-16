#!/usr/bin/env python3
"""Print Android build stamps from the exact published replacement module.

A corrected same-name release must not stamp the old cached Git tag object as
its core source. This build uses an immutable pseudo-version and checks its
module origin before deriving the displayed and embedded version strings.
"""
from __future__ import annotations
import json
import os
import shlex
import subprocess

CORE = "github.com/LiuTangLei/tailscale"
VERSION = "v1.102.5-0.20260916181858-1f00235ed2ce"
COMMIT = "1f00235ed2ceaa2231755a2d2d3677c2d42438c6"

def command(*argv: str) -> str:
    return subprocess.check_output(argv, text=True, env={**os.environ, "GOWORK": "off"}).strip()

module = json.loads(command("./tool/go", "list", "-m", "-json", "tailscale.com"))
replacement = module.get("Replace", {})
if replacement.get("Path") != CORE or replacement.get("Version") != VERSION or not replacement.get("Sum"):
    raise SystemExit("Android release requires the checksum-verified immutable QUIC core module")
source = command("git", "rev-parse", "HEAD")
dirty = bool(command("git", "status", "--porcelain"))
extra = source + ("-dirty" if dirty else "")
long_version = "1.102.4-t" + COMMIT[:9] + "-g" + source[:9] + ("-dirty" if dirty else "")
values = {
    "VERSION_SHORT": "1.102.4",
    "VERSION_LONG": long_version,
    "VERSION_GIT_HASH": COMMIT,
    "VERSION_EXTRA_HASH": extra,
}
for line in command("./tool/go", "run", "tailscale.com/cmd/mkversion").splitlines():
    key, separator, value = line.partition("=")
    print(key + "=" + shlex.quote(values[key]) if key in values else line)
