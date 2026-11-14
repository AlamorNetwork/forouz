# FOROUZ privacy notes

Updated 14 November 2026. FOROUZ is an independent Android client derived from v2rayNG.

## Profiles and diagnostics

Connection profiles, subscriptions, preferences and optional Laboratory history are stored locally by the app. Profile import, subscription refresh, VPN connections and diagnostic checks contact the servers or services selected for those operations. A VPN connection alone does not prove anonymity, complete protection or the absence of leaks. Advanced logs and intentionally shared configurations can contain sensitive information; inspect them before sharing.

## Privacy & Leak Monitor

The Overview monitor is manual. It sends HTTPS checks to `api.ipify.org` and `api6.ipify.org` to observe outlet IP addresses, and up to three TXT queries for `whoami.ds.akahelp.net` to observe recursive DNS resolvers. These services and the network path used can see those diagnostic requests. FOROUZ does not control their privacy policies or availability.

For Amnezia, the connected monitor binds to an Android VPN network owned by FOROUZ, only when this app is included by that VPN. Xray excludes its own app from TUN: its IP checks instead use an explicit local SOCKS listener advertised by the active daemon runtime, with remote destination resolution and no direct fallback. This measures configured Xray egress, including any configured routing rules; it does not verify the TUN path used by other apps. Xray DNS leak status is reported as unknown, and Android raw TXT queries are not used for its connected samples. Unsupported or authenticated custom listeners are not guessed. Neither connected route silently falls back to the physical network. A separate, explicitly confirmed sample can be captured while VPN is disconnected; that sample deliberately uses the current internet connection directly. Ownership checks require Android 11+. Proxy-only and root modes are not covered by this monitor.

IP/resolver observations and the optional direct-network reference stay in the current ViewModel's memory. This feature does not write them to disk, SavedState, Laboratory history or diagnostic logs. Leaving Overview cancels unfinished checks. Process termination or “Forget samples” clears the observations.

Resolver overlap with a recent direct-network reference is only a possible bypass signal. Shared public resolvers, forwarding and resolver caching limit this method. No overlap does not guarantee no leaks; a failed IPv6 request does not prove IPv6 is blocked or protected. The sample concerns only the explicitly labelled diagnostic path, not every other application's DNS, WebRTC or routing behavior. Version 1.0.0 phone testing exposed failed Xray IP checks and ambiguous DNS results from the excluded app UID; those results must not be used as evidence of leak protection.

## Updates

Opening the update screen requests public release metadata from the [FOROUZ repository](https://github.com/AlamorNetwork/forouz/releases). If direct access fails, the existing local proxy can be tried. Download starts only after the user chooses it, through an external browser, for a compatible FOROUZ APK from that repository. No background updater or silent installation is added. The Xray core is bundled with the APK and changes through a new app build.

## Upstream and third parties

FOROUZ retains the upstream software license and acknowledgements. Third-party endpoints, VPN operators and subscriptions have their own policies. These notes describe the added features; they are not a claim of a complete security audit.
