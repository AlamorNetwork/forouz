# Pinned AmneziaWG adapter inputs

Java config/crypto classes, NonNullForAll and JNI declarations are copied from
amnezia-vpn/amneziawg-android tag v3.1.20260814, commit
5c16489e2cd9ed3a0a7a27c7445bba5238132f86. Copyright and SPDX headers are preserved;
see LICENSE. One trailing-whitespace cleanup does not change the Java behavior. No upstream Android UI or upstream VpnService is included.

The four libwg-go.so inputs are extracted from that tag's official release APK.
NATIVE-PROVENANCE.json pins the release APK and each library digest. These are prebuilt
release inputs, not locally rebuilt Go code. The native module's Go dependency revision
is recorded by upstream tunnel/tools/libwg-go/go.mod at the pinned commit.

Run `python tools/prepare-amnezia.py` from the repository root to restore ignored JNI
inputs. System/environment proxy settings are respected; `--proxy` is optional.
Use `--archive` for an existing official APK and `--output-dir` for a validation directory. The build checks all four library digests before preBuild.
The published binary may attempt its upstream app-specific UAPI socket path; this adapter
uses the exported native handle API and does not depend on UAPI socket access.

The app owns VPN lifecycle and routes via AmneziaWgService. Raw .conf data stays in the
existing MMKV raw-profile store, and a non-secret canonical-config hash participates in
profile deduplication. Core settings are parsed by upstream's strict parser; unknown AWG
fields fail instead of silently stripping obfuscation.

Experimental scope: config-file/clipboard import, native tunnel start/stop/restart and
config-defined addresses, routes, DNS and included/excluded applications. Xray policy
chains, Xray-native delay probes, app-level Xray proxy/routing settings, native traffic UI,
automatic network recovery and in-app raw AWG editing are not implemented for AWG.
Android start, revoke, handover, permission, process death and actual connectivity must
be verified on a physical device before release. Successful native setup is not proof
of handshake or internet reachability.

## Managed Free keys

The vpn:// decoder accepts Qt qCompress + base64url or plain base64url JSON with a
1 MiB decompression bound. Managed Amnezia Free AWG import uses the official /v1/config
request envelope (RSA PKCS1 + AES-256-CBC), a new locally generated WG keypair and
an installation UUID stored through MmkvManager. No API token is retained after import.
The gateway public key is taken from official AmneziaVPN 5.0.3.0 arm64 APK, SHA256
2e47063f52dad3fc4c527f7def9d0f21ef75e45021e0c3f30bd4623cc0c83eac.
An unauthenticated empty request confirmed the production key by decrypting the
validation response. Correcting the endpoint and omitting blank optional country fields
produced an authenticated HTTP 200 with a config field in the local diagnostic;
decoding that successful response was not verified at that time. Later requests returned
provider configuration-quota (409) or no compatible worker (422) errors. The earlier general user success report was later clarified: on 4 November the user
said no working AWG configuration had yet been found. Actual AWG connectivity remains
unverified; Xray operation and the UI were confirmed by the user.
Human captcha challenge/retry is supported; Premium keys, gateway proxy fallbacks and renewal are unsupported.
Re-importing a managed key requests a new native configuration; account limits apply.

## Historical Lab2 import diagnosis

An authenticated native_config request reproduced HTTP 402 with encrypted captcha_id,
captcha_image and http_status fields. The gateway now decrypts error responses and
classifies challenges before treating HTTP failure as fatal. The ViewModel owns the
pending retry closure (including the same WG keypair), decodes a bounded image off main,
and exposes only the image to Compose. Cancel/clear drops the pending challenge.
A new captcha response replaces the previous prompt. Answers normalize numeric keyboard
sets. Other gateway failures retain a safe numeric status instead of account/response text.
This paragraph describes Lab2 at the time. Later human captcha submissions were accepted;
current provider error handling also recognizes fixed no-compatible-server and quota
categories without logging provider messages or account identifiers.

## Lab5 native export and current follow-up

Validated local AWG profiles can be copied in native quick-config form from the home
screen or profile menu. Export reparses the local configuration, preserves keys, routes,
DNS and AWG parameters, and does not provision a new configuration. Android 13+ clipboard
content is marked sensitive. Unit round-trip validation passed. The user later clarified that no working AWG configuration was available.
End-to-end AWG connectivity and a working independent-client export round-trip remain
unverified; direct .conf file saving is also follow-up work.

The public roadmap is `../../docs/ROADMAP.md` relative to this module.
Private development logs and user configurations are intentionally excluded from this repository. The graph is a source
navigation aid, not evidence of tunnel handshake, speed or device lifecycle correctness.

## Device report, 4 November 2026

The user subsequently obtained a working Amnezia configuration and reported successful
connection and excellent speed on their Xiaomi Note 11 Pro Plus. This supersedes the
earlier lack-of-working-config status for ordinary connectivity. Numerical AWG throughput,
independent ADB checks, full lifecycle scenarios and native export connectivity in another
client remain unverified. Lab6 adds daemon-origin uptime and notification reconnection;
AWG traffic counters are explicitly unavailable rather than fabricated.

## Lab7 active-tunnel connection test, 7 November 2026

The active AWG profile now handles correlated connection-test requests using a bounded
HTTPS header probe. The test binds DNS and sockets to an app-owned VPN Network on
Android 11+, disables HTTP proxy/redirects and aborts before reading the response body.
Older Android reports explicit unsupported status. Stop/restart invalidates request
ownership and cancels the service-owned probe. It does not use Xray, local SOCKS or
automatically start/switch tunnels for bulk tests. Native traffic counters remain unavailable.
See docs/LAB7.fa.md in the workspace for acceptance and physical-device checks Not run.

## Lab8 probe follow-up, 7 November 2026

The user reports normal AWG traffic and an active timer, but Lab7's HTTPS probe fails.
The exact Android DNS/TLS/socket cause is still unknown. Lab8 adds failure categories
and an errno code when available, without logging URLs or raw exception messages.
The probe continues to use owned-VPN DNS and sockets; no direct-network fallback is added.

Real-delay batches now request a measurement from the active AWG service rather than
returning a hardcoded -1. Inactive profiles and TCP-only tests are skipped without a
fabricated failure; AWG profiles are protected from delay-based automatic deletion.
The home test targets the active GUID/session and offers Cancel while testing. RPC
waiting is bounded and cancellation targets only the matching probe, not the tunnel.
Physical Lab8 acceptance is Not run. See workspace docs/LAB8.fa.md and ROADMAP.fa.md.
