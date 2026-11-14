# FOROUZ public roadmap

Updated 14 November 2026. Implemented features and planned work are separate.

## Delivered in 1.0.1

- Branded FOROUZ interface with Xray and AmneziaWG services.
- Configuration import/export, notification controls, live Xray traffic and optional Laboratory sweeps/history.
- Optional active health monitoring and limited Xray recovery.
- Own-repository compatible-APK update discovery.
- Manual IP observation with explicit route provenance; Xray DNS leak status remains unknown.

## Current priority: connection tools before advanced Laboratory

The base Laboratory is already included in 1.0.1. Revised priorities for the next development cycle:

1. **P1 — CDN scanner and optimizer (R2a–b):** P1a first publishes a [public edge catalog](../catalogs/README.md), separating official prefixes, BGP candidates and exact observed IPs. P1b then consumes it on Android to test selected Cloudflare/CloudFront profiles on the current network; extend GCore/Fastly after service compatibility checks. Preserve Host/SNI/path and authentication; applying a result requires explicit opt-in, backup and undo.
2. **P2 — Fragment and Mux calibration (R3a):** compare the original baseline and compatible patterns with repeated real-delay, stability and optional throughput measurements. Classic Mux and XHTTP XMUX need separate policies; the original baseline may win.
3. **P3 — Root reliability and capability-gated Shizuku:** improve authorization, owned-state cleanup and recovery first. Root and shell backends have different capabilities and require separate device acceptance.
4. **P4 — Iran Direct preset and optional weekly Geo refresh (R8a):** verified sources, validated atomic file replacement, last-known-good rollback and preserved custom routing rules.
5. **Then R1d:** periodic isolated inactive-profile monitoring, history/ranking and controlled selection/rotation. AWG inactive testing and recovery require a separate design.

[Execution boundaries and acceptance criteria](AUTO-SCANNER-OPTIMIZER.md). The P1a collector/feed is separate from the APK; the Android features remain planned and are not additions to the existing 1.0.1 APK. The optional 10 MB client test keeps an explicit consumption estimate.

## Following stages

- R2: CDN identification and endpoint testing is promoted into P1; extend adapters after same-service compatibility is demonstrated.
- R3: Fragment/Mux calibration is promoted into P2; remaining transport/AWG experiments retain compatibility checks and rollback.
- R4: WARP/WireGuard/MASQUE integration after dependency and lifecycle review.
- R5–R7: Separate compatible DNS tunnel, Naive/SSH and Tor/Psiphon adapters with appropriate server/provider support.
- R8: Iran Geo preset/refresh is promoted into P4; remaining routing, DNS, backup, statistics and UX follow later.
- R9: Optional research transports, subject to platform and security feasibility.
- R10: Opt-in free-profile sources after provenance, permissions, refresh limits and provider compatibility are established.

All-app DNS/IPv6 leak testing requires independent measurement and remains open. Planned capabilities are not current protection claims.
