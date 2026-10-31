#!/usr/bin/env python3
"""Register a voice for the shim's consent gate. Only your own voice, or someone who gave explicit consent.

  voicectl.py add --id don --wav ~/don.wav --own
  voicectl.py add --id mom --wav mom.wav --consent-from "Mom" --consent-note "Verbal, recorded 2026-10-30"
  voicectl.py list | remove --id mom
"""
import argparse
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

DATA = Path(os.environ.get("VSMS_DATA_DIR", Path.home() / ".local" / "share" / "victorsms-pc")) / "voices"


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("--id", required=True)
    a.add_argument("--wav", required=True)
    g = a.add_mutually_exclusive_group(required=True)
    g.add_argument("--own", action="store_true")
    g.add_argument("--consent-from")
    a.add_argument("--consent-note", default="")
    sub.add_parser("list")
    r = sub.add_parser("remove")
    r.add_argument("--id", required=True)
    ns = ap.parse_args(argv)
    if ns.cmd == "list":
        for d in sorted(DATA.glob("*/consent.json")):
            c = json.loads(d.read_text())
            print(d.parent.name, c.get("ownership"), c.get("consent_from", ""))
        return 0
    if not re.match(r"^[A-Za-z0-9_-]{1,64}$", ns.id):
        sys.exit("id must be 1-64 chars of A-Z a-z 0-9 _ -")
    d = DATA / ns.id
    if ns.cmd == "remove":
        shutil.rmtree(d, ignore_errors=True)
        return 0
    if ns.consent_from is not None and not (ns.consent_from.strip() and ns.consent_note.strip()):
        sys.exit("--consent-from and --consent-note (how/when they consented) are both required")
    if not Path(ns.wav).is_file():
        sys.exit("wav not found")
    ans = input("I declare this is my own voice or the person gave explicit consent [type YES]: ")
    if ans.strip() != "YES":
        sys.exit("not registered")
    d.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ns.wav, d / "reference.wav")
    c = {"consent": True, "ownership": "own" if ns.own else "consented",
         "consent_from": "" if ns.own else ns.consent_from.strip(), "consent_note": ns.consent_note.strip(),
         "recorded_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    (d / "consent.json").write_text(json.dumps(c, indent=2))
    print("registered", ns.id)
    return 0


if __name__ == "__main__":
    sys.exit(main())
