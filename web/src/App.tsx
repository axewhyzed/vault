import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent, MouseEvent } from 'react';
import ComposeHub from './ComposeHub';
import PairDeviceModal from './PairDeviceModal';
import SecurityModal from './SecurityModal';
import {
  ArrowLeftIcon,
  ArrowRightIcon,
  CheckIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  CloseIcon,
  CopyIcon,
  DownloadIcon,
  FileIcon,
  InboxSparkleIcon,
  ItemTypeIcon,
  LinkIcon,
  MoonIcon,
  QrCodeIcon,
  RefreshIcon,
  SearchIcon,
  ShieldLockIcon,
  SunIcon,
  TextIcon,
  TrashIcon,
} from './Icons';

type Session = { userId: string; email: string; deviceId: string };
type Csrf = { token: string; headerName: 'X-CSRF-TOKEN' };
type ViewState = 'loading' | 'signed-out' | 'signed-in';
type ItemSummary = {
  id: string;
  type: 'text' | 'url' | 'file';
  state: string;
  displayName: string | null;
  sizeBytes: number;
  createdAt: string;
  availableAt: string;
  expiresAt: string;
  version: number;
  sourceDevice: { id: string; name: string; platform: string };
};
type ItemDetail = ItemSummary & {
  text: string | null;
  contentType?: string;
  sha256?: string;
  downloadUrl?: string;
};
type Quota = {
  activeBytes: number;
  maxActiveBytes: number;
  activeItems: number;
  maxActiveItems: number;
};
type ItemPage = { items: ItemSummary[]; nextCursor: string | null; quota: Quota };
type ApiError = { code?: string; message?: string; correlationId?: string };

const API = '/api/v1';

function VaultMark() {
  return (
    <svg aria-hidden="true" viewBox="0 0 32 32" className="vault-mark">
      <rect x="2" y="2" width="28" height="28" rx="9" fill="currentColor" />
      <path d="M9 11.25h2.55l4.45 10.1 4.45-10.1H23l-6.05 13.5h-1.9L9 11.25Z" fill="var(--paper, #f5f4ef)" />
      <circle cx="23.1" cy="8.9" r="1.35" fill="#10B981" />
    </svg>
  );
}

async function readJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T;
  } catch {
    return null;
  }
}

