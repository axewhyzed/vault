export async function readJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T;
  } catch {
    return null;
  }
}

export function retryDelay(response: Response): number {
  const retryAfter = response.headers.get('Retry-After');
  if (!retryAfter) return 30;
  const seconds = Number(retryAfter);
  if (Number.isFinite(seconds)) return Math.max(1, Math.min(3600, Math.ceil(seconds)));
  const dateDelay = Math.ceil((Date.parse(retryAfter) - Date.now()) / 1000);
  return Number.isFinite(dateDelay) ? Math.max(1, Math.min(3600, dateDelay)) : 30;
}

export function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? 'Unknown'
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export function formatRelativeTime(dateString: string): string {
  const date = new Date(dateString);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  if (Number.isNaN(diffMs)) return 'Unknown';
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return 'just now';
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHours = Math.floor(diffMin / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  const diffDays = Math.floor(diffHours / 24);
  if (diffDays === 1) return 'yesterday';
  if (diffDays < 7) return `${diffDays}d ago`;
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(date);
}

export function formatExpiry(expiresAtString: string): string {
  const expires = new Date(expiresAtString);
  const now = new Date();
  const diffMs = expires.getTime() - now.getTime();
  if (Number.isNaN(diffMs) || diffMs <= 0) return 'Expired';
  const diffHours = Math.floor(diffMs / (1000 * 60 * 60));
  if (diffHours < 24) return `${Math.max(1, diffHours)}h left`;
  const diffDays = Math.floor(diffHours / 24);
  return `${diffDays}d left`;
}

export function formatBytes(value: number): string {
  if (value < 1000) return `${value} B`;
  const units = ['KB', 'MB', 'GB'];
  let size = value / 1000;
  let unit = 0;
  while (size >= 1000 && unit < units.length - 1) {
    size /= 1000;
    unit += 1;
  }
  return `${size >= 100 ? Math.round(size) : size.toFixed(1)} ${units[unit]}`;
}
