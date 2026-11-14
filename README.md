# FOROUZ / فروز

An independent Android network client derived from [v2rayNG](https://github.com/2dust/v2rayNG), with Xray and AmneziaWG support, a redesigned interface and an optional connection laboratory.

[Download FOROUZ 1.0.1](https://github.com/AlamorNetwork/forouz/releases/tag/v1.0.1) · [Privacy notes](PRIVACY.md) · [Roadmap](docs/ROADMAP.md) · [Release notes](docs/RELEASE-1.0.1.md)

## Install

Use `FOROUZ_1.0.1-playstore-release_arm64-v8a.apk` for most recent ARM64 phones. Separate APKs are available for armeabi-v7a, x86, x86_64 and universal, in Playstore and Fdroid distributions. Bring your own compatible profiles.

The public package is `ir.alamornetwork.forouz` (Fdroid: `.fdroid`). Public 1.0.1 upgrades public 1.0.0 using the same permanent signature. Earlier experimental packages are separate installations; transfer profiles with a backup.

Verify downloads against the release's `SHA256SUMS.txt`. The permanent APK certificate SHA-256 is:

```
ed606e4fdaaa98f988d6dcf38dabf263672743711e2f5dd5f57b7cbddc2930f4
```

## Features

- Xray profile import, subscriptions, routing and transport configuration inherited from the pinned upstream base.
- Independent AmneziaWG service, native configuration import/export and supported managed Amnezia Free links with human captcha handling.
- FOROUZ dark/light interface, active versus selected profile state, live Xray traffic and notification controls.
- Optional Laboratory: transport grouping, bounded sequential tests, explicit 10 MB download testing and persistent history. Optional active health monitoring includes limited Xray recovery.
- Manual Overview IP observation and an update checker using this repository's compatible releases.

## Privacy monitor scope

Xray IP checks use the active core's admitted local SOCKS listener, because the app itself is excluded from the Xray TUN. This observes configured Xray egress, including its routing rules. **Xray DNS leak status is unknown**; this check does not measure Android's DNS path for every app. Authenticated/unsupported custom listeners, proxy-only and root modes are outside this monitor's supported route.

Amnezia checks bind to the owned VPN network when this app is included. A disconnected direct-network reference requires explicit confirmation. IP and resolver observations remain in memory. Resolver overlap is only a possible bypass signal; no overlap does not guarantee absence of leaks. See [privacy notes](PRIVACY.md).

## Build from source

The source snapshot is based on upstream app commit `57907cf354d9769200ed4717b4430d605abe0952`. Native versions and archive digests are in [NATIVE-INPUTS.json](NATIVE-INPUTS.json); Amnezia has additional [per-ABI provenance](V2rayNG/amnezia/NATIVE-PROVENANCE.json). Native binaries are restored or built, not checked into Git.

Initialize submodules recursively. Install the JDK, Android SDK and NDK declared in `.github/workflows/build.yml`; set `ANDROID_HOME` and `NDK_HOME` accordingly. Then, from the repository root:

```sh
git submodule update --init --recursive
bash compile-hevtun.sh
mkdir -p V2rayNG/app/libs
cp -R libs/. V2rayNG/app/libs/
python3 tools/prepare-xray.py
python3 tools/prepare-amnezia.py
cd V2rayNG
./gradlew --no-daemon --max-workers=1 :app:testPlaystoreDebugUnitTest :app:compilePlaystoreDebugKotlin :app:lintPlaystoreDebug :app:assemblePlaystoreDebug
```

Native restoration respects system/environment proxies. Amnezia additionally accepts `--proxy`; both scripts accept an existing official archive for offline verification. SHA-256 checks run before output is accepted. A clean build requires access to the pinned upstream artifacts and build dependencies.

Release signing reads `FOROUZ_KEYSTORE_PATH` and `FOROUZ_KEYSTORE_PASSWORD` from the build environment with alias `forouz-release`. The private signing material is never part of the repository. The manual GitHub workflow produces debug validation artifacts only; the 1.0.1 release APKs were built and verified locally. Byte-for-byte reproducibility of a fresh HEV rebuild has not been established.

## Validation and limitations

For 1.0.1, 356 JVM tests passed per distribution, debug/release compilation and packaging passed, and Lint reported zero errors (warnings remain). All ten release APKs passed signature and ZIP alignment checks. The user gave positive feedback after delivery; this is not a complete Android/device or leak-protection audit. Hosted CI, full lifecycle/network handover, real IPv6 and update-install coverage remain separate checks.

## License and upstream

FOROUZ retains the [GNU GPL v3 license](LICENSE) and upstream attribution. Credit to [v2rayNG](https://github.com/2dust/v2rayNG), [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), [HEV](https://github.com/heiher/hev-socks5-tunnel) and [AmneziaWG Android](https://github.com/amnezia-vpn/amneziawg-android). The copied Amnezia Java adapter retains its copyright/SPDX headers and [module license](V2rayNG/amnezia/LICENSE). Review the pinned upstream projects for their native-source licenses.

## فارسی

فروز یک کلاینت مستقل بر پایهٔ v2rayNG با پشتیبانی Xray و امنزیا، ظاهر تازه و آزمایشگاه اختیاری است. نسخهٔ۱٫۰٫۱ را از بخش Releases بگیر. مشاهدهٔ IP در Xray از پروکسی خود هسته انجام می‌شود؛ وضعیت نشت DNS آن فعلاً نامشخص است و نتیجه تضمین امنیت همهٔ برنامه‌ها نیست. دانلود۱۰مگابایتی آزمایشگاه فقط با فعال‌سازی و اعلام مصرف انجام می‌شود.
