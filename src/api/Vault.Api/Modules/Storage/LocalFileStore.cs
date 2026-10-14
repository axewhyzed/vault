using System.Security.Cryptography;
using System.Runtime.InteropServices;

namespace Vault.Api.Modules.Storage;

public interface IFileVolumeCapacity
{
    bool TryGetCapacity(string path, out long totalBytes, out long availableBytes);
}

public sealed class DriveInfoFileVolumeCapacity : IFileVolumeCapacity
{
    public bool TryGetCapacity(string path, out long totalBytes, out long availableBytes)
    {
        totalBytes = 0;
        availableBytes = 0;
        try
        {
            var fullPath = Path.GetFullPath(path);
            var drive = DriveInfo.GetDrives()
                .Where(x => x.IsReady && IsWithin(fullPath, Path.GetFullPath(x.Name)))
                .OrderByDescending(x => x.Name.Length)
                .FirstOrDefault();
            if (drive is null) return false;
            if (!drive.IsReady || drive.TotalSize <= 0) return false;
            totalBytes = drive.TotalSize;
            availableBytes = drive.AvailableFreeSpace;
            return true;
        }
        catch { return false; }
    }

    private static bool IsWithin(string path, string mount)
    {
        var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        var normalizedMount = Path.TrimEndingDirectorySeparator(mount);
        if (normalizedMount.Length == 0) normalizedMount = Path.GetPathRoot(mount)!;
        return path.Equals(normalizedMount, comparison)
            || path.StartsWith(normalizedMount + Path.DirectorySeparatorChar, comparison)
            || normalizedMount == Path.GetPathRoot(mount) && path.StartsWith(normalizedMount, comparison);
    }
}

public sealed class LocalFileStore(IConfiguration configuration, IFileVolumeCapacity capacity)
{
    public const long MaxUploadBytes = 100_000_000;
    private readonly string _root = Path.GetFullPath(configuration["Storage:FilesPath"] ?? "/var/lib/vault/files");

    public string Root => _root;
    public string StagingPath(string key) => SafePath("staging", key);
    public string ObjectPath(string key) => SafePath("objects", key);

    public void Initialize()
    {
        Directory.CreateDirectory(Path.Combine(_root, "staging"));
        Directory.CreateDirectory(Path.Combine(_root, "objects"));
    }

    public bool HasReserve(long incomingBytes)
    {
        try
        {
            Initialize();
            if (!capacity.TryGetCapacity(_root, out var totalBytes, out var availableBytes) || totalBytes <= 0) return false;
            return availableBytes - incomingBytes >= (long)Math.Ceiling(totalBytes * 0.20d);
        }
        catch { return false; }
    }

    public async Task<(long Size, string Sha256)> WriteAndHashAsync(string stagingKey, Stream input, CancellationToken ct)
    {
        Initialize();
        var path = StagingPath(stagingKey);
        long size = 0;
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        await using (var output = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None,
                         128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
        {
            var buffer = new byte[128 * 1024];
            while (true)
            {
                var read = await input.ReadAsync(buffer, ct);
                if (read == 0) break;
                size += read;
                if (size > MaxUploadBytes) throw new UploadTooLargeException();
                if (!HasReserve(read)) throw new UploadReserveBreachedException();
                hash.AppendData(buffer, 0, read);
                await output.WriteAsync(buffer.AsMemory(0, read), ct);
            }
            await output.FlushAsync(ct);
            output.Flush(flushToDisk: true);
        }
        return (size, Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant());
    }

    public void Promote(string stagingKey, string storageKey)
    {
        var source = StagingPath(stagingKey);
        var destination = ObjectPath(storageKey);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        if (File.Exists(destination)) return;
        File.Move(source, destination, overwrite: false);
        SyncDirectory(Path.GetDirectoryName(source)!);
        SyncDirectory(Path.GetDirectoryName(destination)!);
    }

    public FileStream? OpenRead(string key)
    {
        try { return new FileStream(ObjectPath(key), FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete, 128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan); }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
    }

    public (long Size, string Sha256)? InspectStaging(string key)
    {
        try
        {
            using var stream = File.OpenRead(StagingPath(key));
            using var hash = SHA256.Create();
            var digest = Convert.ToHexString(hash.ComputeHash(stream)).ToLowerInvariant();
            return (stream.Length, digest);
        }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }

    public void DeleteStaging(string key) { try { File.Delete(StagingPath(key)); } catch (FileNotFoundException) { } }
    public void DeleteObject(string key) { try { File.Delete(ObjectPath(key)); } catch (FileNotFoundException) { } }

    public IEnumerable<string> EnumerateObjects() => Directory.Exists(Path.Combine(_root, "objects"))
        ? Directory.EnumerateFiles(Path.Combine(_root, "objects"), "*", SearchOption.AllDirectories)
        : [];

    public IEnumerable<string> EnumerateStaging() => Directory.Exists(Path.Combine(_root, "staging"))
        ? Directory.EnumerateFiles(Path.Combine(_root, "staging"), "*", SearchOption.TopDirectoryOnly)
        : [];

    private string SafePath(string directory, string key)
    {
        if (key.Length is < 1 or > 128 || key.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.')) || key.Contains("..", StringComparison.Ordinal))
            throw new ArgumentException("Invalid opaque storage key.", nameof(key));
        var path = Path.GetFullPath(Path.Combine(_root, directory, key));
        var prefix = Path.GetFullPath(Path.Combine(_root, directory)) + Path.DirectorySeparatorChar;
        if (!path.StartsWith(prefix, StringComparison.Ordinal)) throw new ArgumentException("Invalid storage key.", nameof(key));
        return path;
    }

    private static void SyncDirectory(string path)
    {
        if (!OperatingSystem.IsLinux()) return;
        const int readOnly = 0;
        const int directory = 0x10000;
        var descriptor = NativeOpen(path, readOnly | directory);
        if (descriptor < 0) throw new IOException("Could not open storage directory for durable promotion.");
        try
        {
            if (NativeFsync(descriptor) != 0) throw new IOException("Could not sync storage directory after promotion.");
        }
        finally { NativeClose(descriptor); }
    }

    [DllImport("libc", EntryPoint = "open", SetLastError = true)]
    private static extern int NativeOpen(string pathname, int flags);
    [DllImport("libc", EntryPoint = "fsync", SetLastError = true)]
    private static extern int NativeFsync(int fileDescriptor);
    [DllImport("libc", EntryPoint = "close", SetLastError = true)]
    private static extern int NativeClose(int fileDescriptor);
}

public sealed class UploadTooLargeException : Exception;
public sealed class UploadReserveBreachedException : Exception;
