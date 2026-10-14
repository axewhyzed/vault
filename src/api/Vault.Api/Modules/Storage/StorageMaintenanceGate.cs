using System.Runtime.InteropServices;

namespace Vault.Api.Modules.Storage;

/// <summary>Coordinates file-volume mutations with backups across API and backup containers.</summary>
public sealed class StorageMaintenanceGate(IConfiguration configuration)
{
    private static readonly SemaphoreSlim WindowsFallback = new(1, 1);
    private readonly string _lockPath = Path.GetFullPath(configuration["Storage:MaintenanceLockPath"] ?? "/run/vault-maintenance/maintenance.lock");

    public IDisposable EnterShared() => OperatingSystem.IsLinux() ? AcquireNative(shared: true) : AcquireFallback(shared: true);
    public IDisposable EnterExclusive() => OperatingSystem.IsLinux() ? AcquireNative(shared: false) : AcquireFallback(shared: false);

    private IDisposable AcquireNative(bool shared)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_lockPath)!);
        const int openReadWrite = 2;
        const int openCreate = 64;
        const int openCloseOnExec = 0x80000;
        var fd = Open(_lockPath, openReadWrite | openCreate | openCloseOnExec, 0x1B0);
        if (fd < 0) throw new IOException($"Could not open the shared file maintenance lock (errno {Marshal.GetLastWin32Error()}).");
        var operation = shared ? 1 : 2; // flock LOCK_SH / LOCK_EX
        if (Flock(fd, operation) != 0)
        {
            var error = Marshal.GetLastWin32Error();
            Close(fd);
            throw new IOException($"Could not acquire the shared file maintenance lock (errno {error}).");
        }
        return new NativeLease(fd);
    }

    private static IDisposable AcquireFallback(bool shared)
    {
        WindowsFallback.Wait();
        return new FallbackLease();
    }

    private sealed class NativeLease(int fd) : IDisposable
    {
        public void Dispose()
        {
            Flock(fd, 8); // LOCK_UN
            Close(fd);
        }
    }

    private sealed class FallbackLease : IDisposable
    {
        public void Dispose() => WindowsFallback.Release();
    }

    [DllImport("libc", EntryPoint = "open", SetLastError = true, CharSet = CharSet.Ansi)]
    private static extern int Open(string pathname, int flags, int mode);
    [DllImport("libc", EntryPoint = "flock", SetLastError = true)]
    private static extern int Flock(int fileDescriptor, int operation);
    [DllImport("libc", EntryPoint = "close", SetLastError = true)]
    private static extern int Close(int fileDescriptor);
}
