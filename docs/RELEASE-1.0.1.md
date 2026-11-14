# FOROUZ 1.0.1

First public FOROUZ release, with a permanent signature and independent package identity.

## Included

- FOROUZ interface and branding, Xray and AmneziaWG support.
- Optional Laboratory test sweeps/history and active connection monitoring.
- Manual Overview IP observation and own-repository APK update discovery.
- Fix: Xray IP observation uses the active daemon's admitted local SOCKS listener instead of binding the excluded app UID to TUN.
- Xray DNS evidence is explicitly unknown; proxy egress is not presented as an all-app DNS leak test.
- Internal main-state broadcasts are restricted to this app's UID.

## Download

For most recent ARM64 phones choose **FOROUZ_1.0.1-playstore-release_arm64-v8a.apk**. Other ABIs and universal builds are attached, with a separate Fdroid distribution. Verify files against **SHA256SUMS.txt**. Public 1.0.0 can be upgraded in place; earlier experimental packages require backup transfer.

APK certificate SHA-256: `ed606e4fdaaa98f988d6dcf38dabf263672743711e2f5dd5f57b7cbddc2930f4`.

## Evidence and limits

356 JVM tests passed per distribution. Debug/release compilation and packaging passed; Lint has zero errors, with existing warnings. All ten signed release APKs were verified locally. Xray, HEV and Amnezia native library bytes match the prior tested 1.0.0 build; this release does not upgrade the native cores.

The user gave positive feedback after delivery. Full Android lifecycle/handover, real IPv6, cross-app leak testing and hosted update installation are not verified by that feedback. The new manual GitHub validation workflow has not been run on a hosted runner. See [privacy notes](https://github.com/AlamorNetwork/forouz/blob/master/PRIVACY.md) and the public roadmap.

فروز۱٫۰٫۱: ظاهر جدید، Xray و امنزیا، آزمایشگاه اختیاری و اصلاح مسیر سنجش IP. برای بیشتر گوشی‌ها فایل arm64-v8a را بگیر. وضعیت نشت DNS در مسیر Xray فعلاً نامشخص است؛ این نسخه ادعای محافظت قطعی همهٔ برنامه‌ها ندارد.
