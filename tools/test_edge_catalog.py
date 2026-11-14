import importlib.util
import ipaddress
import json
from pathlib import Path
import random
import ssl
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("edge_catalog", Path(__file__).with_name("edge_catalog.py"))
edge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(edge)


class EdgeCatalogTests(unittest.TestCase):
    def test_normalize_preserves_narrow_prefixes_and_collapses_overlaps(self):
        self.assertEqual([ipaddress.ip_network("8.8.8.128/25")], edge.normalize(["8.8.8.128/25", "8.8.8.129/32"]))
        self.assertEqual([ipaddress.ip_network("8.8.8.8/32")], edge.normalize(["8.8.8.8/32"]))
        self.assertEqual([], edge.normalize(["2606:4700::/32"]))
        for bad in ["127.0.0.0/8", "10.0.0.0/8", "0.0.0.0/0", "169.254.1.0/24",
                    "224.0.0.0/4", "172.0.0.0/8", "invalid"]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                edge.normalize([bad])

    def test_weighted_sampling_is_bounded_unique_and_in_range_without_expanding_hosts(self):
        ranges = edge.normalize(["8.0.0.0/8", "9.9.9.9/32"])
        with patch.object(ipaddress.IPv4Network, "hosts", side_effect=AssertionError("enumerated hosts")), \
             patch.object(ipaddress.IPv4Network, "subnets", side_effect=AssertionError("enumerated subnets")):
            sample = edge.sample_addresses(ranges, 64, random.Random(23))
        self.assertEqual(64, len(set(sample)))
        self.assertTrue(all(any(ipaddress.ip_address(ip) in n for n in ranges) for ip in sample))
        self.assertEqual(["8.8.8.8"], edge.sample_addresses(edge.normalize(["8.8.8.8/32"]), 8))
        self.assertEqual([], edge.sample_addresses([], 8))
        for size in [-1, 65]:
            with self.assertRaises(ValueError):
                edge.sample_addresses(ranges, size)

    def test_subtraction_keeps_exact_complement(self):
        announced = edge.normalize(["8.8.8.0/24", "9.9.9.0/24"])
        official = edge.normalize(["8.8.8.0/25", "9.9.0.0/16"])
        self.assertEqual(edge.normalize(["8.8.8.128/25"]), edge.subtract(announced, official))
        self.assertEqual(announced, edge.subtract(announced, []))

    def test_cloudfront_filters_both_service_and_global_region(self):
        data = {"prefixes": [
            {"ip_prefix": "8.8.8.0/24", "service": "CLOUDFRONT", "region": "GLOBAL"},
            {"ip_prefix": "9.9.9.0/24", "service": "AMAZON", "region": "GLOBAL"},
            {"ip_prefix": "11.0.0.0/24", "service": "CLOUDFRONT", "region": "us-east-1"}]}
        self.assertEqual(edge.normalize(["8.8.8.0/24"]), edge.official_ranges("cloudfront", json.dumps(data)))
        with self.assertRaises(ValueError):
            edge.official_ranges("fastly", '{"addresses":[]}')

    def test_official_source_private_entry_is_excluded_without_discarding_public_data(self):
        text = '{"addresses":["172.31.0.24","8.8.8.8"]}'
        self.assertEqual(edge.normalize(["8.8.8.8/32"]), edge.official_ranges("gcore", text))

    def test_provider_headers_are_provider_specific(self):
        cf = {"Server": "cloudflare", "CF-Ray": "test"}
        self.assertTrue(edge.header_evidence("cloudflare", cf))
        self.assertFalse(edge.header_evidence("fastly", cf))
        self.assertFalse(edge.header_evidence("cloudflare", {"server": "nginx"}))
        self.assertTrue(edge.header_evidence("cloudfront", {"x-amz-cf-id": "x", "x-amz-cf-pop": "x"}))
        self.assertTrue(edge.header_evidence("fastly", {"x-served-by": "cache-test", "x-cache": "hit"}))

    def test_probe_keeps_hostname_verification_and_never_falls_back_after_certificate_failure(self):
        sock = MagicMock()
        context = MagicMock()
        context.wrap_socket.side_effect = ssl.SSLCertVerificationError("fixture")
        with patch.object(edge.socket, "create_connection", return_value=sock) as connect, \
             patch.object(edge.ssl, "create_default_context", return_value=context):
            result = edge.probe_address("cloudfront", "8.8.8.8", "example.cloudfront.net")
        self.assertFalse(result["edge_observed"])
        self.assertFalse(result["tls_verified"])
        self.assertEqual("certificate", result["error_category"])
        connect.assert_called_once_with(("8.8.8.8", 443), timeout=3.0)
        context.wrap_socket.assert_called_once_with(sock, server_hostname="example.cloudfront.net")
        sock.close.assert_called_once()

    def test_success_is_exact_ip_and_only_observed_with_verified_tls_and_vendor_markers(self):
        for headers, expected in [([("server", "cloudflare"), ("cf-ray", "test")], True),
                                  ([("server", "nginx")], False)]:
            context = MagicMock()
            conn = MagicMock()
            conn.getresponse.return_value.status = 200
            conn.getresponse.return_value.getheaders.return_value = headers
            with patch.object(edge.socket, "create_connection"), \
                 patch.object(edge.ssl, "create_default_context", return_value=context), \
                 patch.object(edge.http.client, "HTTPSConnection", return_value=conn):
                result = edge.probe_address("cloudflare", "8.8.8.8", "speed.cloudflare.com")
            self.assertEqual(expected, result["edge_observed"])
            self.assertEqual("8.8.8.8", result["address"])
            self.assertNotIn("verified_subnet", result)
            conn.request.assert_called_once()
            conn.close.assert_called_once()

    @staticmethod
    def fixture(url):
        if "cloudflare.com" in url:
            return "8.8.8.0/24\n"
        if "amazonaws.com" in url:
            return json.dumps({"prefixes": [{"service": "CLOUDFRONT", "region": "GLOBAL", "ip_prefix": "9.9.9.0/24"}]})
        if "ripe.net" in url:
            return json.dumps({"status": "ok", "data": {"prefixes": [{"prefix": "11.0.0.0/24"}]}})
        return '{"addresses":["12.0.0.0/24"]}'

    def test_catalog_separates_authoritative_ranges_bgp_and_exact_observations(self):
        def probe(provider, address, host):
            return {"address": address, "edge_observed": True, "tls_verified": True}
        output = edge.build_catalog(probe=True, samples=2, getter=self.fixture, prober=probe)
        cf = output["providers"]["cloudflare"]
        self.assertEqual(4, len(cf["observations"]))
        self.assertEqual(4, len(cf["edge_observed_ipv4"]))
        self.assertEqual(["11.0.0.0/24"], cf["unlisted_bgp_ipv4"])
        self.assertEqual("not_run", output["providers"]["gcore"]["probe_status"])
        self.assertIn("not_assumed_viewer", output["providers"]["gcore"]["role"])
        self.assertEqual("not_run", edge.build_catalog(probe=False, samples=0, getter=self.fixture)["providers"]["cloudflare"]["probe_status"])

    def test_failed_source_does_not_replace_previous_catalog(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "edge.json"
            path.write_text("last-good", encoding="utf-8")
            with self.assertRaises(ValueError):
                catalog = edge.build_catalog(getter=lambda _: "broken")
                edge.write_catalog(path, catalog)
            self.assertEqual("last-good", path.read_text())

    def test_invalid_probe_inputs_and_work_limits_are_rejected_without_network(self):
        with patch.object(edge.socket, "create_connection", side_effect=AssertionError("network")):
            for host, ip in [("https://host.test/path", "8.8.8.8"), ("a.test", "127.0.0.1")]:
                with self.assertRaises(ValueError):
                    edge.probe_address("cloudflare", ip, host)
        for kw in [{"samples": 65}, {"workers": 0}, {"hosts": {"unknown": "host.test"}}]:
            with self.assertRaises(ValueError):
                edge.build_catalog(**kw)


if __name__ == "__main__":
    unittest.main()
