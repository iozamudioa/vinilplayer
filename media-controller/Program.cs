using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using Windows.Media.Control;

namespace MediaController
{
    class Program
    {
        [DllImport("user32.dll")]
        static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        static extern bool SetForegroundWindow(IntPtr hWnd);

        const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        const uint KEYEVENTF_KEYUP = 0x0002;

        const byte VK_MEDIA_NEXT_TRACK = 0xB0;
        const byte VK_MEDIA_PREV_TRACK = 0xB1;
        const byte VK_MEDIA_PLAY_PAUSE = 0xB3;
        const int SW_RESTORE = 9;

        static int Main(string[] args)
        {
            if (args.Length == 0)
            {
                Console.WriteLine("Usage: media-controller.exe [play|pause|next|previous|playpause|seek <seconds>|focussource]");
                return 1;
            }

            string command = args[0].ToLowerInvariant();

            try
            {
                if (command == "seek")
                {
                    return HandleSeek(args);
                }

                if (command == "focussource")
                {
                    return HandleFocusSource();
                }

                byte keyCode = command switch
                {
                    "play" => VK_MEDIA_PLAY_PAUSE,
                    "pause" => VK_MEDIA_PLAY_PAUSE,
                    "next" => VK_MEDIA_NEXT_TRACK,
                    "previous" => VK_MEDIA_PREV_TRACK,
                    "playpause" => VK_MEDIA_PLAY_PAUSE,
                    _ => 0
                };

                if (keyCode == 0)
                {
                    Console.WriteLine($"Unknown command: {command}");
                    return 1;
                }

                keybd_event(keyCode, 0, KEYEVENTF_EXTENDEDKEY, UIntPtr.Zero);
                keybd_event(keyCode, 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, UIntPtr.Zero);

                Console.WriteLine($"Command '{command}' executed successfully");
                return 0;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error: {ex.Message}");
                return 1;
            }
        }

        static int HandleSeek(string[] args)
        {
            if (args.Length < 2)
            {
                Console.WriteLine("Usage: media-controller.exe seek <seconds>");
                return 1;
            }

            if (!double.TryParse(args[1], NumberStyles.Float, CultureInfo.InvariantCulture, out double seconds))
            {
                Console.WriteLine($"Invalid seek value: {args[1]}");
                return 1;
            }

            seconds = Math.Max(0, seconds);
            bool ok = SeekToSecondsAsync(seconds).GetAwaiter().GetResult();
            if (!ok)
            {
                Console.WriteLine("Seek command failed");
                return 1;
            }

            Console.WriteLine($"Seeked to {seconds:0.##} seconds");
            return 0;
        }

        static int HandleFocusSource()
        {
            bool ok = FocusCurrentSessionSourceWindowAsync().GetAwaiter().GetResult();
            if (!ok)
            {
                Console.WriteLine("Focus source command failed");
                return 1;
            }

            Console.WriteLine("Focused source window");
            return 0;
        }

        static async System.Threading.Tasks.Task<bool> SeekToSecondsAsync(double seconds)
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            if (manager == null)
            {
                return false;
            }

            var session = manager.GetCurrentSession();
            if (session == null)
            {
                var sessions = manager.GetSessions();
                if (sessions.Count == 0)
                {
                    return false;
                }

                session = sessions[0];
            }

            long ticks = TimeSpan.FromSeconds(seconds).Ticks;
            return await session.TryChangePlaybackPositionAsync(ticks);
        }

        static async System.Threading.Tasks.Task<bool> FocusCurrentSessionSourceWindowAsync()
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            if (manager == null)
            {
                return false;
            }

            var session = manager.GetCurrentSession();
            if (session == null)
            {
                var sessions = manager.GetSessions();
                if (sessions.Count == 0)
                {
                    return false;
                }

                session = sessions[0];
            }

            string sourceAppId = session.SourceAppUserModelId ?? string.Empty;
            if (TryFocusByProcess(sourceAppId))
            {
                return true;
            }

