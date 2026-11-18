# FOROUZ public roadmap

Updated 18 November 2026. Implemented features and planned work are separate.

## Delivered in 1.0.1

- Branded FOROUZ interface with Xray and AmneziaWG services.
- Configuration import/export, notification controls, live Xray traffic and optional Laboratory sweeps/history.
- Optional active health monitoring and limited Xray recovery.
- Own-repository compatible-APK update discovery.
- Manual IP observation with explicit route provenance; Xray DNS leak status remains unknown.

## Delivered in1.0.2

- Independent IPv4 CDN edge scanner: TCP80, TCP443 and separately verified TLS. No profile tunnel or10MB download in this scanner; configuration success is tested manually.
- Explicit HTTPS catalog source selection and last-valid cache retention. An Iran-hosted mirror can be deployed separately; no mirror domain is bundled or claimed live.
- Exact manual IP mode independent of catalog download/provider DNS. Public-IP-only single/bulk clipboard actions. Historical reports remain readable.
- Windows companion with the full public catalog embedded offline, official/ASN distinctions, bounded edge sampling and TXT export for Android. This is not a full Windows VPN client.

Next priorities remain service-compatible provider expansion, bounded Fragment/Mux tuning, capability-gated Root/Shizuku reliability and optional verified Geo updates. Full Windows/iOS VPN clients require separate platform implementations and are not delivered here. No native-EOF root cause, universal censorship bypass or all-app leak protection is claimed. See [scanner behavior and validation boundaries](CDN-SCANNER.md).

## Following stages

- R2: CDN identification and endpoint testing is promoted into P1; extend adapters after same-service compatibility is demonstrated.
- R3: Fragment/Mux calibration is promoted into P2; remaining transport/AWG experiments retain compatibility checks and rollback.
- R4: WARP/WireGuard/MASQUE integration after dependency and lifecycle review.
- R5–R7: Separate compatible DNS tunnel, Naive/SSH and Tor/Psiphon adapters with appropriate server/provider support.
- R8: Iran Geo preset/refresh is promoted into P4; remaining routing, DNS, backup, statistics and UX follow later.
- R9: Optional research transports, subject to platform and security feasibility.
- R10: Opt-in free-profile sources after provenance, permissions, refresh limits and provider compatibility are established.

All-app DNS/IPv6 leak testing requires independent measurement and remains open. Planned capabilities are not current protection claims.
