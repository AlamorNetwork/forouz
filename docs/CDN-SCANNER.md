# CDN edge tools — FOROUZ 1.0.2

The Android Laboratory includes an independent IPv4 edge scanner. It does not start an Xray tunnel, authenticate a profile, change a saved profile or download a speed-test file.

## Android: catalog scan

1. Open Laboratory → CDN Scanner. The catalog URL defaults to the public FOROUZ GitHub feed. A direct HTTPS mirror URL can be saved in the same screen; credentials, query parameters, fragments and redirects are not accepted. Saving a URL does not fetch it. Press Download / refresh explicitly. A failed or invalid refresh keeps the last valid cached catalog and never silently switches sources.
2. Stop the VPN and keep the physical network unchanged. Select a profile with a valid SNI. Its Address/DNS is used only to identify Cloudflare or CloudFront; credentials, transport, masks, Host and path are not exercised by the scanner.
3. Leave the manual-IP field empty and select a32/64/128/512-IP budget. The cached catalog must be valid and less than30days old; timestamps are not rewritten. Each tested public IP receives TCP80, TCP443 and, if443 connects, system-trusted TLS with SNI and HTTPS hostname verification. The scanner connects to the literal IP through Android Network.socketFactory; it does not resolve the SNI as the connection destination.
4. Connect timeouts are3seconds per TCP port and5seconds for TLS. There is one sample per stage, not ICMP ping, native Real Delay, jitter or throughput. A failed443 connection leaves TLS explicitly not tested. The run deadline is30minutes; cancellation closes owned sockets and retains completed IP results.
5. Review the independent stages. “Edge reachable” means at least one TCP connection succeeded; it does not prove CDN identity, tunnel compatibility, origin reachability, clean IP status or resistance to censorship. TLS success validates the peer name/certificate separately. Test the actual configuration yourself.

Adaptive sampling refines successful branches and defers repeatedly failing branches. It does not enumerate every address or declare an entire prefix dead after a failed sample. The budget counts IPs actually tested; there is no additional native baseline in this mode.

## Android: no server or catalog

Paste1–512 public IPv4 addresses into the optional manual list. Newlines, commas, semicolons and whitespace are accepted; duplicates are removed and malformed/private addresses reject the whole input. Only those exact IPs are tested. This mode does not read/fetch a catalog or resolve the profile Address to identify a provider. The selected profile supplies SNI; the provider is labelled unverified. Clear the field to return to catalog scanning. Manual input is an uncommitted ViewModel draft; completed results persist locally.

Copy one reachable IP or all reachable IPs. The clipboard contains only distinct public IPs, one per line, in the visible ranking order (TLS-verified,443,80 and timing). No SNI, UUID, key or profile export is copied. Copying is also available for completed rows of interrupted/cancelled runs. No automatic profile creation or application is offered for edge results.

Historical HTTPS/native-delay/download reports keep their original fields and qualification semantics. They are not reinterpreted as edge tests. The earlier tunnel scanner's phone EOF investigation does not establish a cause or a universal TLS fix; the scanner's scope was changed to independent edge reachability.

## Windows companion

[Build, install and use the Windows Edge List tool](../tools/windows-edge-list/README.fa.md). Setup and Portable packages include the entire public catalog snapshot, both embedded in the executable and as JSON. They start without a network download. Users can choose official prefixes or clearly labelled ASN candidates, sample a bounded budget, supply a service-associated SNI and test TCP80/TCP443/TLS using Windows trust. A failed explicit online refresh retains the offline data. Snapshot age is shown and never forged.

The snapshot contains Cloudflare, CloudFront, Fastly, GCore and Akamai metadata. GCore origin allowlists and ASN membership are not proof of viewer-edge compatibility. Only Cloudflare and CloudFront currently have public default test hostnames. Exported TXT contains public IPs only and can be pasted into Android manual mode. This companion is not a Windows VPN client and installs no driver or service.

## Validation and limits

The Android candidate passed435 JVM tests per debug distribution, both Kotlin compilations, packaging and lint with zero errors. ARM64 signing,16KB zip alignment and unchanged native libraries were checked. A real JVM loopback TLS fixture accepts a trusted matching hostname and rejects an untrusted certificate and wrong hostname. These checks do not replace Android Conscrypt/Network.socketFactory/device testing.

Windows CLI checks cover public IPv4 input, CIDR boundaries, all bundled providers, bounded samples, task cancellation/timeout and installer payload hashes. Windows executables are not Authenticode signed. Interactive installation, remote-network TLS/scanner behavior, clipboard, keyboard/accessibility and device/display matrices remain Not run. This repository does not claim complete DNS/IPv6/WebRTC leak protection.