            return TryOpenServiceUri(sourceAppId);
        }

        static bool TryFocusByProcess(string sourceAppId)
        {
            foreach (string candidate in ResolveProcessCandidates(sourceAppId))
            {
                if (BringProcessWindowToFront(candidate))
                {
                    return true;
                }
            }

            return false;
        }

        static IEnumerable<string> ResolveProcessCandidates(string sourceAppId)
        {
            var candidates = new List<string>();
            string source = sourceAppId ?? string.Empty;
            string normalized = source.ToLowerInvariant();

            if (normalized.Contains("msedge") || normalized.Contains("microsoftedge")) candidates.Add("msedge");
            if (normalized.Contains("chrome")) candidates.Add("chrome");
            if (normalized.Contains("firefox")) candidates.Add("firefox");
            if (normalized.Contains("brave")) candidates.Add("brave");
            if (normalized.Contains("opera")) candidates.Add("opera");

            if (normalized.Contains("spotify")) candidates.Add("spotify");
            if (normalized.Contains("applemusic") || normalized.Contains("music.apple")) candidates.Add("applemusic");
            if (normalized.Contains("amazonmusic") || normalized.Contains("music.amazon")) candidates.Add("amazonmusic");
            if (normalized.Contains("youtubemusic") || normalized.Contains("youtube"))
            {
                candidates.Add("youtubemusic");
                candidates.Add("YouTube Music");
            }

            string candidate = source;
            int bangIndex = candidate.IndexOf('!');
            if (bangIndex >= 0)
            {
                candidate = candidate.Substring(0, bangIndex);
            }

            string postBangCandidate = source;
            if (bangIndex >= 0 && bangIndex + 1 < source.Length)
            {
                postBangCandidate = source.Substring(bangIndex + 1);
            }

            int separator = Math.Max(candidate.LastIndexOf('\\'), candidate.LastIndexOf('/'));
            if (separator >= 0 && separator + 1 < candidate.Length)
            {
                candidate = candidate.Substring(separator + 1);
            }

            int postBangSeparator = Math.Max(postBangCandidate.LastIndexOf('\\'), postBangCandidate.LastIndexOf('/'));
            if (postBangSeparator >= 0 && postBangSeparator + 1 < postBangCandidate.Length)
            {
                postBangCandidate = postBangCandidate.Substring(postBangSeparator + 1);
            }

            if (candidate.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            {
                candidate = candidate.Substring(0, candidate.Length - 4);
            }

            if (postBangCandidate.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            {
                postBangCandidate = postBangCandidate.Substring(0, postBangCandidate.Length - 4);
            }

            candidate = candidate.Trim();
            postBangCandidate = postBangCandidate.Trim();

            if (!string.IsNullOrWhiteSpace(candidate))
            {
                candidates.Add(candidate);
            }

            if (!string.IsNullOrWhiteSpace(postBangCandidate))
            {
                candidates.Add(postBangCandidate);
            }

            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (string entry in candidates)
            {
                if (string.IsNullOrWhiteSpace(entry))
                {
                    continue;
                }

                string normalizedEntry = entry.Trim();
                if (seen.Add(normalizedEntry))
                {
                    yield return normalizedEntry;
                }
            }
        }

        static bool TryOpenServiceUri(string sourceAppId)
        {
            string normalized = (sourceAppId ?? string.Empty).ToLowerInvariant();

            if (normalized.Contains("spotify"))
            {
                return TryOpenUri("spotify:") || TryOpenUri("https://open.spotify.com");
            }

            if (normalized.Contains("youtubemusic") || normalized.Contains("youtube"))
            {
                return TryOpenUri("youtubemusic://") || TryOpenUri("https://music.youtube.com");
            }

            if (normalized.Contains("applemusic") || normalized.Contains("music.apple"))
            {
                return TryOpenUri("applemusic://") || TryOpenUri("music://") || TryOpenUri("https://music.apple.com");
            }

            if (normalized.Contains("amazonmusic") || normalized.Contains("music.amazon"))
            {
                return TryOpenUri("amazonmusic://") || TryOpenUri("https://music.amazon.com");
            }

            return false;
        }

        static bool TryOpenUri(string uri)
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = uri,
                    UseShellExecute = true
                });

                return true;
            }
            catch
            {
                return false;
            }
        }

        static bool BringProcessWindowToFront(string processName)
        {
            try
            {
                var processes = Process.GetProcessesByName(processName);
                foreach (var process in processes)
                {
                    IntPtr handle = process.MainWindowHandle;
                    if (handle == IntPtr.Zero)
                    {
                        continue;
                    }

                    ShowWindowAsync(handle, SW_RESTORE);
                    if (SetForegroundWindow(handle))
                    {
                        return true;
                    }
                }
            }
            catch
            {
                return false;
            }

            return false;
        }
    }
}
