using System;
using System.Globalization;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace MediaReader
{
    class Program
    {
        private const string ReaderMutexName = @"Global\VinilPlayer.MediaReader.Singleton";

        static void Main(string[] args)
        {
            using var singleInstanceMutex = new Mutex(true, ReaderMutexName, out bool isFirstInstance);
            if (!isFirstInstance)
            {
                Console.Error.WriteLine("media-reader duplicate instance detected, exiting");
                return;
            }

            using var cts = new CancellationTokenSource();
            Console.CancelKeyPress += (_, e) =>
            {
                e.Cancel = true;
                cts.Cancel();
            };

            var thread = new Thread(() =>
            {
                SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());

                Dispatcher.CurrentDispatcher.BeginInvoke(new Action(async () =>
                {
                    await Run(cts.Token);
                    Dispatcher.CurrentDispatcher.InvokeShutdown();
                }));

                Dispatcher.Run();
            });

            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();
        }

        static async Task Run(CancellationToken cancellationToken)
        {
            Console.OutputEncoding = Encoding.UTF8;
            Console.Error.WriteLine("media-reader stream started");

            try
            {
                var manager = await WithTimeout(
                    GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask(),
                    TimeSpan.FromSeconds(2));

                if (manager == null)
                {
                    while (!cancellationToken.IsCancellationRequested)
                    {
                        PrintEmpty();
                        await Task.Delay(500, cancellationToken);
                    }

                    return;
                }

                while (!cancellationToken.IsCancellationRequested)
                {
                    string payload = await ReadSnapshotAsJson(manager);
                    Console.WriteLine(payload);
                    await Console.Out.FlushAsync();
                    await Task.Delay(500, cancellationToken);
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("ERROR: " + e.Message);
                PrintEmpty();
            }
        }

        static async Task<string> ReadSnapshotAsJson(GlobalSystemMediaTransportControlsSessionManager manager)
        {
            var session = manager.GetCurrentSession();
            if (session == null)
            {
                var sessions = manager.GetSessions();
                if (sessions.Count == 0)
                {
                    return EmptyJson();
                }

                session = sessions[0];
            }

            try
            {
                var media = await WithTimeout(
                    session.TryGetMediaPropertiesAsync().AsTask(),
                    TimeSpan.FromMilliseconds(800));
                var playback = session.GetPlaybackInfo();
                var timeline = session.GetTimelineProperties();

                string artist = media?.Artist ?? "";
                string title = media?.Title ?? "";
                string status = playback?.PlaybackStatus.ToString().ToUpperInvariant() ?? "STOPPED";
                double position = timeline?.Position.TotalSeconds ?? 0;
                double duration = timeline?.EndTime.TotalSeconds ?? 0;

                string thumbnail = await ReadThumbnailBase64(media?.Thumbnail);

                return BuildJson(artist, title, status, position, duration, thumbnail);
            }
            catch
            {
                return EmptyJson();
            }
        }

        static async Task<string> ReadThumbnailBase64(IRandomAccessStreamReference? thumbnailRef)
        {
            if (thumbnailRef == null)
            {
                return "";
            }

            try
            {
                using var thumbStream = await WithTimeout(
                    thumbnailRef.OpenReadAsync().AsTask(),
                    TimeSpan.FromMilliseconds(700));
                if (thumbStream == null)
                {
                    return "";
                }

                if (thumbStream.Size == 0)
                {
                    return "";
                }

                var reader = new DataReader(thumbStream);
                uint size = (uint)thumbStream.Size;
                await reader.LoadAsync(size);

                byte[] buffer = new byte[size];
                reader.ReadBytes(buffer);

                return Convert.ToBase64String(buffer);
            }
            catch
            {
                return "";
            }
        }

        static void PrintEmpty()
        {
            Console.WriteLine(EmptyJson());
        }

        static string EmptyJson()
        {
            return BuildJson("", "", "STOPPED", 0, 0, "");
        }

        static string BuildJson(string artist, string title, string status, double position, double duration, string thumbnail)
        {
            return
                "{" +
                "\"artist\":\"" + Escape(artist) + "\"," +
                "\"title\":\"" + Escape(title) + "\"," +
                "\"status\":\"" + Escape(status) + "\"," +
                "\"position\":" + position.ToString(CultureInfo.InvariantCulture) + "," +
                "\"duration\":" + duration.ToString(CultureInfo.InvariantCulture) + "," +
                "\"thumbnail\":\"" + thumbnail + "\"" +
                "}";
        }

        static string Escape(string value)
        {
            return value.Replace("\\", "\\\\").Replace("\"", "\\\"");
        }

        static async Task<T?> WithTimeout<T>(Task<T> task, TimeSpan timeout)
        {
            var completed = await Task.WhenAny(task, Task.Delay(timeout));
            if (completed != task)
            {
                return default;
            }

            return await task;
        }
    }
}
