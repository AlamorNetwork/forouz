"""Restore the official digest-pinned Xray AAR recorded in NATIVE-INPUTS.json."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Verify a cached official AAR offline")
    parser.add_argument("--output", type=Path, help="Override output path")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    expected = json.loads((root / "NATIVE-INPUTS.json").read_text(encoding="utf-8"))["xray"]
    if args.archive:
        with args.archive.open("rb") as source:
            data = source.read(expected["size_bytes"] + 1)
    else:
        with urllib.request.urlopen(expected["url"], timeout=120) as response:
            data = response.read(expected["size_bytes"] + 1)
    if len(data) != expected["size_bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
        raise ValueError("AAR size or digest mismatch")
    target = args.output or root / "V2rayNG/app/libs/libv2ray.aar"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print("Verified and prepared the pinned Xray AAR.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        raise SystemExit("Xray preparation failed: " + type(error).__name__) from None
