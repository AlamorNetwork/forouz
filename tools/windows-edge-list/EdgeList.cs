// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8", FrameworkDisplayName = ".NET Framework 4.8")]

namespace ForouzEdgeList {
    public static class IpLists {
        private static readonly string[] Blocked = { "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16",
            "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4" };
        internal static bool Number(string value, out uint number) {
            number = 0;
            string[] parts = value.Split('.');
            if (parts.Length != 4) return false;
            foreach (string part in parts) {
                uint octet;
                if (!UInt32.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out octet) || octet > 255 ||
                    octet.ToString(CultureInfo.InvariantCulture) != part) return false;
                number = (number << 8) | octet;
            }
            return true;
        }
        public static bool IsPublic(string value) {
            uint ip;
            if (!Number(value, out ip)) return false;
            return IsPublicRange(ip, ip);
        }
        internal static bool IsPublicRange(uint first, uint last) {
            foreach (string prefix in Blocked) {
                string[] fields = prefix.Split('/');
                uint start;
                Number(fields[0], out start);
                int bits = Int32.Parse(fields[1], CultureInfo.InvariantCulture);
                uint mask = UInt32.MaxValue << (32 - bits);
                uint low = start & mask, high = low | ~mask;
                if (first <= high && low <= last) return false;
            }
            return true;
        }
        public static string[] Normalize(string raw) {
            if (String.IsNullOrWhiteSpace(raw) || raw.Length > 16384) throw new FormatException("فهرست خالی یا بیش از حد بزرگ است.");
            var seen = new HashSet<string>(StringComparer.Ordinal);
            var result = new List<string>();
            foreach (string value in Regex.Split(raw.Trim(), @"[\s,;]+")) {
                if (!IsPublic(value)) throw new FormatException("فقط IPv4 عمومی معتبر وارد کنید؛ IP خصوصی، لینک و کانفیگ پذیرفته نیست.");
                if (seen.Add(value)) result.Add(value);
                if (result.Count > 512) throw new FormatException("حداکثر ۵۱۲ IP یکتا مجاز است.");
            }
            return result.ToArray();
        }
        public static int SelfTest(string path) {
            int checks = 0;
            foreach (string value in new[] { "1.1.1.1", "8.8.8.8", "18.161.126.68", "9.9.9.9" }) {
                if (!IsPublic(value)) throw new Exception("Public IPv4 fixture failed"); checks++;
            }
            foreach (string value in new[] { "127.0.0.1", "10.1.2.3", "192.168.1.1", "100.64.0.1", "169.254.1.1", "172.16.0.1",
                "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "01.1.1.1", "::1", "https://1.1.1.1", "1.1.1.999" }) {
                if (IsPublic(value)) throw new Exception("Invalid IPv4 fixture accepted"); checks++;
            }
            if (String.Join("\n", Normalize("1.1.1.1\n8.8.8.8,1.1.1.1;8.8.8.8")) != "1.1.1.1\n8.8.8.8") throw new Exception("Deduplication failed"); checks++;
            bool rejected = false;
            try { Normalize("1.1.1.1\n127.0.0.1"); } catch (FormatException) { rejected = true; }
            if (!rejected) throw new Exception("Mixed invalid list accepted"); checks++;
            var many = new List<string>();
            for (int i = 0; i <= 512; i++) many.Add("8.8." + (i / 256) + "." + (i % 256));
            rejected = false;
            try { Normalize(String.Join("\n", many)); } catch (FormatException) { rejected = true; }
            if (!rejected) throw new Exception("Limit failed"); checks++;
            var report = DesktopCatalog.SelfTest();
            report.Add("checks", checks); report.Add("failures", 0); report.Add("network_requests", 0);
            File.WriteAllText(path, new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(report), new UTF8Encoding(false));
            return 0;
        }
    }
    public sealed class MainWindow : Form {
        private readonly TextBox input = new TextBox();
        private readonly TextBox output = new TextBox();
        private readonly Label status = new Label();
        private readonly List<Button> buttons = new List<Button>();
        private readonly CancellationTokenSource lifetime = new CancellationTokenSource();
        private readonly Color panel = Color.FromArgb(17, 26, 20);
        private readonly Color text = Color.FromArgb(238, 243, 234);
        public MainWindow() {
            Text = "FOROUZ | ابزار لبه و فهرست IP آفلاین"; Width = 930; Height = 730; MinimumSize = new Size(740, 560);
            StartPosition = FormStartPosition.CenterScreen; AutoScaleMode = AutoScaleMode.Dpi;
            Font = new Font("Segoe UI", 10); BackColor = Color.FromArgb(11, 16, 13); ForeColor = text;
            RightToLeft = RightToLeft.Yes; RightToLeftLayout = true;
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(20), ColumnCount = 1, RowCount = 5 };
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(new Label { Text = "آماده‌سازی IP برای فروز\nبدون سرور، دانلود یا اتصال VPN — فقط IPv4 عمومی؛ حداکثر ۵۱۲ IP", AutoSize = true, Padding = new Padding(0, 0, 0, 12) }, 0, 0);
            var toolbar = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft };
            toolbar.Controls.Add(MakeAsyncButton("بازکردن TXT", LoadText, false));
            toolbar.Controls.Add(MakeButton("بررسی فهرست", delegate { NormalizeInput(); }, true));
            root.Controls.Add(toolbar, 0, 1);
            var editors = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 2 };
            editors.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50)); editors.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            editors.RowStyles.Add(new RowStyle(SizeType.AutoSize)); editors.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            editors.Controls.Add(new Label { Text = "IPهای ورودی", AutoSize = true }, 0, 0);
            editors.Controls.Add(new Label { Text = "خروجی یکتا برای اپ", AutoSize = true }, 1, 0);
            foreach (TextBox box in new[] { input, output }) {
                box.Multiline = true; box.ScrollBars = ScrollBars.Vertical; box.Dock = DockStyle.Fill;
                box.RightToLeft = RightToLeft.No; box.Font = new Font("Consolas", 11); box.BackColor = panel; box.ForeColor = text;
                box.BorderStyle = BorderStyle.FixedSingle; box.MaxLength = 16384; box.Margin = new Padding(4, 6, 4, 12);
            }
            output.ReadOnly = true; input.AccessibleName = "IPهای ورودی"; output.AccessibleName = "خروجی IPهای عمومی یکتا";
            editors.Controls.Add(input, 0, 1); editors.Controls.Add(output, 1, 1); root.Controls.Add(editors, 0, 2);
            var actions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft };
            actions.Controls.Add(MakeButton("کپی IPها", delegate { if (NormalizeInput()) Clipboard.SetText(output.Text, TextDataFormat.UnicodeText); }, false));
            actions.Controls.Add(MakeAsyncButton("ذخیرهٔ TXT", SaveText, false));
            actions.Controls.Add(MakeButton("پاک‌کردن", delegate { input.Clear(); output.Clear(); status.Text = ""; }, false));
            root.Controls.Add(actions, 0, 3);
            status.AutoSize = true; status.Padding = new Padding(0, 8, 0, 0); root.Controls.Add(status, 0, 4);
            var tabs = new TabControl { Dock = DockStyle.Fill };
            var manual = new TabPage("فهرست دستی"); manual.Controls.Add(root);
            var scanner = new TabPage("اسکن رنج‌های آفلاین"); scanner.Controls.Add(new OfflineScanner());
            tabs.TabPages.Add(manual); tabs.TabPages.Add(scanner); Controls.Add(tabs);
            FormClosed += delegate { lifetime.Cancel(); lifetime.Dispose(); };
        }
        private Button MakeButton(string label, Action action, bool primary) {
            return MakeAsyncButton(label, delegate { action(); return Task.FromResult(0); }, primary);
        }
        private Button MakeAsyncButton(string label, Func<Task> action, bool primary) {
            var button = new Button { Text = label, AutoSize = true, MinimumSize = new Size(120, 42), Margin = new Padding(4), FlatStyle = FlatStyle.Flat,
                BackColor = primary ? Color.FromArgb(211, 248, 90) : panel, ForeColor = primary ? Color.FromArgb(11, 16, 13) : text };
            button.Click += async delegate(object sender, EventArgs args) {
                try { await action(); }
                catch (Exception) { if (!IsDisposed) MessageBox.Show(this, "عملیات انجام نشد. فایل یا دسترسی کلیپ‌بورد را بررسی کنید.", "فروز"); }
            };
            buttons.Add(button); return button;
        }
        private bool NormalizeInput() {
            try { string[] values = IpLists.Normalize(input.Text); output.Text = String.Join(Environment.NewLine, values); status.Text = values.Length + " IP عمومی یکتا آماده است؛ دسترسی شبکه آزمایش نشده است."; return true; }
            catch (FormatException error) { output.Clear(); status.Text = error.Message; return false; }
        }
        private void Busy(bool value) { foreach (Button b in buttons) b.Enabled = !value; input.ReadOnly = value; }
        private async Task LoadText() {
            using (var dialog = new OpenFileDialog { Filter = "Text file (*.txt)|*.txt", Multiselect = false }) {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                Busy(true);
                try {
                    string path = dialog.FileName;
                    string raw = await Task.Run(delegate { if (new FileInfo(path).Length > 16384) throw new IOException(); return File.ReadAllText(path, Encoding.UTF8); }, lifetime.Token);
                    if (!IsDisposed) { input.Text = raw; NormalizeInput(); }
                } catch (Exception) { if (!IsDisposed) status.Text = "فایل خوانده نشد یا بیش از حد بزرگ است."; }
                finally { if (!IsDisposed) Busy(false); }
            }
        }
        private async Task SaveText() {
            if (!NormalizeInput()) return;
            using (var dialog = new SaveFileDialog { Filter = "Text file (*.txt)|*.txt", FileName = "forouz-ips.txt", AddExtension = true }) {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                Busy(true);
                try {
                    string path = dialog.FileName; string value = output.Text;
                    await Task.Run(delegate { File.WriteAllText(path, value, new UTF8Encoding(false)); }, lifetime.Token);
                    if (!IsDisposed) status.Text = "فایل ذخیره شد. متن آن را در کادر IP دستیِ فروز وارد کنید.";
                } catch (Exception) { if (!IsDisposed) status.Text = "فایل ذخیره نشد."; }
                finally { if (!IsDisposed) Busy(false); }
            }
        }
    }
    internal static class Program {
        [STAThread] private static int Main(string[] args) {
            if (args.Length == 2 && args[0] == "--self-test") { try { return IpLists.SelfTest(args[1]); } catch { return 1; } }
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false); Application.Run(new MainWindow()); return 0;
        }
    }
}
