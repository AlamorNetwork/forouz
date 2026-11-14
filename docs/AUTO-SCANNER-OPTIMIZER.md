# Auto-Scanner & Optimizer: next delivery plan

Priority revised on 14 November 2026. The base Laboratory is already shipped in 1.0.1; advanced inactive monitoring and rotation follow the priorities below. This document specifies planned behavior.

## P1: CDN scanner

Select profiles, identify their CDN using DNS/CNAME, official service prefixes and TLS evidence, and request a bounded scan on the current network. Start with compatible Cloudflare/CloudFront WS+TLS and XHTTP+TLS profiles. Add GCore/Fastly with verified frontend and service adapters. Provider membership alone is not endpoint compatibility: Fastly uses domain-associated TLS configurations, while GCore documents its public IP list for origin allowlisting.

Compare the original baseline and sampled same-service addresses. Preserve credentials, port, Host/SNI/authority, path, ALPN, ECH and extra settings. Unknown providers, split download routes and unsupported transports must remain explicit. Direct REALITY/RAW and AWG endpoints are not assumed to be CDN-substitutable.

Require authenticated transport and HTTPS through the candidate outbound; TCP latency or a certificate alone is insufficient. Record repeated real-delay, request failure rate and delay variation on the actual measurement path. Throughput downloads remain optional and budgeted. A failed candidate set produces no winner.

Offer suggestion-only mode or an explicitly authorized test-and-apply session. Default to reversible copies. Reject stale revisions and changed networks; retain backup/undo. Saving a profile for the next connection must not silently restart the active tunnel. Profiles stay local. Bound candidates, concurrency, duration and bytes; never expand an entire huge prefix.

Acceptance: provider evidence, field preservation, baseline/candidate end-to-end tests, transport failures, authorization scope, revision conflicts, cancellation, network changes, rollback and an unaffected active connection. Real device/network checks are required for each advertised adapter.

## P2: Fragment and Mux calibration

Compare the original baseline with a small compatible pattern set, changing one variable at a time. Use repeated request outcomes and a stability window, plus optional download measurements. Keep the baseline when it wins. Results belong to a profile, core revision, network and measurement time.

Reuse existing fragment trials after checking the pinned core schema: current FinalMask trials use length/delay/maxSplit, so do not insert an interval field into the wrong structure. Classic Mux, XUDP and XHTTP XMUX require distinct compatibility policies. Mux is not a universal throughput improvement. Apply a winner only within the authorized scope, with rollback and normal TLS verification.

## P3: Root reliability and Shizuku

First harden authorization, setup failures, repeat stop, setup/stop races and cleanup after process death. Review owned IPv4/IPv6/DNS state, bounded cleanup and recovery. Do not rely only on onDestroy or remove unrelated applications' state. Test on actual rooted hardware before calling this fixed.

The referenced issues have different scopes: #5898 requests LAN/tethering sharing with Shizuku, #6154 concerns the authorization prompt, and #6411 reports connectivity loss after root-mode exit. Reporter-provided analysis is not independent validation.

Shizuku may use root UID0 or shell UID2000, with different Android/Linux privileges. Verify effective capabilities, permission lifetime and service lifetime. A shell backend is not a blanket substitute for root networking authority. Expose only the functionality supported on that device. Battery improvements require measurements rather than assumptions.

## P4: Iran Geo preset and weekly refresh

Use a vetted source such as Chocolate4U/Iran-v2ray-rules and verify the selected dataset's actual tags. Its sample routing includes geosite:ir and geoip:ir. Existing manual downloads are a starting point; robust file replacement and success reporting precede scheduling.

Require preset/source opt-in and a routing preview. Use bounded HTTPS downloads, validate format and available digest/provenance, replace compatible files atomically and retain a last-known-good copy. Preserve custom rules and their order. A valid file does not itself prove the preset is active or every domestic service is classified correctly.

Use unique periodic WorkManager work with network/data constraints and cancellation when disabled. Weekly cadence is best-effort, not a guaranteed execution time. Test offline/corrupt files, partial updates, replacement failure, cancellation, rollback, custom rules and actual routes.

## Primary references

- [Shizuku API and privilege model](https://github.com/RikkaApps/Shizuku-API)
- [Issue5898](https://github.com/2dust/v2rayNG/issues/5898), [Issue6154](https://github.com/2dust/v2rayNG/issues/6154), [Issue6411](https://github.com/2dust/v2rayNG/issues/6411)
- [Fastly routing](https://www.fastly.com/documentation/guides/concepts/routing-traffic-to-fastly/)
- [GCore origin allowlisting](https://support.gcore.com/hc/en-us/articles/16360355991057-4xx-errors)
- [Xray Mux](https://xtls.github.io/en/config/outbound.html#muxobject)
- [Iran routing datasets](https://github.com/Chocolate4U/Iran-v2ray-rules)

**Not run:** implementation or runtime acceptance of P1–P4, live CDN scans, automatic profile changes, privileged device actions, scheduled Geo downloads, network/battery benchmarks or a new APK build. Current work is source/document research and reprioritization.