function retryDelay(response: Response) {
  const retryAfter = response.headers.get('Retry-After');
  if (!retryAfter) return 30;
  const seconds = Number(retryAfter);
  if (Number.isFinite(seconds)) return Math.max(1, Math.min(3600, Math.ceil(seconds)));
  const dateDelay = Math.ceil((Date.parse(retryAfter) - Date.now()) / 1000);
  return Number.isFinite(dateDelay) ? Math.max(1, Math.min(3600, dateDelay)) : 30;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? 'Unknown'
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function formatRelativeTime(dateString: string) {
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

function formatExpiry(expiresAtString: string) {
  const expires = new Date(expiresAtString);
  const now = new Date();
  const diffMs = expires.getTime() - now.getTime();
  if (Number.isNaN(diffMs) || diffMs <= 0) return 'Expired';
  const diffHours = Math.floor(diffMs / (1000 * 60 * 60));
  if (diffHours < 24) return `${Math.max(1, diffHours)}h left`;
  const diffDays = Math.floor(diffHours / 24);
  return `${diffDays}d left`;
}

function formatBytes(value: number) {
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

function safePathId() {
  const match = window.location.pathname.match(/^\/items\/([0-9a-f-]{36})\/?$/i);
  return match?.[1] ?? null;
}

function App() {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    const saved = localStorage.getItem('vault-theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });

  const [view, setView] = useState<ViewState>('loading');
  const [session, setSession] = useState<Session | null>(null);
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [messageKind, setMessageKind] = useState<'error' | 'notice'>('error');
  const [retrySeconds, setRetrySeconds] = useState(0);
  const [serviceOnline, setServiceOnline] = useState<boolean | null>(null);

  // Topbar & Modals state
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [pairModalOpen, setPairModalOpen] = useState(false);
  const [securityModalOpen, setSecurityModalOpen] = useState(false);
  const [securityModalBusy, setSecurityModalBusy] = useState(false);
  const [securityModalError, setSecurityModalError] = useState('');

  // Inbox & Filter state
  const [items, setItems] = useState<ItemSummary[]>([]);
  const [filterType, setFilterType] = useState<'all' | 'file' | 'url' | 'text'>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [quota, setQuota] = useState<Quota | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [listBusy, setListBusy] = useState(false);
  const [listError, setListError] = useState('');
  const [inboxNotice, setInboxNotice] = useState('');

  // Detail view state
  const [selectedId, setSelectedId] = useState<string | null>(() => safePathId());
  const [detail, setDetail] = useState<ItemDetail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [copyMessage, setCopyMessage] = useState('');

  // Row micro-actions state
  const [copiedItemId, setCopiedItemId] = useState<string | null>(null);
  const [rowDeleteConfirmId, setRowDeleteConfirmId] = useState<string | null>(null);
  const detailCache = useRef<Map<string, string>>(new Map());

  const identifierRef = useRef<HTMLInputElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const userMenuRef = useRef<HTMLDivElement>(null);
  const mountedRef = useRef(true);
  const generationRef = useRef(0);
  const detailRequestRef = useRef(0);

  const publishError = (text: string) => {
    setMessageKind('error');
    setMessage(text);
  };
  const invalidateRequests = () => {
    generationRef.current += 1;
  };

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('vault-theme', theme);
  }, [theme]);

  const toggleTheme = () => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'));

  const fetchCsrf = useCallback(async () => {
    const response = await fetch(`${API}/auth/csrf`, {
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      if (response.status === 429) throw new Error(`Too many requests. Try again in ${retryDelay(response)} seconds.`);
      throw new Error(
        response.status === 403
          ? 'Vault could not verify this browser request. Refresh the page and try again.'
          : 'Vault could not prepare a secure request. Check your connection and try again.'
      );
    }
    const data = await readJson<Csrf>(response);
    if (!data?.token || data.headerName !== 'X-CSRF-TOKEN')
      throw new Error('Vault returned an invalid security token. Refresh the page and try again.');
    return data;
  }, []);

  const signOutOnUnauthorized = useCallback(() => {
    invalidateRequests();
    detailRequestRef.current += 1;
    setSession(null);
    setView('signed-out');
    setItems([]);
    setQuota(null);
    setDetail(null);
    setSelectedId(null);
    setPairModalOpen(false);
    setSecurityModalOpen(false);
    setUserMenuOpen(false);
    setInboxNotice('');
    setDeleteConfirm(false);
    setMessageKind('error');
    setMessage('Your session has expired. Sign in again to continue.');
    if (window.location.pathname !== '/') window.history.replaceState({}, '', '/');
    void fetchCsrf().catch(() => undefined);
  }, [fetchCsrf]);

  const restoreSession = useCallback(async () => {
    try {
      const response = await fetch(`${API}/auth/me`, {
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
      });
      if (response.status === 401) {
        if (mountedRef.current) {
          setSession(null);
          setView('signed-out');
        }
        await fetchCsrf();
        return;
      }
      if (!response.ok) throw new Error('Vault could not restore your session. Try refreshing the page.');
      const data = await readJson<Session>(response);
      if (!data?.userId || !data.email || !data.deviceId) throw new Error('Vault returned an incomplete session.');
      if (mountedRef.current) {
        setSession(data);
        setView('signed-in');
        void fetchCsrf().catch(() => undefined);
      }
    } catch (error) {
      if (mountedRef.current) {
        setSession(null);
        setView('signed-out');
        publishError(
          error instanceof TypeError
            ? 'Vault is unreachable. Check your connection and try again.'
            : error instanceof Error
              ? error.message
              : 'Vault could not restore your session.'
        );
      }
    }
  }, [fetchCsrf]);

  useEffect(() => {
    mountedRef.current = true;
    void restoreSession();
    const checkHealth = async () => {
      try {
        const response = await fetch('/health/ready', { cache: 'no-store' });
        if (mountedRef.current) setServiceOnline(response.ok);
      } catch {
        if (mountedRef.current) setServiceOnline(false);
      }
    };
    void checkHealth();
    const interval = window.setInterval(checkHealth, 15_000);
    return () => {
      mountedRef.current = false;
      invalidateRequests();
      window.clearInterval(interval);
    };
  }, [restoreSession]);

  useEffect(() => {
    if (view !== 'signed-in' || !session) return;
    const gen = ++generationRef.current;
    void loadPage(null, false, gen);
    return () => {
      if (generationRef.current === gen) generationRef.current += 1;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, session?.userId]);

  useEffect(() => {
    if (view !== 'signed-in' || !session) return;
    if (!selectedId) {
      detailRequestRef.current += 1;
      setDetail(null);
      setDetailError('');
      if (window.location.pathname !== '/') window.history.replaceState({}, '', '/');
      return;
    }
    void loadDetail(selectedId, generationRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, session?.userId, selectedId]);

  const backToList = useCallback(() => {
    window.history.pushState({}, '', '/');
    setSelectedId(null);
    setDeleteConfirm(false);
    setDetailError('');
  }, []);

  useEffect(() => {
    const handlePopState = () => {
      setSelectedId(safePathId());
      setDeleteConfirm(false);
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  // Global keyboard shortcuts: / or Cmd+K to focus search; Esc to back out of detail or menus
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const isInput =
        target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);

      if ((event.key === '/' && !isInput) || ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k')) {
        event.preventDefault();
        searchInputRef.current?.focus();
        searchInputRef.current?.select();
        return;
      }

      if (event.key === 'Escape') {
        if (userMenuOpen) {
          setUserMenuOpen(false);
          return;
        }
        if (selectedId && !pairModalOpen && !securityModalOpen) {
          backToList();
          return;
        }
        if (searchQuery && document.activeElement === searchInputRef.current) {
          setSearchQuery('');
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectedId, pairModalOpen, securityModalOpen, userMenuOpen, searchQuery, backToList]);

  // Click outside listener for user dropdown
  useEffect(() => {
    if (!userMenuOpen) return;
    const handleClickOutside = (e: MouseEvent | globalThis.MouseEvent) => {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target as Node)) {
        setUserMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [userMenuOpen]);

  useEffect(() => {
    if (retrySeconds <= 0) return undefined;
    const timer = window.setTimeout(() => setRetrySeconds((s) => Math.max(0, s - 1)), 1000);
    return () => window.clearTimeout(timer);
  }, [retrySeconds]);

  useEffect(() => {
    if (view === 'signed-out') identifierRef.current?.focus();
  }, [view]);

  async function handleApiFailure(response: Response): Promise<string> {
    if (response.status === 401) {
      signOutOnUnauthorized();
      return 'Your session has expired. Sign in again to continue.';
    }
    const error = await readJson<ApiError>(response);
    if (response.status === 403)
      return error?.message || 'Vault could not verify this browser request. Refresh and try again.';
    if (response.status === 429) return `Please wait ${retryDelay(response)} seconds before trying again.`;
    const known: Record<number, string> = {
      400: 'The request was not valid.',
      404: 'This item is no longer available.',
      410: 'This item has expired.',
      413: 'This item is too large.',
      422: 'Check the item details and try again.',
      409:
        error?.code === 'quota_exceeded'
          ? 'Your Vault storage quota is full. Delete an item or try again later.'
          : error?.code === 'idempotency_conflict'
            ? 'This retry no longer matches the original send. Start a new send.'
            : 'Vault could not accept this request.',
    };
    return error?.message || known[response.status] || 'Vault could not complete the request. Try again.';
  }

  async function loadPage(cursor: string | null, append: boolean, gen = generationRef.current) {
    if (gen !== generationRef.current) return;
    setListBusy(true);
    setListError('');
    try {
      const query = cursor ? `?limit=30&cursor=${encodeURIComponent(cursor)}` : '?limit=30';
      const response = await fetch(`${API}/items${query}`, {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
      });
      if (gen !== generationRef.current) return;
      if (response.status === 401) {
        signOutOnUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(await handleApiFailure(response));
      const data = await readJson<ItemPage>(response);
      if (!data || !Array.isArray(data.items) || !data.quota)
        throw new Error('Vault returned an invalid inbox response.');
      if (gen === generationRef.current) {
        setItems((previous) => (append ? [...previous, ...data.items] : data.items));
        setNextCursor(data.nextCursor);
        setQuota(data.quota);
      }
    } catch (error) {
      if (gen === generationRef.current)
        setListError(
          error instanceof TypeError
            ? 'Vault is unreachable. Check your connection and retry.'
            : error instanceof Error
              ? error.message
              : 'Could not load your inbox.'
        );
    } finally {
      if (gen === generationRef.current) setListBusy(false);
    }
  }

  async function loadDetail(id: string, gen = generationRef.current) {
    const detailRequest = ++detailRequestRef.current;
    setDetail(null);
    setDetailError('');
    setDetailBusy(true);
    try {
      const response = await fetch(`${API}/items/${encodeURIComponent(id)}`, {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
      });
      if (gen !== generationRef.current || detailRequest !== detailRequestRef.current) return;
      if (response.status === 401) {
        signOutOnUnauthorized();
        return;
      }
      if (response.status === 404 || response.status === 410) {
        const messageText = response.status === 410 ? 'This item has expired.' : 'This item could not be found.';
        setDetailError(messageText);
        setItems((previous) => previous.filter((item) => item.id !== id));
        if (window.location.pathname !== '/') {
          window.history.replaceState({}, '', '/');
          setSelectedId(null);
        }
        return;
      }
      if (!response.ok) throw new Error(await handleApiFailure(response));
      const data = await readJson<ItemDetail>(response);
      if (
        !data ||
        (data.type === 'file'
          ? data.text !== null || !data.downloadUrl || !data.contentType || !data.sha256
          : typeof data.text !== 'string')
      )
        throw new Error('Vault returned an invalid item response.');
      if (gen === generationRef.current) {
        setDetail(data);
        if (data.text) detailCache.current.set(data.id, data.text);
      }
    } catch (error) {
      if (gen === generationRef.current && detailRequest === detailRequestRef.current)
        setDetailError(
          error instanceof TypeError
            ? 'Vault is unreachable. Check your connection and retry.'
            : error instanceof Error
              ? error.message
              : 'Could not load this item.'
        );
    } finally {
      if (gen === generationRef.current && detailRequest === detailRequestRef.current) setDetailBusy(false);
    }
  }

  async function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || retrySeconds > 0) return;
    setBusy(true);
    setMessage('');
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/auth/login`, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          [token.headerName]: token.token,
        },
        body: JSON.stringify({ identifier: identifier.trim(), password }),
      });
      if (response.status === 401) {
        publishError('That email or username and password did not match. Check them and try again.');
        setPassword('');
        return;
      }
      if (response.status === 403) {
        publishError('Vault could not verify this sign-in request. Refresh the security token and try again.');
        return;
      }
      if (response.status === 429) {
        const delay = retryDelay(response);
        setRetrySeconds(delay);
        publishError(`Too many sign-in attempts. Try again in ${delay} seconds.`);
        return;
      }
      if (!response.ok) throw new Error('Vault could not sign you in. Try again.');
      setPassword('');
      await restoreSession();
    } catch (error) {
      publishError(
        error instanceof TypeError
          ? 'Vault is unreachable. Check your connection and try again.'
          : error instanceof Error
            ? error.message
            : 'Vault could not sign you in.'
      );
    } finally {
      setBusy(false);
    }
  }

  async function handleLogout() {
    if (busy) return;
    setBusy(true);
    setMessage('');
    setUserMenuOpen(false);
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/auth/logout`, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json', [token.headerName]: token.token },
      });
      if (response.status === 401 || response.ok) {
        invalidateRequests();
        detailRequestRef.current += 1;
        setSession(null);
        setView('signed-out');
        setItems([]);
        setDetail(null);
        setSelectedId(null);
        setCopyMessage('');
        setInboxNotice('');
        setDeleteConfirm(false);
        setMessageKind('notice');
        setMessage('You are signed out.');
        if (window.location.pathname !== '/') window.history.replaceState({}, '', '/');
        await fetchCsrf();
        return;
      }
      publishError(await handleApiFailure(response));
    } catch (error) {
      publishError(
        error instanceof TypeError
          ? 'Vault is unreachable. Check your connection and try again.'
          : error instanceof Error
            ? error.message
            : 'Vault could not sign you out.'
      );
    } finally {
      setBusy(false);
    }
  }

  async function handleRevokeEverywhere() {
    if (securityModalBusy) return;
    setSecurityModalBusy(true);
    setSecurityModalError('');
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/auth/sessions/revoke-all`, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          [token.headerName]: token.token,
        },
        body: '{}',
      });
      if (response.status === 401) {
        signOutOnUnauthorized();
        return;
      }
      if (response.status !== 204 && !response.ok) {
        setSecurityModalError(await handleApiFailure(response));
        return;
      }
      invalidateRequests();
      detailRequestRef.current += 1;
      setSecurityModalOpen(false);
      setSession(null);
      setView('signed-out');
      setItems([]);
      setQuota(null);
      setDetail(null);
      setSelectedId(null);
      setCopyMessage('');
      setInboxNotice('');
      setDeleteConfirm(false);
      setMessageKind('notice');
      setMessage('You are signed out everywhere. Sign in again on each device you want to keep using.');
      if (window.location.pathname !== '/') window.history.replaceState({}, '', '/');
    } catch (error) {
      setSecurityModalError(
        error instanceof TypeError
          ? 'Vault is unreachable. Check your connection and try again.'
          : error instanceof Error
            ? error.message
            : 'Could not sign out everywhere.'
      );
    } finally {
      setSecurityModalBusy(false);
    }
  }

  function openItem(id: string) {
    window.history.pushState({}, '', `/items/${id}`);
    setSelectedId(id);
    setDeleteConfirm(false);
    setCopyMessage('');
    setInboxNotice('');
  }

  async function copyText(textToCopy?: string | null) {
    const text = textToCopy ?? detail?.text;
    if (!text) return;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
        setCopyMessage('Copied to clipboard.');
        setTimeout(() => setCopyMessage(''), 2500);
      } else {
        throw new Error('clipboard unavailable');
      }
    } catch {
      setCopyMessage('Clipboard access failed. Select the text above and copy it.');
    }
  }

  // Quick hover micro-action: copy text/link directly
  async function handleQuickCopy(item: ItemSummary, e: MouseEvent) {
    e.stopPropagation();
    try {
      let text = detailCache.current.get(item.id);
      if (!text) {
        const response = await fetch(`${API}/items/${encodeURIComponent(item.id)}`, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        });
        if (response.ok) {
          const itemDetail = await readJson<ItemDetail>(response);
          if (itemDetail?.text) {
            text = itemDetail.text;
            detailCache.current.set(item.id, itemDetail.text);
          }
        }
      }
      if (text && navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
        setCopiedItemId(item.id);
        setTimeout(() => setCopiedItemId(null), 2000);
      }
    } catch {
      //
    }
  }

  async function downloadAttachment(downloadUrlPath: string, name?: string | null) {
    setCopyMessage('Preparing attachment…');
    try {
      const url = new URL(downloadUrlPath, window.location.origin);
      if (url.origin !== window.location.origin || !url.pathname.startsWith(`${API}/`))
        throw new Error('Vault returned an invalid download address.');
      const response = await fetch(`${url.pathname}${url.search}`, {
        credentials: 'same-origin',
        cache: 'no-store',
      });
      if (response.status === 401) {
        signOutOnUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(await handleApiFailure(response));
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = objectUrl;
      link.download = name?.trim() || 'vault-attachment';
      link.rel = 'noopener';
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
      setCopyMessage('Attachment download started.');
      setTimeout(() => setCopyMessage(''), 2500);
    } catch (error) {
      setCopyMessage(error instanceof Error ? error.message : 'Could not download this attachment.');
    }
  }

  // Quick hover micro-action: download file directly
  function handleQuickDownload(item: ItemSummary, e: MouseEvent) {
    e.stopPropagation();
    void downloadAttachment(`${API}/items/${encodeURIComponent(item.id)}/download`, item.displayName);
  }

  // Quick hover micro-action: delete item with inline confirm
  async function handleQuickDelete(id: string, e: MouseEvent) {
    e.stopPropagation();
    if (rowDeleteConfirmId !== id) {
      setRowDeleteConfirmId(id);
      return;
    }
    // Confirmed delete
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/items/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json', [token.headerName]: token.token },
      });
      if (response.status === 401) {
        signOutOnUnauthorized();
        return;
      }
      if (response.status !== 204 && !response.ok) throw new Error(await handleApiFailure(response));
      setItems((prev) => prev.filter((i) => i.id !== id));
      if (selectedId === id) {
        setSelectedId(null);
        window.history.replaceState({}, '', '/');
      }
      setRowDeleteConfirmId(null);
      setInboxNotice('Item deleted.');
      setTimeout(() => setInboxNotice(''), 3000);
      void loadPage(null, false, generationRef.current);
    } catch (error) {
      setInboxNotice(error instanceof Error ? error.message : 'Could not delete item.');
    }
  }

  async function deleteDetailItem() {
    if (!detail || deleteBusy) return;
    setDeleteBusy(true);
    setDetailError('');
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/items/${encodeURIComponent(detail.id)}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json', [token.headerName]: token.token },
      });
      if (response.status === 401) {
        signOutOnUnauthorized();
        return;
      }
      if (response.status !== 204 && !response.ok) throw new Error(await handleApiFailure(response));
      const gen = generationRef.current;
      setItems((previous) => previous.filter((item) => item.id !== detail.id));
      setDeleteConfirm(false);
      setDetail(null);
      window.history.replaceState({}, '', '/');
      setSelectedId(null);
      setCopyMessage('');
      setInboxNotice('Item deleted.');
      setTimeout(() => setInboxNotice(''), 3000);
      await loadPage(null, false, gen);
    } catch (error) {
      setDetailError(
        error instanceof TypeError
          ? 'Vault is unreachable. Check your connection and retry.'
          : error instanceof Error
            ? error.message
            : 'Could not delete this item.'
      );
    } finally {
      setDeleteBusy(false);
    }
  }

  const bytePercent = quota ? Math.min(100, (quota.activeBytes / quota.maxActiveBytes) * 100) : 0;

  // Filter & Search calculations
  const filteredItems = useMemo(() => {
    return items.filter((item) => {
      if (filterType !== 'all' && item.type !== filterType) return false;
      if (searchQuery.trim()) {
        const query = searchQuery.trim().toLowerCase();
        const nameMatch = (item.displayName ?? '').toLowerCase().includes(query);
        const typeMatch = item.type.toLowerCase().includes(query);
        const deviceMatch = item.sourceDevice.name.toLowerCase().includes(query);
        const cachedText = detailCache.current.get(item.id)?.toLowerCase() ?? '';
        const textMatch = cachedText.includes(query);
        return nameMatch || typeMatch || deviceMatch || textMatch;
      }
      return true;
    });
  }, [items, filterType, searchQuery]);

  const fileCount = useMemo(() => items.filter((i) => i.type === 'file').length, [items]);
  const linkCount = useMemo(() => items.filter((i) => i.type === 'url').length, [items]);
  const textCount = useMemo(() => items.filter((i) => i.type === 'text').length, [items]);

  const avatarInitial = session?.email ? session.email.charAt(0).toUpperCase() : 'U';

  return (
    <main className="app-shell">
      {/* 56px Compact Topbar */}
      <header className="topbar">
        <div className="topbar-left">
          <a className="brand" href="/" aria-label="Vault home">
            <VaultMark />
            <span>vault</span>
          </a>
          {view === 'signed-in' && (
            <div
              className={`topbar-status-badge ${serviceOnline === false ? 'is-offline' : 'is-ready'}`}
              title={serviceOnline === false ? 'Service connection issue' : 'Vault personal relay is active and ready'}
            >
              <span className="status-dot-pulse" aria-hidden="true" />
              <span>{serviceOnline === false ? 'Offline' : 'Ready'}</span>
            </div>
          )}
        </div>

        {/* Center: Storage Pill Meter */}
        {view === 'signed-in' && (
          <div
            className="storage-pill"
            title={
              quota
                ? `${quota.activeItems.toLocaleString()} of ${quota.maxActiveItems.toLocaleString()} items · ${formatBytes(quota.activeBytes)} of ${formatBytes(quota.maxActiveBytes)} used`
                : 'Loading quota usage…'
            }
          >
            <div
              className="storage-pill-track"
              role="progressbar"
              aria-label="Storage quota usage"
              aria-valuemin={0}
              aria-valuemax={quota?.maxActiveBytes ?? 2_000_000_000}
              aria-valuenow={quota?.activeBytes ?? 0}
            >
              <span className="storage-pill-fill" style={{ width: `${bytePercent}%` }} />
            </div>
            <span className="storage-pill-text">
              {quota
                ? `${formatBytes(quota.activeBytes)} / ${formatBytes(quota.maxActiveBytes)} · ${bytePercent.toFixed(0)}%`
                : 'Storage'}
            </span>
          </div>
        )}

        {/* Right: Actions, Theme Toggle, User Menu */}
        <div className="topbar-right">
          {view === 'signed-in' && (
            <button
              type="button"
              className="topbar-action-btn"
              onClick={() => setPairModalOpen(true)}
              aria-label="Pair Android Phone"
            >
              <QrCodeIcon size={15} />
              <span className="hide-mobile">Pair Phone</span>
            </button>
          )}

          <button
            type="button"
            className="theme-toggle-btn"
            onClick={toggleTheme}
            aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
            title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
          >
            {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
          </button>

          {view === 'signed-in' && session && (
            <div className="user-menu-container" ref={userMenuRef}>
              <button
                type="button"
                className="user-menu-btn"
                onClick={() => setUserMenuOpen((prev) => !prev)}
                aria-expanded={userMenuOpen}
                aria-haspopup="true"
                aria-label="Account and session settings"
              >
                <span className="user-avatar-initial" aria-hidden="true">
                  {avatarInitial}
                </span>
                <span className="user-email-text hide-mobile">{session.email}</span>
                <ChevronDownIcon size={12} className={`chevron-indicator ${userMenuOpen ? 'is-open' : ''}`} />
              </button>

              {userMenuOpen && (
                <div className="user-menu-dropdown" role="menu">
                  <div className="dropdown-account-header">
                    <span className="account-email">{session.email}</span>
                    <span className="account-tag">
                      <span className="status-dot-pulse" aria-hidden="true" />
                      Web Client · Current session
                    </span>
                  </div>

                  <div className="dropdown-divider" role="separator" />

                  <button
                    type="button"
                    className="dropdown-item"
                    role="menuitem"
                    onClick={() => void handleLogout()}
                    disabled={busy}
                  >
                    <span>{busy ? 'Signing out…' : 'Sign out'}</span>
                  </button>

                  <div className="dropdown-divider" role="separator" />

                  <button
                    type="button"
                    className="dropdown-item dropdown-item--danger"
                    role="menuitem"
                    onClick={() => {
                      setUserMenuOpen(false);
                      setSecurityModalOpen(true);
                    }}
                  >
                    <ShieldLockIcon size={14} />
                    <span>Security: Revoke all devices…</span>
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </header>

      {/* Loading View */}
      {view === 'loading' && (
        <section className="loading-panel" aria-live="polite" aria-busy="true">
          <span className="loading-ring" aria-hidden="true" />
          <p>Restoring your private space…</p>
        </section>
      )}

      {/* Signed-out Auth View */}
      {view === 'signed-out' && (
        <section className="auth-layout" aria-labelledby="welcome-title">
          <div className="intro-panel">
            <p className="eyebrow">
              <span className="eyebrow-line" /> YOUR DEVICES, IN SYNC
            </p>
            <h1 id="welcome-title">
              A quieter way
              <br />
              to move things.
            </h1>
            <p className="intro">
              An encrypted personal relay between your Android phone and desktop browser.
              Sign in to access your Vault.
            </p>
            <div
              className={`service-status ${serviceOnline === false ? 'service-status--offline' : 'service-status--ready'}`}
              role="status"
            >
              <span className="status-dot" />
              <span>
                {serviceOnline === null
                  ? 'Checking Vault'
                  : serviceOnline
                    ? 'Vault service ready'
                    : 'Vault is having trouble connecting'}
              </span>
            </div>
          </div>
          <section className="auth-card" aria-labelledby="signin-title">
            <div className="auth-card-top">
              <span className="card-kicker">PRIVATE SPACE</span>
              <span className="secure-label">
                <ShieldLockIcon size={12} className="secure-icon" /> SECURE SIGN-IN
              </span>
            </div>
            <h2 id="signin-title">Welcome back</h2>
            <p className="auth-description">Enter your Vault credentials to continue.</p>
            <form onSubmit={handleLogin} noValidate>
              <div className="field-group">
                <label htmlFor="identifier">Email or username</label>
                <input
                  ref={identifierRef}
                  id="identifier"
                  name="identifier"
                  type="text"
                  autoComplete="username"
                  inputMode="text"
                  required
                  value={identifier}
                  onChange={(event) => setIdentifier(event.target.value)}
                  disabled={busy}
                  placeholder="Email address or username"
                />
              </div>
              <div className="field-group">
                <label htmlFor="password">Password</label>
                <input
                  id="password"
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  disabled={busy}
                />
              </div>
              <div
                className={`form-message ${message ? (messageKind === 'error' ? 'form-message--error' : 'form-message--notice') : ''}`}
                role={messageKind === 'error' ? 'alert' : 'status'}
                aria-live="polite"
              >
                {message}
              </div>
              <button
                className="primary-button"
                type="submit"
                disabled={busy || retrySeconds > 0 || !identifier.trim() || !password}
              >
                <span>
                  {busy ? 'Signing in…' : retrySeconds > 0 ? `Try again in ${retrySeconds}s` : 'Sign in'}
                </span>
                <ArrowRightIcon size={16} />
              </button>
            </form>
            <p className="card-footnote">
              <ShieldLockIcon size={13} className="footnote-icon" />
              Your credentials are only transmitted over this encrypted connection.
            </p>
          </section>
        </section>
      )}

      {/* Signed-in Zero-Waste Dashboard */}
      {view === 'signed-in' && session && (
        <section className="inbox-layout" aria-label="Vault Workspace">
          <div className={`vault-workspace ${selectedId ? 'vault-workspace--detail' : ''}`}>
            {/* Left Panel: Unified Compose Hub (380px–420px) */}
            <div className="send-stack">
              <ComposeHub
                onSuccess={() => void loadPage(null, false, generationRef.current)}
                onUnauthorized={signOutOnUnauthorized}
                fetchCsrf={fetchCsrf}
                handleApiFailure={handleApiFailure}
              />
            </div>

            {/* Right Panel: High-Density Inbox & Timeline */}
            <section className="items-panel" aria-label="Inbox items">
              <div className="items-heading">
                {selectedId ? (
                  <div className="detail-nav-header">
                    <button
                      type="button"
                      className="nav-back-button"
                      onClick={backToList}
                      aria-label="Back to items list"
                    >
                      <ArrowLeftIcon size={15} />
                      <span>Back to items</span>
                    </button>
                    <span className="breadcrumb-separator" aria-hidden="true">
                      /
                    </span>
                    <span className="breadcrumb-current">Item detail</span>
                    <span className="esc-key-hint hide-mobile">Esc</span>
                  </div>
                ) : (
                  <div className="inbox-toolbar-row">
                    <div className="inbox-title-wrap">
                      <h2>Inbox</h2>
                      <span className="inbox-count-badge" aria-label={`${items.length} items`}>
                        {items.length}
                      </span>
                    </div>

                    <div className="inbox-search-box">
                      <SearchIcon size={14} className="search-icon" />
                      <input
                        ref={searchInputRef}
                        type="text"
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        placeholder="Search items… (/ or ⌘K)"
                        aria-label="Search inbox items"
                      />
                      {searchQuery ? (
                        <button
                          type="button"
                          className="search-clear-btn"
                          onClick={() => {
                            setSearchQuery('');
                            searchInputRef.current?.focus();
                          }}
                          aria-label="Clear search"
                        >
                          <CloseIcon size={13} />
                        </button>
                      ) : (
                        <kbd className="search-kbd-hint hide-mobile">/</kbd>
                      )}
                    </div>

                    <button
                      className="secondary-button refresh-button"
                      type="button"
                      onClick={() => void loadPage(null, false)}
                      disabled={listBusy}
                      title="Refresh items list"
                      aria-label="Refresh items list"
                    >
                      <RefreshIcon size={14} className={listBusy ? 'icon-spin' : ''} />
                      <span className="hide-mobile">{listBusy ? 'Refreshing…' : 'Refresh'}</span>
                    </button>
                  </div>
                )}
              </div>

              {/* Filter Chips (Visible when not in detail view) */}
              {!selectedId && (
                <div className="filter-bar" role="tablist" aria-label="Filter items by type">
                  <button
                    type="button"
                    role="tab"
                    aria-selected={filterType === 'all'}
                    className={`filter-chip ${filterType === 'all' ? 'is-active' : ''}`}
                    onClick={() => setFilterType('all')}
                  >
                    <span>All</span>
                    <span className="filter-count">{items.length}</span>
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={filterType === 'file'}
                    className={`filter-chip ${filterType === 'file' ? 'is-active' : ''}`}
                    onClick={() => setFilterType('file')}
                  >
                    <span className="filter-chip-icon">
                      <FileIcon size={13} />
                    </span>
                    <span>Files</span>
                    <span className="filter-count">{fileCount}</span>
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={filterType === 'url'}
                    className={`filter-chip ${filterType === 'url' ? 'is-active' : ''}`}
                    onClick={() => setFilterType('url')}
                  >
                    <span className="filter-chip-icon">
                      <LinkIcon size={13} />
                    </span>
                    <span>Links</span>
                    <span className="filter-count">{linkCount}</span>
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={filterType === 'text'}
                    className={`filter-chip ${filterType === 'text' ? 'is-active' : ''}`}
                    onClick={() => setFilterType('text')}
                  >
                    <span className="filter-chip-icon">
                      <TextIcon size={13} />
                    </span>
                    <span>Text</span>
                    <span className="filter-count">{textCount}</span>
                  </button>
                </div>
              )}

              {/* Notices & Errors */}
              {!selectedId && inboxNotice && (
                <p className="inbox-notice" role="status" aria-live="polite">
                  {inboxNotice}
                </p>
              )}
              {listError && (
                <p className="panel-error" role="alert">
                  {listError}
                </p>
              )}

              {/* Items List (Dense 52px rows) */}
              {!selectedId && (
                <>
                  <ul className="item-list" aria-label="Inbox items, newest first">
                    {filteredItems.map((item) => {
                      const isConfirmingDelete = rowDeleteConfirmId === item.id;
                      const isCopied = copiedItemId === item.id;

                      return (
                        <li key={item.id}>
                          <div
                            className="item-row"
                            onClick={() => openItem(item.id)}
                            role="button"
                            tabIndex={0}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault();
                                openItem(item.id);
                              }
                            }}
                          >
                            <span className={`item-type-badge item-type-badge--${item.type}`} aria-hidden="true">
                              <ItemTypeIcon type={item.type} size={15} />
                            </span>

                            <div className="item-row-content">
                              <div className="item-row-title-line">
                                <strong className="item-title">
                                  {item.displayName?.trim() ||
                                    (item.type === 'url'
                                      ? 'Saved link'
                                      : item.type === 'file'
                                        ? 'File attachment'
                                        : 'Text note')}
                                </strong>
                              </div>
                              <div className="item-row-meta-line">
                                <span className="device-tag">
                                  {item.sourceDevice.platform === 'android' ? 'Android' : 'Web'}
                                </span>
                                <span className="meta-dot">·</span>
                                <span>{formatRelativeTime(item.createdAt)}</span>
                                <span className="meta-dot">·</span>
                                <span>{formatBytes(item.sizeBytes)}</span>
                                <span className="meta-dot">·</span>
                                <span className="expiry-tag">{formatExpiry(item.expiresAt)}</span>
                              </div>
                            </div>

                            {/* Quick Hover Micro-Actions */}
                            <div className="item-micro-actions" onClick={(e) => e.stopPropagation()}>
                              {item.type === 'file' ? (
                                <button
                                  type="button"
                                  className="micro-action-btn"
                                  onClick={(e) => handleQuickDownload(item, e)}
                                  title="Download attachment"
                                  aria-label="Download attachment"
                                >
                                  <DownloadIcon size={14} />
                                </button>
                              ) : (
                                <button
                                  type="button"
                                  className={`micro-action-btn ${isCopied ? 'is-copied' : ''}`}
                                  onClick={(e) => void handleQuickCopy(item, e)}
                                  title={isCopied ? 'Copied!' : 'Copy to clipboard'}
                                  aria-label="Copy to clipboard"
                                >
                                  {isCopied ? <CheckIcon size={14} /> : <CopyIcon size={14} />}
                                </button>
                              )}

                              {isConfirmingDelete ? (
                                <div className="inline-delete-confirm">
                                  <button
                                    type="button"
                                    className="inline-delete-yes"
                                    onClick={(e) => void handleQuickDelete(item.id, e)}
                                    title="Confirm deletion"
                                    aria-label="Confirm deletion"
                                  >
                                    Delete
                                  </button>
                                  <button
                                    type="button"
                                    className="inline-delete-no"
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      setRowDeleteConfirmId(null);
                                    }}
                                    title="Cancel"
                                    aria-label="Cancel"
                                  >
                                    <CloseIcon size={12} />
                                  </button>
                                </div>
                              ) : (
                                <button
                                  type="button"
                                  className="micro-action-btn micro-action-btn--danger"
                                  onClick={(e) => void handleQuickDelete(item.id, e)}
                                  title="Delete item"
                                  aria-label="Delete item"
                                >
                                  <TrashIcon size={14} />
                                </button>
                              )}

                              <button
                                type="button"
                                className="micro-action-btn"
                                onClick={() => openItem(item.id)}
                                title="Open details"
                                aria-label="Open details"
                              >
                                <ChevronRightIcon size={14} />
                              </button>
                            </div>
                          </div>
                        </li>
                      );
                    })}
                  </ul>

                  {/* Empty state: clear inbox */}
                  {!items.length && !listBusy && (
                    <div className="inbox-empty">
                      <span className="inbox-empty-icon" aria-hidden="true">
                        <InboxSparkleIcon size={36} />
                      </span>
                      <h3>Your inbox is empty.</h3>
                      <p>Add text, a link, or a file from the compose hub to store it securely in your Vault.</p>
                    </div>
                  )}

                  {/* Empty state: filter or search has no matches */}
                  {items.length > 0 && !filteredItems.length && !listBusy && (
                    <div className="inbox-empty">
                      <span className="inbox-empty-icon" aria-hidden="true">
                        <InboxSparkleIcon size={36} />
                      </span>
                      <h3>No matching items found.</h3>
                      <p>
                        {searchQuery
                          ? `No items match "${searchQuery}" in ${filterType === 'all' ? 'your inbox' : `${filterType} items`}.`
                          : `There are currently no ${filterType} items in your inbox.`}
                      </p>
                      <button
                        type="button"
                        className="secondary-button"
                        style={{ marginTop: '12px' }}
                        onClick={() => {
                          setFilterType('all');
                          setSearchQuery('');
                        }}
                      >
                        Reset filters
                      </button>
                    </div>
                  )}

                  {/* Skeleton loading indicator */}
                  {listBusy && !items.length && (
                    <div className="skeleton-container" role="status" aria-label="Loading your items…">
                      <div className="skeleton-card" />
                      <div className="skeleton-card" />
                      <div className="skeleton-card" />
                    </div>
                  )}

                  {/* Load more cursor pagination */}
                  {nextCursor && (
                    <button
                      className="load-more"
                      type="button"
                      disabled={listBusy}
                      onClick={() => void loadPage(nextCursor, true)}
                    >
                      {listBusy ? 'Loading…' : 'Load more items'}
                    </button>
                  )}
                </>
              )}

              {/* Master-Detail Drawer / View */}
              {selectedId && (
                <div className="detail-view">
                  {detailBusy && (
                    <div
                      className="skeleton-container skeleton-container--detail"
                      role="status"
                      aria-label="Loading item details…"
                    >
                      <div className="skeleton-card skeleton-card--detail-header" />
                      <div className="skeleton-card skeleton-card--detail-body" />
                      <div className="skeleton-card skeleton-card--detail-meta" />
                    </div>
                  )}
                  {detailError && (
                    <p className="panel-error" role="alert">
                      {detailError}
                    </p>
                  )}
                  {detail && (
                    <>
                      <div className="detail-title">
                        <span className={`item-type-badge item-type-badge--${detail.type}`} aria-hidden="true">
                          <ItemTypeIcon type={detail.type} size={18} />
                        </span>
                        <div>
                          <p className="card-kicker">
                            {detail.type === 'url' ? 'LINK' : detail.type === 'file' ? 'FILE ATTACHMENT' : 'TEXT'}
                          </p>
                          <h3>
                            {detail.displayName?.trim() ||
                              (detail.type === 'url'
                                ? 'Saved link'
                                : detail.type === 'file'
                                  ? 'File attachment'
                                  : 'Text item')}
                          </h3>
                        </div>
                      </div>

                      {detail.type === 'file' ? (
                        <dl className="detail-meta file-detail-meta">
                          <div>
                            <dt>File type</dt>
                            <dd>{detail.contentType}</dd>
                          </div>
                          <div>
                            <dt>SHA-256</dt>
                            <dd>
                              <code>{detail.sha256}</code>
                            </dd>
                          </div>
                        </dl>
                      ) : (
                        <pre className="detail-text" tabIndex={0} aria-label="Saved item text">
                          {detail.text}
                        </pre>
                      )}

                      <dl className="detail-meta">
                        <div>
                          <dt>Sent from</dt>
                          <dd>
                            {detail.sourceDevice.name} ·{' '}
                            {detail.sourceDevice.platform === 'web' ? 'This browser' : detail.sourceDevice.platform}
                          </dd>
                        </div>
                        <div>
                          <dt>Added</dt>
                          <dd>{formatDate(detail.createdAt)}</dd>
                        </div>
                        <div>
                          <dt>Expires</dt>
                          <dd>{formatDate(detail.expiresAt)}</dd>
                        </div>
                        <div>
                          <dt>Size</dt>
                          <dd>{formatBytes(detail.sizeBytes)}</dd>
                        </div>
                      </dl>

                      {copyMessage && (
                        <p className="copy-message" role="status" aria-live="polite">
                          {copyMessage}
                        </p>
                      )}

                      {!deleteConfirm ? (
                        <div className="detail-actions">
                          {detail.type === 'file' ? (
                            <button
                              className="primary-button"
                              type="button"
                              onClick={() => void downloadAttachment(detail.downloadUrl!, detail.displayName)}
                            >
                              <DownloadIcon size={16} />
                              <span>Download attachment</span>
                            </button>
                          ) : (
                            <button
                              className="primary-button"
                              type="button"
                              onClick={() => void copyText(detail.text)}
                            >
                              <CopyIcon size={16} />
                              <span>Copy text</span>
                            </button>
                          )}
                          <button
                            className="danger-button"
                            type="button"
                            onClick={() => setDeleteConfirm(true)}
                          >
                            <TrashIcon size={15} />
                            <span>Delete</span>
                          </button>
                        </div>
                      ) : (
                        <div className="delete-confirm" role="group" aria-label="Confirm item deletion">
                          <p>Permanently delete this item from your Vault?</p>
                          <button
                            className="danger-button"
                            type="button"
                            disabled={deleteBusy}
                            onClick={() => void deleteDetailItem()}
                          >
                            <TrashIcon size={15} />
                            <span>{deleteBusy ? 'Deleting…' : 'Delete item'}</span>
                          </button>
                          <button
                            className="secondary-button"
                            type="button"
                            disabled={deleteBusy}
                            onClick={() => setDeleteConfirm(false)}
                          >
                            Cancel
                          </button>
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}
            </section>
          </div>
        </section>
      )}

      {/* Footer */}
      <footer className="footer">
        <span>VAULT PERSONAL DATA RELAY · ZERO-KNOWLEDGE ARCHITECTURE</span>
        <span className="footer-mark">
          <span /> PRIVATE BY DESIGN
        </span>
      </footer>

      {/* Modals */}
      <PairDeviceModal
        isOpen={pairModalOpen}
        onClose={() => setPairModalOpen(false)}
        onUnauthorized={signOutOnUnauthorized}
      />

      <SecurityModal
        isOpen={securityModalOpen}
        onClose={() => setSecurityModalOpen(false)}
        onConfirm={handleRevokeEverywhere}
        busy={securityModalBusy}
        error={securityModalError}
      />
    </main>
  );
}

export default App;
