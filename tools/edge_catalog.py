"""Publishable CDN range metadata and bounded, per-address edge observations.

Adapted from the user's edge collector. No VPN credentials or user profiles are
accepted. A responding sample never verifies the rest of its prefix. BGP alone
does not identify a CDN frontend, and a failed probe does not prove "not edge".
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import datetime
import hashlib
import http.client
import ipaddress
import json
from pathlib import Path
import random
import re
import socket
import ssl
import threading
import time
import urllib.error
import urllib.request

SOURCES = {
    "cloudflare": "https://www.cloudflare.com/ips-v4",
    "gcore": "https://api.gcore.com/cdn/public-ip-list",
    "fastly": "https://api.fastly.com/public-ip-list",
    "cloudfront": "https://ip-ranges.amazonaws.com/ip-ranges.json",
}
ASNS = {
    "cloudflare": ["AS13335", "AS209242"],
    "gcore": ["AS199524", "AS202422"],
    "fastly": ["AS54113"],
    "cloudfront": ["AS16509", "AS14618"],
    "akamai": ["AS20940", "AS16625"],
}
# Only public, provider-specific hosts. Additional hosts are explicit operator inputs.
# CloudFront host below is linked from AWS's edge-range documentation.
DEFAULT_HOSTS = {
    "cloudflare": "speed.cloudflare.com",
    "cloudfront": "d7uri8nf7uskq.cloudfront.net",
}
ROLES = {
    "cloudflare": "viewer_prefixes",
    "cloudfront": "global_viewer_prefixes",
    "fastly": "service_associated_public_prefixes",
    "gcore": "origin_allowlist_not_assumed_viewer",
    "akamai": "asn_candidates_only",
}
NON_PUBLIC = [ipaddress.ip_network(value) for value in (
    "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
    "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16",
    "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4")]


def public_network(net):
    return (net.version == 4 and net.network_address.is_global and net.broadcast_address.is_global
            and not any(net.overlaps(blocked) for blocked in NON_PUBLIC))


def normalize(values):
    """Keep exact IPv4 CIDRs, including /25../32; reject non-public ranges."""
    nets = []
    for value in values:
        net = ipaddress.ip_network(str(value).strip(), strict=False)
        if net.version != 4:
            continue
        if not public_network(net):
            raise ValueError("Non-public prefix in source")
        nets.append(net)
    return list(ipaddress.collapse_addresses(nets))


def subtract(announced, official):
    remaining = list(announced)
    for off in official:
        next_ranges = []
        for candidate in remaining:
            if not candidate.overlaps(off):
                next_ranges.append(candidate)
            elif candidate.subnet_of(off):
                continue
            else:
                next_ranges.extend(candidate.address_exclude(off))
        remaining = next_ranges
    return list(ipaddress.collapse_addresses(remaining))


def sample_addresses(networks, count, rng=None):
    """Bounded weighted sampling without ever enumerating hosts or /24s."""
    if not 0 <= count <= 64:
        raise ValueError("Sample limit is 64 addresses per pool")
    nets = list(ipaddress.collapse_addresses(networks))
    total = sum(net.num_addresses for net in nets)
    if not total or not count:
        return []
    rng = rng or random.SystemRandom()
    offsets = rng.sample(range(total), min(count, total))
    result = []
    for offset in offsets:
        for net in nets:
            if offset < net.num_addresses:
                result.append(str(net.network_address + offset))
                break
            offset -= net.num_addresses
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Forouz-Edge-Catalog/1.0",
                                              "Accept": "application/json, text/plain"})
    with urllib.request.build_opener(NoRedirect).open(req, timeout=20) as response:
        data = response.read(8_000_001)
        if response.status != 200 or len(data) > 8_000_000:
            raise ValueError("Invalid or oversized source response")
        return data.decode("utf-8")


def official_data(provider, text):
    if provider == "cloudflare":
        values = text.splitlines()
    elif provider == "cloudfront":
        values = [p["ip_prefix"] for p in json.loads(text)["prefixes"]
                  if p["service"] == "CLOUDFRONT" and p["region"] == "GLOBAL"]
    else:
        values = json.loads(text)["addresses"]
    public = []
    excluded = 0
    for value in values:
        net = ipaddress.ip_network(str(value).strip(), strict=False)
        if net.version != 4:
            continue
        if public_network(net):
            public.append(str(net))
        else:
            excluded += 1
    ranges = normalize(public)
    if not 1 <= len(ranges) <= 20_000:
        raise ValueError("Empty or unexpected official prefix count")
    return ranges, excluded


def official_ranges(provider, text):
    return official_data(provider, text)[0]


def header_evidence(provider, headers):
    """Provider markers are observations, not cryptographic proof of ownership."""
    h = {str(k).lower(): str(v).lower() for k, v in headers.items()}
    if provider == "cloudflare":
        return "cf-ray" in h and "cloudflare" in h.get("server", "")
    if provider == "cloudfront":
        return "x-amz-cf-id" in h and ("x-amz-cf-pop" in h or "cloudfront" in h.get("via", ""))
    if provider == "fastly":
        return "cache-" in h.get("x-served-by", "") and "x-cache" in h
    if provider == "gcore":
        return any(marker in h.get("server", "") for marker in ("gcore", "gcdn"))
    if provider == "akamai":
        return "akamai-grn" in h or "x-akamai-transformed" in h
    return False


def valid_host(host):
    return (isinstance(host, str) and len(host) <= 253 and "." in host and
            all(re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?", part)
                for part in host.split(".")) and not host.replace(".", "").isdigit())


def probe_address(provider, address, host, timeout=3.0):
    if not valid_host(host) or not public_network(ipaddress.ip_network(address + "/32")):
        raise ValueError("Public literal address and DNS host required")
    result = {"address": address, "test_host": host, "probe_status": "failed",
              "edge_observed": False, "tls_verified": False, "http_status": None,
              "provider_header_observed": False, "error_category": None,
              "observed_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    started = time.monotonic()
    conn = None
    raw = None
    secured = None
    deadline = None
    response = None
    try:
        raw = socket.create_connection((address, 443), timeout=timeout)
        # Strict PKI + hostname verification, using THIS provider's host, never a shared SNI.
        secured = ssl.create_default_context().wrap_socket(raw, server_hostname=host)
        raw = None  # secured now owns the descriptor
        result["tls_verified"] = True
        # Socket timeouts alone bound idle reads, not a peer dripping partial headers forever.
        def interrupt():
            try:
                secured.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        deadline = threading.Timer(timeout * 2, interrupt)
        deadline.daemon = True
        deadline.start()
        conn = http.client.HTTPSConnection(host, timeout=timeout)
        conn.sock = secured
        conn.request("HEAD", "/", headers={"Host": host, "Connection": "close",
                                          "User-Agent": "Forouz-Edge-Catalog/1.0"})
        response = conn.getresponse()
        result["http_status"] = response.status
        result["provider_header_observed"] = header_evidence(provider, dict(response.getheaders()))
        result["edge_observed"] = (200 <= response.status < 500 and result["provider_header_observed"])
        result["probe_status"] = "edge_observed" if result["edge_observed"] else "https_only"
    except ssl.SSLCertVerificationError:
        result["error_category"] = "certificate"
    except ssl.SSLError:
        result["error_category"] = "tls"
    except (TimeoutError, socket.timeout):
        result["error_category"] = "timeout"
    except (OSError, http.client.HTTPException):
        result["error_category"] = "network_or_http"
    finally:
        if deadline is not None:
            deadline.cancel()
        if response is not None:
            response.close()
        if conn is not None:
            conn.close()
        elif secured is not None:
            secured.close()
        if raw is not None:
            raw.close()
    result["elapsed_ms"] = round((time.monotonic() - started) * 1000)
    return result


def build_catalog(probe=False, bgp=True, samples=8, workers=4, hosts=None, getter=fetch,
                  prober=probe_address, progress=None):
    if not 0 <= samples <= 64 or not 1 <= workers <= 16:
        raise ValueError("Invalid work limits")
    hosts = dict(DEFAULT_HOSTS if hosts is None else hosts)
    if any(k not in ASNS or not valid_host(v) for k, v in hosts.items()):
        raise ValueError("Invalid provider host mapping")
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    output = {"schema_version": 1, "generated_at": now,
              "vantage": "catalog_builder_network_not_end_user_network",
              "purpose": "edge_candidates_not_vpn_performance_or_subnet_health",
              "probes_requested": probe, "providers": {}}
    for provider, asns in ASNS.items():
        if progress:
            progress(provider)
        official = []
        source = SOURCES.get(provider)
        source_hash = None
        excluded = 0
        # Official-source failure aborts publication: never replace the last-good catalog with empty data.
        if source:
            text = getter(source)
            official, excluded = official_data(provider, text)
            source_hash = hashlib.sha256(text.encode()).hexdigest()
        announced = []
        bgp_sources = []
        query_end = int(time.time())
        for asn in asns if bgp else []:
            url = ("https://stat.ripe.net/data/announced-prefixes/data.json?resource=" + asn +
                   "&starttime=" + str(query_end - 3600) + "&endtime=" + str(query_end))
            try:
                data = json.loads(getter(url))
                if data.get("status") != "ok":
                    raise ValueError("RIPE did not return success")
                announced.extend(normalize(p["prefix"] for p in data["data"]["prefixes"]))
                bgp_sources.append({"asn": asn, "url": url, "status": "ok"})
            except (OSError, ValueError, KeyError):
                bgp_sources.append({"asn": asn, "url": url, "status": "unavailable"})
        announced = list(ipaddress.collapse_addresses(announced))
        unlisted = subtract(announced, official)
        candidates = [(ip, "official") for ip in sample_addresses(official, samples)]
        candidates += [(ip, "unlisted_bgp") for ip in sample_addresses(unlisted, samples)]
        observations = []
        host = hosts.get(provider)
        if probe and host:
            with ThreadPoolExecutor(max_workers=workers) as pool:
                futures = {pool.submit(prober, provider, ip, host): origin for ip, origin in candidates}
                for future in as_completed(futures):
                    row = future.result()
                    row["candidate_source"] = futures[future]
                    observations.append(row)
        entry = {"role": ROLES[provider], "official_source": source,
                 "official_source_sha256": source_hash,
                 "non_public_ipv4_excluded": excluded,
                 "official_ipv4": [str(n) for n in official],
                 "announced_ipv4": [str(n) for n in announced],
                 "unlisted_bgp_ipv4": [str(n) for n in unlisted], "bgp_sources": bgp_sources,
                 "test_host": host, "probe_status": "run" if probe and host else "not_run",
                 "not_run_reason": None if probe and host else ("no_provider_host" if probe else "not_requested"),
                 "observations": sorted(observations, key=lambda r: int(ipaddress.ip_address(r["address"]))),
                 "edge_observed_ipv4": sorted({r["address"] for r in observations if r["edge_observed"]},
                                              key=ipaddress.ip_address)}
        output["providers"][provider] = entry
    return output


def write_catalog(path, catalog):
    encoded = json.dumps(catalog, indent=2, sort_keys=True) + "\n"
    if len(encoded.encode()) > 8_000_000:
        raise ValueError("Catalog too large")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(encoded, encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "catalogs/edge-candidates.json")
    parser.add_argument("--probe", action="store_true", help="Run bounded per-address HTTPS/header checks")
    parser.add_argument("--no-bgp", action="store_true")
    parser.add_argument("--samples", type=int, default=8, help="Samples per provider and source pool, max 64")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--hosts", type=Path, help="JSON map of provider to public test DNS hostname; never a profile")
    args = parser.parse_args()
    hosts = DEFAULT_HOSTS if args.hosts is None else json.loads(args.hosts.read_text(encoding="utf-8"))
    catalog = build_catalog(args.probe, not args.no_bgp, args.samples, args.workers, hosts,
                            progress=lambda provider: print("Collecting " + provider, flush=True))
    write_catalog(args.output, catalog)
    for name, entry in catalog["providers"].items():
        print(f"{name}: official={len(entry['official_ipv4'])}, observations={len(entry['observations'])}, "
              f"edge-observed-addresses={len(entry['edge_observed_ipv4'])}")


if __name__ == "__main__":
    main()
