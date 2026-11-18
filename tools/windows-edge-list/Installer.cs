// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Threading;
using System.Windows.Forms;
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8", FrameworkDisplayName = ".NET Framework 4.8")]

namespace ForouzEdgeListSetup {
    internal static class Payload {
        internal static byte[] Read(string name) {
            using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)) {
                if (source == null) throw new IOException("Missing installer payload");
                using (var memory = new MemoryStream()) { source.CopyTo(memory); return memory.ToArray(); }
            }
        }
        internal static string Hash(byte[] value) {
            using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(value)).Replace("-", "").ToLowerInvariant();
        }
        internal static void Verify() {
            if (Hash(Read("forouz.app")) != ExpectedPayload.Sha256 || Hash(Read("forouz.catalog")) != ExpectedPayload.CatalogSha256 ||
                Read("forouz.readme").Length == 0 || Read("forouz.license").Length == 0)
                throw new IOException("Installer integrity failed");
        }
    }
    internal sealed class SetupWindow : Form {
        private readonly Button install = new Button();
        private readonly Button open = new Button();
        private readonly Label status = new Label();
        private readonly CancellationTokenSource lifetime = new CancellationTokenSource();
        private readonly string directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ForouzEdgeList");
        private string AppPath { get { return Path.Combine(directory, "FOROUZ_Edge_List.exe"); } }
        internal SetupWindow() {
            Text = "نصب ابزار لبه و فهرست IP فروز"; Width = 650; Height = 320; MinimumSize = new Size(600, 300);
            StartPosition = FormStartPosition.CenterScreen; Font = new Font("Segoe UI", 10);
            RightToLeft = RightToLeft.Yes; RightToLeftLayout = true; AutoScaleMode = AutoScaleMode.Dpi;
            BackColor = Color.FromArgb(11, 16, 13); ForeColor = Color.FromArgb(238, 243, 234);
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(24), ColumnCount = 1, RowCount = 4 };
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(new Label { AutoSize = true, Text = "ابزار لبه و فهرست IP برای فروز\nرنج‌ها داخل بسته‌اند؛ تست لبه و خروجی فهرست، بدون VPN یا Python.\nفقط برای کاربر فعلی نصب می‌شود؛ درایور یا سرویس نصب نمی‌کند.", Padding = new Padding(0, 0, 0, 12) }, 0, 0);
            root.Controls.Add(new Label { AutoSize = true, MaximumSize = new Size(560, 0), Text = @"%LOCALAPPDATA%\ForouzEdgeList", RightToLeft = RightToLeft.No }, 0, 1);
            status.AutoSize = true; status.Padding = new Padding(0, 12, 0, 0); root.Controls.Add(status, 0, 2);
            var actions = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.RightToLeft, Dock = DockStyle.Fill };
            install.Text = "نصب برای من"; install.AutoSize = true; install.MinimumSize = new Size(150, 44);
            open.Text = "اجرای ابزار"; open.AutoSize = true; open.MinimumSize = new Size(150, 44); open.Enabled = false;
            actions.Controls.Add(install); actions.Controls.Add(open); root.Controls.Add(actions, 0, 3); Controls.Add(root);
            install.Click += async delegate {
                install.Enabled = false;
                try {
                    var token = lifetime.Token;
                    await Task.Run(delegate { InstallFiles(token); }, token);
                    if (IsDisposed) return;
                    CreateShortcut(); status.Text = "نصب شد. میان‌بر FOROUZ Edge List در منوی Start آماده است."; open.Enabled = true;
                } catch (Exception) {
                    if (!IsDisposed) { status.Text = "نصب انجام نشد. ابزار باز را ببندید و دسترسی پوشه را بررسی کنید."; install.Enabled = true; }
                }
            };
            open.Click += delegate { try { System.Diagnostics.Process.Start(AppPath); } catch { status.Text = "اجرای ابزار انجام نشد."; } };
            FormClosed += delegate { lifetime.Cancel(); lifetime.Dispose(); };
        }
        private void InstallFiles(CancellationToken token) {
            Payload.Verify();
            string target = Path.GetFullPath(directory);
            if (Directory.Exists(target) && (File.GetAttributes(target) & FileAttributes.ReparsePoint) != 0) throw new IOException();
            if (Directory.Exists(target) && Directory.GetFileSystemEntries(target).Length != 0) {
                if (File.Exists(AppPath) && Payload.Hash(File.ReadAllBytes(AppPath)) == ExpectedPayload.Sha256 &&
                    File.Exists(Path.Combine(target, "README.fa.md")) && File.Exists(Path.Combine(target, "LICENSE.txt")) &&
                    File.Exists(Path.Combine(target, "edge-candidates.json")) && Payload.Hash(File.ReadAllBytes(Path.Combine(target, "edge-candidates.json"))) == ExpectedPayload.CatalogSha256) return;
                throw new IOException("Existing installation must be removed first");
            }
            bool existed = Directory.Exists(target);
            Directory.CreateDirectory(target);
            string[] names = { "FOROUZ_Edge_List.exe", "README.fa.md", "LICENSE.txt", "edge-candidates.json" };
            string[] resources = { "forouz.app", "forouz.readme", "forouz.license", "forouz.catalog" };
            int written = 0;
            try {
                for (int i = 0; i < names.Length; i++) {
                    token.ThrowIfCancellationRequested();
                    WriteOwned(target, names[i], Payload.Read(resources[i])); written++;
                }
                token.ThrowIfCancellationRequested();
            } catch {
                for (int i = 0; i < written; i++) { try { File.Delete(Path.Combine(target, names[i])); } catch (IOException) { } }
                if (!existed) { try { Directory.Delete(target, false); } catch (IOException) { } }
                throw;
            }
        }
        private static void WriteOwned(string directory, string name, byte[] bytes) {
            string path = Path.GetFullPath(Path.Combine(directory, name));
            if (!path.StartsWith(directory + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException();
            string temporary = Path.Combine(directory, ".install-" + Guid.NewGuid().ToString("N"));
            try {
                File.WriteAllBytes(temporary, bytes);
                if (File.Exists(path)) File.Replace(temporary, path, null); else File.Move(temporary, path);
            } finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }
        private void CreateShortcut() {
            object shell = null; object link = null;
            try {
                Type type = Type.GetTypeFromProgID("WScript.Shell", true); shell = Activator.CreateInstance(type);
                string shortcut = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "FOROUZ Edge List.lnk");
                link = type.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { shortcut });
                Type linkType = link.GetType();
                linkType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, link, new object[] { AppPath });
                linkType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, link, new object[] { directory });
                linkType.InvokeMember("Save", BindingFlags.InvokeMethod, null, link, null);
            } finally {
                if (link != null) Marshal.FinalReleaseComObject(link);
                if (shell != null) Marshal.FinalReleaseComObject(shell);
            }
        }
    }
    internal static class Program {
        [STAThread] private static int Main(string[] args) {
            if (args.Length == 2 && args[0] == "--self-test") {
                try { Payload.Verify(); File.WriteAllText(args[1], "{\"payload_sha256\":\"" + ExpectedPayload.Sha256 + "\",\"failures\":0}", new UTF8Encoding(false)); return 0; }
                catch { return 1; }
            }
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false); Application.Run(new SetupWindow()); return 0;
        }
    }
}
