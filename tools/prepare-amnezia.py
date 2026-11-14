"""Restore digest-pinned official AWG JNI inputs; never accepts unverified libraries."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import urllib.parse
import urllib.request
import zipfile

ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
MAX_BYTES = 32 * 1024 * 1024
URL = "https://github.com/amnezia-vpn/amneziawg-android/releases/download/v3.1.20260814/AmneziaWG-3.1.202060814.apk"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Verify an already downloaded official APK offline")
    parser.add_argument("--proxy", help="Optional HTTP(S) proxy; otherwise use system/environment settings")
    parser.add_argument("--output-dir", type=Path, help="Override JNI output directory")
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1] / "V2rayNG" / "amnezia"
    metadata = json.loads((root / "NATIVE-PROVENANCE.json").read_text(encoding="utf-8"))
    if set(metadata["native_sha256"]) != ABIS:
        raise ValueError("Unexpected native ABI set")
    if args.archive:
        with args.archive.open("rb") as source:
            archive = source.read(MAX_BYTES + 1)
    else:
        handlers = []
        if args.proxy:
            proxy = urllib.parse.urlsplit(args.proxy)
            if proxy.scheme not in ("http", "https") or not proxy.hostname or proxy.username or proxy.password:
                raise ValueError("Proxy must be an HTTP(S) address without embedded credentials")
            handlers.append(urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy}))
        with urllib.request.build_opener(*handlers).open(URL, timeout=120) as response:
            archive = response.read(MAX_BYTES + 1)
    if len(archive) > MAX_BYTES or hashlib.sha256(archive).hexdigest() != metadata["release_apk_sha256"]:
        raise ValueError("APK size or digest mismatch")
    verified = {}
    with zipfile.ZipFile(io.BytesIO(archive)) as apk:
        for abi, expected in metadata["native_sha256"].items():
            entry = apk.getinfo(f"lib/{abi}/libwg-go.so")
            if entry.file_size > 16 * 1024 * 1024:
                raise ValueError("Unexpected library size")
            data = apk.read(entry)
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError("Library digest mismatch")
            verified[abi] = data
    # Validate the entire archive before writing any ABI.
    output = args.output_dir or root / "src/main/jniLibs"
    for abi, data in verified.items():
        target = output / abi / "libwg-go.so"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print("Verified and prepared all four AWG native ABIs.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        raise SystemExit("AWG preparation failed: " + type(error).__name__) from None
