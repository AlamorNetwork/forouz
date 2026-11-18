// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace ForouzEdgeList {
    internal sealed class EdgeRange {
        internal readonly uint First, Last;
        private EdgeRange(uint first, uint last) { First = first; Last = last; }
        internal static EdgeRange Parse(string value) {
            string[] fields = value.Split('/'); uint address; int bits;
            if (fields.Length != 2 || !IpLists.Number(fields[0], out address) || !Int32.TryParse(fields[1], out bits) || bits < 1 || bits > 32)
                throw new FormatException("Invalid public IPv4 prefix");
            uint mask = UInt32.MaxValue << (32 - bits); uint first = address & mask; uint last = first | ~mask;
            if (!IpLists.IsPublicRange(first, last)) throw new FormatException("Non-public prefix");
            return new EdgeRange(first, last);
        }
        internal bool Contains(uint ip) { return ip >= First && ip <= Last; }
        internal string Sample(Random random) {
            ulong width = (ulong)Last - First + 1;
            uint value = First + (uint)(random.NextDouble() * width);
            return String.Format(CultureInfo.InvariantCulture, "{0}.{1}.{2}.{3}", value >> 24, (value >> 16) & 255, (value >> 8) & 255, value & 255);
        }
    }
    internal sealed class EdgeProvider {
        internal string Name, Role, TestHost;
        internal readonly List<EdgeRange> Official = new List<EdgeRange>();
        internal readonly List<EdgeRange> Announced = new List<EdgeRange>();
        public override string ToString() { return Name + (Name == "akamai" ? " · ASN candidates" : Name == "gcore" ? " · origin list" : ""); }
    }
    internal sealed class DesktopCatalog {
        internal const string DefaultUrl = "https://raw.githubusercontent.com/AlamorNetwork/forouz/master/catalogs/edge-candidates.json";
        internal static readonly string Cache = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ForouzEdgeToolsData", "catalog-cache.json");
        internal readonly List<EdgeProvider> Providers = new List<EdgeProvider>();
        internal DateTimeOffset Generated;
        internal string Source;
        internal static string BundledText() {
            using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("forouz.catalog")) {
                if (stream == null) throw new IOException("Missing bundled catalog");
                using (var reader = new StreamReader(stream, Encoding.UTF8, true)) return reader.ReadToEnd();
            }
        }
        private static string Text(Dictionary<string, object> record, string key) {
            object value; string text = record.TryGetValue(key, out value) ? Convert.ToString(value, CultureInfo.InvariantCulture) : "";
            return text.Length > 256 ? text.Substring(0, 256) : text;
        }
        private static void Ranges(Dictionary<string, object> item, string key, List<EdgeRange> result) {
            object value;
            if (!item.TryGetValue(key, out value)) return;
            IEnumerable values = value as IEnumerable;
            if (values == null || value is string) throw new FormatException("Invalid ranges");
            int count = 0;
            foreach (object raw in values) {
                if (++count > 20000 || !(raw is string)) throw new FormatException("Invalid ranges");
                result.Add(EdgeRange.Parse((string)raw));
            }
        }
        internal static DesktopCatalog Parse(string raw, string source) {
            if (String.IsNullOrWhiteSpace(raw) || Encoding.UTF8.GetByteCount(raw) > 2000000) throw new FormatException("Invalid catalog size");
            var serializer = new JavaScriptSerializer { MaxJsonLength = 2000000, RecursionLimit = 100 };
            var root = serializer.DeserializeObject(raw) as Dictionary<string, object>;
            if (root == null || Text(root, "schema_version") != "1") throw new FormatException("Invalid catalog schema");
            DateTimeOffset generated;
            if (!DateTimeOffset.TryParse(Text(root, "generated_at"), CultureInfo.InvariantCulture, DateTimeStyles.None, out generated) ||
                generated > DateTimeOffset.UtcNow.AddMinutes(10)) throw new FormatException("Invalid catalog date");
            object data; var result = new DesktopCatalog { Generated = generated, Source = source };
            if (!root.TryGetValue("providers", out data)) throw new FormatException("Missing providers");
            var providers = data as Dictionary<string, object>;
            if (providers == null || providers.Count < 1 || providers.Count > 32) throw new FormatException("Invalid providers");
            foreach (var entry in providers) {
                var item = entry.Value as Dictionary<string, object>;
                if (item == null || entry.Key.Length > 64) throw new FormatException("Invalid provider");
                var provider = new EdgeProvider { Name = entry.Key, Role = Text(item, "role"), TestHost = Text(item, "test_host") };
                Ranges(item, "official_ipv4", provider.Official); Ranges(item, "announced_ipv4", provider.Announced);
                result.Providers.Add(provider);
            }
            return result;
        }
        internal static DesktopCatalog Load() {
            try {
                if (File.Exists(Cache) && new FileInfo(Cache).Length <= 2000000) return Parse(File.ReadAllText(Cache, Encoding.UTF8), "cache");
            } catch (IOException) { } catch (FormatException) { } catch (ArgumentException) { } catch (InvalidOperationException) { }
            return Parse(BundledText(), "bundled");
        }
        internal static List<string> Samples(List<EdgeRange> ranges, int budget, Random random) {
            if (budget < 1 || budget > 512 || ranges.Count == 0) throw new ArgumentException("Invalid sample budget");
            var order = new List<EdgeRange>(ranges);
            for (int i = order.Count - 1; i > 0; i--) { int j = random.Next(i + 1); var saved = order[i]; order[i] = order[j]; order[j] = saved; }
            var seen = new HashSet<string>(StringComparer.Ordinal); var result = new List<string>();
            for (int attempt = 0; attempt < budget * 32 && result.Count < budget; attempt++) {
                string ip = order[attempt % order.Count].Sample(random);
                if (seen.Add(ip)) result.Add(ip);
            }
            return result;
        }
        internal static string FeedUrl(string text) {
            Uri uri;
            if (String.IsNullOrWhiteSpace(text) || text.Length > 2048 || Regex.IsMatch(text, @"[\x00-\x1f\x7f]") ||
                !Uri.TryCreate(text.Trim(), UriKind.Absolute, out uri) || uri.Scheme != "https" ||
                uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0) throw new FormatException("Invalid HTTPS source");
            return uri.AbsoluteUri;
        }
        internal static async Task<DesktopCatalog> Refresh(string input, CancellationToken token) {
            string url = FeedUrl(input);
            using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(token)) {
                timeout.CancelAfter(30000);
                using (var handler = new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false, UseDefaultCredentials = false })
                using (var client = new HttpClient(handler)) {
                    client.DefaultRequestHeaders.UserAgent.ParseAdd("FOROUZ-Edge-Tools/1.0");
                    using (var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false)) {
                        if (response.StatusCode != HttpStatusCode.OK) throw new IOException("Catalog HTTP failure");
                        using (var stream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false))
                        using (var memory = new MemoryStream())
                        using (timeout.Token.Register(delegate { stream.Dispose(); })) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = await stream.ReadAsync(buffer, 0, buffer.Length, timeout.Token).ConfigureAwait(false)) != 0) {
                                if (memory.Length + count > 2000000) throw new IOException("Catalog size exceeded");
                                memory.Write(buffer, 0, count);
                            }
                            string raw = Encoding.UTF8.GetString(memory.ToArray());
                            var catalog = Parse(raw, "cache");
                            // Network refresh never replaces a newer cache with an expired source. Bundled offline snapshots remain usable with their real age shown.
                            if (catalog.Generated < DateTimeOffset.UtcNow.AddDays(-30)) throw new FormatException("Expired catalog");
                            timeout.Token.ThrowIfCancellationRequested();
                            string directory = Path.GetDirectoryName(Cache); Directory.CreateDirectory(directory);
                            string temporary = Path.Combine(directory, ".catalog-" + Guid.NewGuid().ToString("N"));
                            try {
                                File.WriteAllText(temporary, raw, new UTF8Encoding(false));
                                if (File.Exists(Cache)) File.Replace(temporary, Cache, null); else File.Move(temporary, Cache);
                            } finally { if (File.Exists(temporary)) File.Delete(temporary); }
                            return catalog;
                        }
                    }
                }
            }
        }
        internal static Dictionary<string, object> SelfTest() {
            string raw = BundledText(); var catalog = Parse(raw, "bundled"); int official = 0, announced = 0, samples = 0;
            foreach (var provider in catalog.Providers) {
                official += provider.Official.Count; announced += provider.Announced.Count;
                var ranges = provider.Official.Count > 0 ? provider.Official : provider.Announced;
                var values = Samples(ranges, 32, new Random(19));
                if (values.Count > 32 || new HashSet<string>(values).Count != values.Count) throw new Exception("Unbounded samples");
                foreach (string ip in values) {
                    uint number; if (!IpLists.Number(ip, out number) || !IpLists.IsPublic(ip) || !ranges.Exists(delegate(EdgeRange r) { return r.Contains(number); }))
                        throw new Exception("Invalid sampled address");
                    samples++;
                }
            }
            foreach (string invalid in new[] { "128.0.0.0/2", "10.0.0.0/8", "0.0.0.0/0" }) {
                bool rejected = false; try { EdgeRange.Parse(invalid); } catch (FormatException) { rejected = true; }
                if (!rejected) throw new Exception("Non-public range accepted");
            }
            if (DesktopEdgeProbe.Host("EDGE.example") != "edge.example") throw new Exception("SNI normalization failed");
            bool badHost = false; try { DesktopEdgeProbe.Host("https://edge.example"); } catch (FormatException) { badHost = true; }
            if (!badHost) throw new Exception("Invalid SNI accepted");
            DesktopEdgeProbe.SelfTest().GetAwaiter().GetResult();
            string hash; using (var sha = SHA256.Create()) hash = BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(raw))).Replace("-", "").ToLowerInvariant();
            return new Dictionary<string, object> { { "providers", catalog.Providers.Count }, { "official_ranges", official },
                { "announced_ranges", announced }, { "sample_checks", samples }, { "timeout_cancel_checks", 2 }, { "bundled_sha256", hash }, { "generated_at", catalog.Generated.ToString("O") } };
        }
    }
    internal sealed class DesktopEdgeRow {
        internal string Ip, Tcp80 = "NOT_TESTED", Tcp443 = "NOT_TESTED", Tls = "NOT_TESTED";
        internal bool Reachable;
    }
    internal static class DesktopEdgeProbe {
        internal static async Task SelfTest() {
            bool closed = false;
            var pending = new TaskCompletionSource<bool>();
            try {
                await Bounded(pending.Task, 10, CancellationToken.None, delegate { closed = true; pending.TrySetException(new IOException("fixture")); });
                throw new Exception("Timeout missing");
            } catch (TimeoutException) { if (!closed) throw new Exception("Timeout did not close operation"); }
            using (var cancel = new CancellationTokenSource()) {
                var waiting = new TaskCompletionSource<bool>(); closed = false;
                var task = Bounded(waiting.Task, 10000, cancel.Token, delegate { closed = true; waiting.TrySetCanceled(); });
                cancel.Cancel();
                try { await task; throw new Exception("Cancellation missing"); }
                catch (OperationCanceledException) { if (!closed) throw new Exception("Cancellation did not close operation"); }
            }
        }
        internal static string Host(string raw) {
            if (String.IsNullOrWhiteSpace(raw)) return "";
            string name;
            try { name = new IdnMapping { UseStd3AsciiRules = true }.GetAscii(raw).ToLowerInvariant(); }
            catch (ArgumentException) { throw new FormatException("Invalid SNI"); }
            if (name.Length > 253 || !name.Contains(".") || Uri.CheckHostName(name) != UriHostNameType.Dns || Regex.IsMatch(name, @"\s"))
                throw new FormatException("Invalid SNI");
            return name;
        }
        private static async Task Bounded(Task operation, int millis, CancellationToken token, Action close) {
            using (var delay = CancellationTokenSource.CreateLinkedTokenSource(token)) {
                Task deadline = Task.Delay(millis, delay.Token);
                if (await Task.WhenAny(operation, deadline).ConfigureAwait(false) != operation) {
                    close();
                    try { await operation.ConfigureAwait(false); } catch (Exception) { }
                    token.ThrowIfCancellationRequested(); throw new TimeoutException();
                }
                delay.Cancel(); await operation.ConfigureAwait(false); token.ThrowIfCancellationRequested();
            }
        }
        private static string Failure(Exception error) {
            if (error is TimeoutException) return "TIMEOUT";
            if (error is AuthenticationException) return "TLS_VALIDATION_OR_HANDSHAKE";
            var socket = error as SocketException;
            if (socket != null && socket.SocketErrorCode == SocketError.ConnectionRefused) return "REFUSED";
            return "IO";
        }
        internal static async Task<DesktopEdgeRow> Measure(string ip, string host, CancellationToken token) {
            if (!IpLists.IsPublic(ip)) throw new FormatException("Invalid public IPv4");
            var result = new DesktopEdgeRow { Ip = ip };
            using (var client = new TcpClient(AddressFamily.InterNetwork))
            using (token.Register(client.Close)) {
                try {
                    var clock = Stopwatch.StartNew();
                    await Bounded(client.ConnectAsync(IPAddress.Parse(ip), 80), 3000, token, client.Close).ConfigureAwait(false);
                    result.Tcp80 = clock.ElapsedMilliseconds + " ms"; result.Reachable = true;
                } catch (Exception error) { token.ThrowIfCancellationRequested(); result.Tcp80 = Failure(error); }
            }
            using (var client = new TcpClient(AddressFamily.InterNetwork))
            using (token.Register(client.Close)) {
                try {
                    var clock = Stopwatch.StartNew();
                    await Bounded(client.ConnectAsync(IPAddress.Parse(ip), 443), 3000, token, client.Close).ConfigureAwait(false);
                    result.Tcp443 = clock.ElapsedMilliseconds + " ms"; result.Reachable = true;
                } catch (Exception error) { token.ThrowIfCancellationRequested(); result.Tcp443 = Failure(error); result.Tls = "SKIP: TCP443"; return result; }
                if (host.Length == 0) { result.Tls = "SKIP: no SNI"; return result; }
                try {
                    // No validation callback: Windows validates both the certificate chain and the target hostname.
                    using (var secure = new SslStream(client.GetStream(), false)) {
                        var clock = Stopwatch.StartNew();
                        await Bounded(secure.AuthenticateAsClientAsync(host, null, SslProtocols.None, true), 5000, token, client.Close).ConfigureAwait(false);
                        result.Tls = clock.ElapsedMilliseconds + " ms";
                    }
                } catch (Exception error) { token.ThrowIfCancellationRequested(); result.Tls = Failure(error); }
            }
            return result;
        }
    }
    internal sealed class OfflineScanner : UserControl {
        private bool busy;
        private readonly ComboBox providers = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 190 };
        private readonly ComboBox kind = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 170 };
        private readonly ComboBox budget = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 80 };
        private readonly TextBox sni = new TextBox { Width = 260, RightToLeft = RightToLeft.No };
        private readonly TextBox source = new TextBox { Dock = DockStyle.Fill, RightToLeft = RightToLeft.No, Text = DesktopCatalog.DefaultUrl };
        private readonly Label summary = new Label { AutoSize = true, MaximumSize = new Size(760, 0) };
        private readonly Label status = new Label { AutoSize = true };
        private readonly DataGridView grid = new DataGridView { Dock = DockStyle.Fill, ReadOnly = true, AllowUserToAddRows = false,
            AllowUserToDeleteRows = false, AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill, RowHeadersVisible = false, RightToLeft = RightToLeft.No };
        private readonly Button start = new Button { Text = "شروع تست", AutoSize = true, MinimumSize = new Size(110, 40), Enabled = false };
        private readonly Button cancel = new Button { Text = "لغو", AutoSize = true, MinimumSize = new Size(90, 40), Enabled = false };
        private readonly Button refresh = new Button { Text = "دریافت کاتالوگ", AutoSize = true, MinimumSize = new Size(120, 40) };
        private readonly CancellationTokenSource lifetime = new CancellationTokenSource();
        private CancellationTokenSource running;
        private DesktopCatalog catalog;
        private readonly List<DesktopEdgeRow> rows = new List<DesktopEdgeRow>();
        private long generation;
        internal OfflineScanner() {
            Dock = DockStyle.Fill; BackColor = Color.FromArgb(11, 16, 13); ForeColor = Color.FromArgb(238, 243, 234);
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 6, Padding = new Padding(12) };
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(summary, 0, 0);
            var options = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft };
            kind.Items.AddRange(new object[] { "رنج رسمی", "رنج ASN · کاندید" }); kind.SelectedIndex = 0;
            budget.Items.AddRange(new object[] { 32, 64, 128, 512 }); budget.SelectedIndex = 0;
            foreach (Control control in new Control[] { providers, kind, budget, new Label { Text = "SNI برای TLS:", AutoSize = true }, sni, start, cancel }) options.Controls.Add(control);
            root.Controls.Add(options, 0, 1);
            var sourcePanel = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 2 };
            sourcePanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); sourcePanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            sourcePanel.Controls.Add(source, 0, 0); sourcePanel.Controls.Add(refresh, 1, 0); root.Controls.Add(sourcePanel, 0, 2);
            grid.Columns.Add("ip", "IP"); grid.Columns.Add("80", "TCP 80"); grid.Columns.Add("443", "TCP 443"); grid.Columns.Add("tls", "TLS");
            grid.BackgroundColor = Color.FromArgb(17, 26, 20); grid.DefaultCellStyle.BackColor = Color.FromArgb(17, 26, 20); grid.DefaultCellStyle.ForeColor = ForeColor;
            root.Controls.Add(grid, 0, 3);
            var actions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft };
            var copy = new Button { Text = "کپی IPهای دارای TCP", AutoSize = true, MinimumSize = new Size(150, 40) };
            var save = new Button { Text = "خروجی TXT", AutoSize = true, MinimumSize = new Size(110, 40) };
            actions.Controls.Add(copy); actions.Controls.Add(save); root.Controls.Add(actions, 0, 4); root.Controls.Add(status, 0, 5); Controls.Add(root);
            providers.SelectedIndexChanged += delegate { var p = providers.SelectedItem as EdgeProvider; if (p != null) { sni.Text = p.TestHost ?? ""; kind.SelectedIndex = p.Official.Count == 0 ? 1 : 0; } Describe(); };
            kind.SelectedIndexChanged += delegate { Describe(); };
            start.Click += async delegate { await Scan(); };
            cancel.Click += delegate { if (running != null) running.Cancel(); };
            refresh.Click += async delegate { await UpdateCatalog(); };
            copy.Click += delegate { try { string value = Export(); if (value.Length > 0) Clipboard.SetText(value); } catch (Exception) { status.Text = "کپی انجام نشد."; } };
            save.Click += async delegate {
                string value = Export(); if (value.Length == 0) return;
                using (var dialog = new SaveFileDialog { Filter = "Text file (*.txt)|*.txt", FileName = "forouz-tested-ips.txt", AddExtension = true }) {
                    if (dialog.ShowDialog(this) != DialogResult.OK) return;
                    try { string path = dialog.FileName; await Task.Run(delegate { File.WriteAllText(path, value, new UTF8Encoding(false)); }, lifetime.Token); }
                    catch (Exception) { if (!IsDisposed) status.Text = "ذخیره انجام نشد."; }
                }
            };
            Load += async delegate {
                try { var loaded = await Task.Run((Func<DesktopCatalog>)DesktopCatalog.Load, lifetime.Token); if (!IsDisposed) UseCatalog(loaded); }
                catch (Exception) { if (!IsDisposed) status.Text = "کاتالوگ آفلاین بارگذاری نشد."; }
            };
        }
        private void UseCatalog(DesktopCatalog value) {
            catalog = value; providers.Items.Clear(); int selected = 0;
            foreach (var p in value.Providers) { if (p.Name == "cloudflare") selected = providers.Items.Count; providers.Items.Add(p); }
            if (providers.Items.Count > 0) providers.SelectedIndex = selected;
            Describe(); Busy(false);
        }
        private void Describe() {
            var provider = providers.SelectedItem as EdgeProvider;
            if (catalog == null || provider == null) return;
            int count = kind.SelectedIndex == 0 ? provider.Official.Count : provider.Announced.Count;
            summary.Text = String.Format(CultureInfo.InvariantCulture, "منبع: {0} · تاریخ: {1:yyyy-MM-dd} · {2} رنج\nفقط نمونه‌های محدود تست می‌شوند؛ رنج ASN یا origin به معنی لبهٔ تأییدشده نیست. TLS نیاز به SNI دارد. VPN خاموش باشد؛ نتیجه مربوط به مسیر فعلی ویندوز است.{3}",
                catalog.Source, catalog.Generated, count, catalog.Generated < DateTimeOffset.UtcNow.AddDays(-30) ? "\nاین snapshot قدیمی است؛ تاریخ آن تغییر داده نشده است." : "");
            start.Enabled = !busy && count > 0;
        }
        private void Busy(bool value) { busy = value; refresh.Enabled = !value; providers.Enabled = !value; kind.Enabled = !value; budget.Enabled = !value; sni.ReadOnly = value; source.ReadOnly = value; cancel.Enabled = value && running != null; start.Enabled = !value && catalog != null; Describe(); }
        private string Export() { var seen = new HashSet<string>(); var result = new List<string>(); foreach (var row in rows) if (row.Reachable && seen.Add(row.Ip)) result.Add(row.Ip); return String.Join(Environment.NewLine, result); }
        private async Task Scan() {
            if (running != null || catalog == null) return;
            var provider = providers.SelectedItem as EdgeProvider; if (provider == null) return;
            long id = ++generation;
            running = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token); running.CancelAfter(TimeSpan.FromMinutes(30));
            Busy(true); rows.Clear(); grid.Rows.Clear();
            try {
                string host = DesktopEdgeProbe.Host(sni.Text);
                var ranges = new List<EdgeRange>(kind.SelectedIndex == 0 ? provider.Official : provider.Announced);
                int limit = (int)budget.SelectedItem;
                var samples = DesktopCatalog.Samples(ranges, limit, new Random());
                var progress = new Progress<DesktopEdgeRow>(delegate(DesktopEdgeRow row) {
                    if (IsDisposed || id != generation) return;
                    rows.Add(row); grid.Rows.Add(row.Ip, row.Tcp80, row.Tcp443, row.Tls); status.Text = rows.Count + " / " + samples.Count + " · TCP موفق به معنی کانفیگ سالم نیست.";
                });
                var token = running.Token;
                await Task.Run(async delegate { foreach (string ip in samples) { token.ThrowIfCancellationRequested(); var row = await DesktopEdgeProbe.Measure(ip, host, token); ((IProgress<DesktopEdgeRow>)progress).Report(row); } }, token);
                if (!IsDisposed) status.Text = "تست نمونه‌ها تمام شد؛ کانفیگ را دستی امتحان کنید.";
            } catch (OperationCanceledException) { if (!IsDisposed) status.Text = "تست لغو شد؛ نتایج کامل‌شده باقی ماندند."; }
            catch (Exception) { if (!IsDisposed) status.Text = "تست شروع/تکمیل نشد؛ رنج انتخابی و SNI را بررسی کنید."; }
            finally { running.Dispose(); running = null; if (!IsDisposed) Busy(false); }
        }
        private async Task UpdateCatalog() {
            if (running != null) return;
            Busy(true);
            try { string url = source.Text; var updated = await Task.Run(delegate { return DesktopCatalog.Refresh(url, lifetime.Token); }, lifetime.Token); if (!IsDisposed) { UseCatalog(updated); status.Text = "کاتالوگ معتبر دریافت و ذخیره شد."; } }
            catch (Exception) { if (!IsDisposed) status.Text = "دریافت انجام نشد؛ رنج‌های آفلاین فعلی حفظ شدند."; }
            finally { if (!IsDisposed) Busy(false); }
        }
        private bool lifetimeDisposed;
        protected override void Dispose(bool disposing) {
            if (disposing && !lifetimeDisposed) { lifetime.Cancel(); if (running != null) running.Cancel(); lifetime.Dispose(); lifetimeDisposed = true; }
            base.Dispose(disposing);
        }
    }
}
