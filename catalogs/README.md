# Public edge catalog

This is a **range-discovery feed**, not a list of working VPN servers or fast IPs on the reader's network. No profile, UUID, private key, subscription, or user's IP/DNS sample is an input.

The collector adapts the user-supplied Python edge script: official Cloudflare, GCore, Fastly and global CloudFront prefixes, plus RIPE announced prefixes for the configured ASNs. Akamai currently has ASN candidates only. ASN membership does not establish a CDN frontend.

## Read the JSON

- `official_ipv4`: exact published IPv4 prefixes, retaining small /25–/32 networks. `role` distinguishes viewer ranges, service-associated ranges and GCore's origin allowlist.
- `announced_ipv4` / `unlisted_bgp_ipv4`: announcements observed in the queried one-hour window and their complement after subtracting official ranges; **unverified candidates**, not "hidden clean ranges" or a guarantee of a currently active route. Exact query times are present in the RIPE URLs.
- `observations`: individual sampled IP checks. A successful observation requires normal TLS certificate/hostname validation, an HTTP response and a provider-specific header marker. Markers are observations, not cryptographic proof. Failed checks do not prove that an address is not an edge.
- `edge_observed_ipv4`: **only the exact IPs** satisfying those checks. One sample does not validate its /24 or any other prefix. Empty is a legitimate result.
- `generated_at`, source URLs, source hashes, BGP status and probe status expose freshness and incomplete collection. `non_public_ipv4_excluded` counts private/reserved entries discarded from official responses. Source hashes describe what was collected; they are not an independent publisher signature.

Only Cloudflare and CloudFront have public default test hosts. Fastly, GCore and Akamai retain published/announced metadata but have `not_run` probe status until an operator supplies a suitable service-associated test hostname using `--hosts`. Never use Cloudflare's hostname to classify other providers. GCore's published list is intended for origin allowlisting and is not automatically a viewer-address list.

## Build and publish

```sh
python3 -m unittest discover -s tools -p 'test_edge_catalog.py' -v
python3 tools/edge_catalog.py --probe --samples 8 --workers 4
```

Without `--probe`, this performs metadata collection only. The sampler does not enumerate all hosts or /24s. The default is at most 8 official and 8 unlisted BGP addresses per configured provider, with 4 concurrent checks, using HTTPS port 443 only. There is no certificate bypass, HTTP fallback, redirect following, VPN speed test or automatic profile modification. Official-source failure leaves the previous catalog in place.

The `Refresh public edge catalog` workflow runs weekly and manually on the owning repository. It can commit only `catalogs/edge-candidates.json`; forks do not run the publication job. Measurement belongs to the builder's network, not the user's operator. A client must still test the selected profile end to end on its own network, preserving Host/SNI/path/authentication, before proposing a reversible Address change.

Download feed: [edge-candidates.json](https://raw.githubusercontent.com/AlamorNetwork/forouz/master/catalogs/edge-candidates.json).

## Primary sources

- [Cloudflare prefixes](https://www.cloudflare.com/ips-v4)
- [AWS global CloudFront edge ranges and public list hostname](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/LocationsOfEdgeServers.html)
- [Fastly public IP list](https://api.fastly.com/public-ip-list) and [domain-associated routing](https://www.fastly.com/documentation/guides/concepts/routing-traffic-to-fastly/)
- [GCore origin allowlisting](https://support.gcore.com/hc/en-us/articles/16360355991057-4xx-errors)
- [RIPE announced-prefixes API](https://stat.ripe.net/docs/data-api/api-endpoints/announced-prefixes)
