# FOROUZ public roadmap

Updated 14 November 2026. Implemented features and planned work are separate.

## Delivered in 1.0.1

- Branded FOROUZ interface with Xray and AmneziaWG services.
- Configuration import/export, notification controls, live Xray traffic and optional Laboratory sweeps/history.
- Optional active health monitoring and limited Xray recovery.
- Own-repository compatible-APK update discovery.
- Manual IP observation with explicit route provenance; Xray DNS leak status remains unknown.

## Next development: R1d

1. Optional periodic monitoring of inactive Xray profiles through isolated probes, with timing and traffic budgets.
2. History and ranking of fresh successful samples, while retaining the active tunnel.
3. Controlled selection/rotation among healthy candidates. Native AWG inactive testing and recovery require a separate design.

The existing 10 MB test remains opt-in with an explicit consumption estimate.

## Following stages

- R2: CDN identification and bounded endpoint tests, starting with Cloudflare/CloudFront. Candidate IPs use a copied profile and explicit user request/confirmation; preserve Host/SNI/path and credentials.
- R3: Transport-aware DPI/AWG experiments, compatibility checks and rollback.
- R4: WARP/WireGuard/MASQUE integration after dependency and lifecycle review.
- R5–R7: Separate compatible DNS tunnel, Naive/SSH and Tor/Psiphon adapters with appropriate server/provider support.
- R8: Routing, DNS, backup, native statistics and UX completion.
- R9: Optional research transports, subject to platform and security feasibility.
- R10: Opt-in free-profile sources after provenance, permissions, refresh limits and provider compatibility are established.

All-app DNS/IPv6 leak testing requires independent measurement and remains open. Planned capabilities are not current protection claims.
