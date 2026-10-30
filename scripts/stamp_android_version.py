#!/usr/bin/env python3
"""Print Android build stamps from the checksum-verified published fork module."""
from __future__ import annotations
import json
import os
import shlex
import subprocess

CORE = "github.com/LiuTangLei/tailscale"
VERSION = "v1.104.4"
COMMIT = "f58b730c8c1eb30c00127e6a2ebe4fa0540d29f1"

def command(*argv: str) -> str:
    return subprocess.check_output(argv, text=True, env={**os.environ, "GOWORK": "off"}).strip()

module = json.loads(command("./tool/go", "list", "-m", "-json", "tailscale.com"))
replacement = module.get("Replace", {})
if replacement.get("Path") != CORE or replacement.get("Version") != VERSION or not replacement.get("Sum"):
    raise SystemExit("Android release requires the checksum-verified immutable QUIC core module")
source = command("git", "rev-parse", "HEAD")
dirty = bool(command("git", "status", "--porcelain"))
extra = source + ("-dirty" if dirty else "")
long_version = "1.104.4-t" + COMMIT[:9] + "-g" + source[:9] + ("-dirty" if dirty else "")
values = {
    "VERSION_SHORT": "1.104.4",
    "VERSION_LONG": long_version,
    "VERSION_GIT_HASH": COMMIT,
    "VERSION_EXTRA_HASH": extra,
}
for line in command("./tool/go", "run", "tailscale.com/cmd/mkversion").splitlines():
    key, separator, value = line.partition("=")
    print(key + "=" + shlex.quote(values[key]) if key in values else line)
