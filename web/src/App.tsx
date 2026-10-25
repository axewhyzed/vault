import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent, MouseEvent } from 'react';
import ComposeHub from './ComposeHub';
import PairDeviceModal from './PairDeviceModal';
import SecurityModal from './SecurityModal';
import ConnectedDevicesModal from './components/ConnectedDevicesModal';
import Topbar from './components/Topbar';
import AuthLayout from './components/AuthLayout';
import InboxList from './components/InboxList';
import ItemDetailPanel from './components/ItemDetailPanel';
import TourGuideModal from './components/TourGuideModal';
import { DEMO_DETAILS, DEMO_QUOTA, DEMO_SESSION, INITIAL_DEMO_ITEMS } from './demoData';
import { PlusIcon } from './Icons';
import type {
  ApiError,
  Csrf,
  ItemDetail,
  ItemPage,
  ItemSummary,
  Quota,
  Session,
  ViewState,
} from './types';
import { copyToClipboard, readJson, retryDelay } from './utils';

const API = '/api/v1';

function safePathId() {
  const match = window.location.pathname.match(/^\/items\/([0-9a-f-]{36})\/?$/i);
  return match?.[1] ?? null;
}

export default function App() {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    const saved = localStorage.getItem('vault-theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });

  const [view, setView] = useState<ViewState>('loading');
  const [session, setSession] = useState<Session | null>(null);
  const [isDemo, setIsDemo] = useState<boolean>(() => {
    return (
      typeof window !== 'undefined' &&
      (window.location.hash === '#demo' ||
        new URLSearchParams(window.location.search).get('demo') === 'true')
    );
  });
  const [tourOpen, setTourOpen] = useState<boolean>(() => {
    return (
      typeof window !== 'undefined' &&
      (window.location.hash === '#demo' ||
        new URLSearchParams(window.location.search).get('demo') === 'true')
    );
  });
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
  const [devicesModalOpen, setDevicesModalOpen] = useState(false);
  const [securityModalOpen, setSecurityModalOpen] = useState(false);
  const [securityModalBusy, setSecurityModalBusy] = useState(false);
  const [securityModalError, setSecurityModalError] = useState('');
  const [mobileComposeOpen, setMobileComposeOpen] = useState(false);

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
    detailCache.current.clear();
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
    if (isDemo) {
      if (mountedRef.current) {
        setSession(DEMO_SESSION);
        setQuota(DEMO_QUOTA);
        setItems(INITIAL_DEMO_ITEMS);
        setView('signed-in');
      }
      return;
    }
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
  }, [fetchCsrf, isDemo]);

  const enterDemo = useCallback(() => {
    setIsDemo(true);
    setSession(DEMO_SESSION);
    setQuota(DEMO_QUOTA);
    setItems(INITIAL_DEMO_ITEMS);
    setView('signed-in');
    setTourOpen(true);
    window.location.hash = 'demo';
  }, []);

  const exitDemo = useCallback(() => {
    setIsDemo(false);
    setTourOpen(false);
    setSession(null);
    setView('signed-out');
    setItems([]);
    setSelectedId(null);
    setDetail(null);
    if (window.location.hash === '#demo') {
      window.history.replaceState(null, '', window.location.pathname);
    }
  }, []);

  const handleDemoCreateItem = useCallback(
    (created: {
      type: 'text' | 'url' | 'file';
      displayName?: string;
      textContent?: string | null;
      sizeBytes: number;
    }) => {
      const newItemId = `demo-${Date.now()}`;
      const newItem: ItemSummary = {
        id: newItemId,
        type: created.type,
        state: 'available',
        displayName:
          created.displayName ||
          (created.type === 'file'
            ? 'Uploaded attachment'
            : created.type === 'url'
              ? 'Saved link'
              : 'Text note'),
        sizeBytes: created.sizeBytes,
        createdAt: new Date().toISOString(),
        availableAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
        version: 1,
        sourceDevice: { id: 'd-web', name: 'This Browser', platform: 'web' },
        textContent: created.textContent,
      };
      setItems((prev) => [newItem, ...prev]);
      setQuota((prev) =>
        prev
          ? {
              ...prev,
              activeItems: prev.activeItems + 1,
              activeBytes: prev.activeBytes + created.sizeBytes,
            }
          : prev
      );
    },
    []
  );

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

    const handleOnline = () => {
      if (mountedRef.current) setServiceOnline(true);
      void checkHealth();
    };
    const handleOffline = () => {
      if (mountedRef.current) setServiceOnline(false);
    };
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    // Prevent default browser file drop behavior that navigates away and destroys in-progress app state
    const handleGlobalDragOver = (e: DragEvent) => e.preventDefault();
    const handleGlobalDrop = (e: DragEvent) => e.preventDefault();
    window.addEventListener('dragover', handleGlobalDragOver);
    window.addEventListener('drop', handleGlobalDrop);

    return () => {
      mountedRef.current = false;
      invalidateRequests();
      window.clearInterval(interval);
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('dragover', handleGlobalDragOver);
      window.removeEventListener('drop', handleGlobalDrop);
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

  // Adaptive periodic inbox polling: sync every 8 seconds when active, paused in background
  useEffect(() => {
    if (view !== 'signed-in' || !session) return undefined;

    let timer: number | null = null;

    const pollItems = async () => {
      if (document.visibilityState !== 'visible') return;
      try {
        const response = await fetch(`${API}/items?limit=30`, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        });
        if (response.status === 401) {
          signOutOnUnauthorized();
          return;
        }
        if (response.ok) {
          const data = await readJson<ItemPage>(response);
          if (data && Array.isArray(data.items) && data.quota) {
            setItems(data.items);
            setQuota(data.quota);
          }
        }
      } catch {
        // Tolerated in background sync
      }
    };

    timer = window.setInterval(pollItems, 8_000);

    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        void pollItems();
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);

    return () => {
      if (timer) window.clearInterval(timer);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [view, session, signOutOnUnauthorized]);

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

  const openItem = useCallback((id: string) => {
    window.history.pushState({}, '', `/items/${id}`);
    setSelectedId(id);
    setDeleteConfirm(false);
    setCopyMessage('');
    setInboxNotice('');
  }, []);

  useEffect(() => {
    const handlePopState = () => {
      setSelectedId(safePathId());
      setDeleteConfirm(false);
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  // Global keyboard shortcuts: / or Cmd+K to focus search; Esc to back out; ArrowDown/j & ArrowUp/k to navigate inbox
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

      const isAnyModalOpen = pairModalOpen || devicesModalOpen || securityModalOpen;

      if (event.key === 'Escape') {
        if (userMenuOpen) {
          setUserMenuOpen(false);
          return;
        }
        if (selectedId && !isAnyModalOpen) {
          backToList();
          return;
        }
        if (searchQuery && document.activeElement === searchInputRef.current) {
          setSearchQuery('');
        }
        return;
      }

      // List keyboard navigation: ArrowUp/k, ArrowDown/j when not in an input and no modal open
      if (!isInput && !isAnyModalOpen && items.length > 0) {
        if (event.key === 'ArrowDown' || (event.key === 'j' && !event.metaKey && !event.ctrlKey)) {
          event.preventDefault();
          const currentIndex = selectedId ? items.findIndex((i) => i.id === selectedId) : -1;
          const nextIndex = currentIndex < items.length - 1 ? currentIndex + 1 : 0;
          const nextItem = items[nextIndex];
          if (nextItem) openItem(nextItem.id);
        } else if (event.key === 'ArrowUp' || (event.key === 'k' && !event.metaKey && !event.ctrlKey)) {
          event.preventDefault();
          const currentIndex = selectedId ? items.findIndex((i) => i.id === selectedId) : -1;
          const prevIndex = currentIndex > 0 ? currentIndex - 1 : items.length - 1;
          const prevItem = items[prevIndex];
          if (prevItem) openItem(prevItem.id);
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [
    selectedId,
    pairModalOpen,
    devicesModalOpen,
    securityModalOpen,
    userMenuOpen,
    searchQuery,
    backToList,
    items,
    openItem,
  ]);

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
    if (isDemo || gen !== generationRef.current) return;
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

    if (isDemo) {
      const demoDetail = DEMO_DETAILS[id] || {
        ...(items.find((x) => x.id === id) || INITIAL_DEMO_ITEMS[0]),
        text: items.find((x) => x.id === id)?.textContent || null,
        contentType:
          items.find((x) => x.id === id)?.type === 'file'
            ? 'application/octet-stream'
            : 'text/plain',
        sha256:
          items.find((x) => x.id === id)?.type === 'file'
            ? '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08'
            : undefined,
        downloadUrl: '#demo-download',
      };
      setDetail(demoDetail);
      setDetailBusy(false);
      return;
    }

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
      if (gen === generationRef.current && detailRequest === detailRequestRef.current) {
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
        detailCache.current.clear();
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
      detailCache.current.clear();
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

  async function copyText(textToCopy?: string | null) {
    const text = textToCopy ?? detail?.text;
    if (!text) return;
    const ok = await copyToClipboard(text);
    if (ok) {
      setCopyMessage('Copied to clipboard.');
      setTimeout(() => setCopyMessage(''), 2500);
    } else {
      setCopyMessage('Clipboard access failed. Select the text above and copy it.');
    }
  }

  async function handleQuickCopy(item: ItemSummary, e: MouseEvent) {
    e.stopPropagation();
    if (isDemo) {
      const text = item.textContent || (item.type === 'url' ? item.displayName : 'Sample Vault item');
      if (text) {
        const ok = await copyToClipboard(text);
        if (ok) {
          setCopiedItemId(item.id);
          setTimeout(() => setCopiedItemId(null), 2000);
        }
      }
      return;
    }
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
      if (text) {
        const ok = await copyToClipboard(text);
        if (ok) {
          setCopiedItemId(item.id);
          setTimeout(() => setCopiedItemId(null), 2000);
        } else {
          setInboxNotice('Could not copy to clipboard.');
          setTimeout(() => setInboxNotice(''), 2500);
        }
      }
    } catch {
      setInboxNotice('Could not copy to clipboard.');
      setTimeout(() => setInboxNotice(''), 2500);
    }
  }

  function downloadAttachment(downloadUrlPath: string, name?: string | null) {
    if (isDemo) {
      const blob = new Blob([`Vault Demo Attachment Content: ${name || 'sample'}`], { type: 'text/plain' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = name?.trim() || 'vault-demo-attachment.txt';
      document.body.append(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setCopyMessage('Download started (Demo).');
      setTimeout(() => setCopyMessage(''), 2500);
      return;
    }
    setCopyMessage('Starting download…');
    try {
      const url = new URL(downloadUrlPath, window.location.origin);
      if (url.origin !== window.location.origin || !url.pathname.startsWith(`${API}/`))
        throw new Error('Vault returned an invalid download address.');
      const link = document.createElement('a');
      link.href = `${url.pathname}${url.search}`;
      link.download = name?.trim() || 'vault-attachment';
      link.rel = 'noopener';
      document.body.append(link);
      link.click();
      link.remove();
      setCopyMessage('Download started.');
      setTimeout(() => setCopyMessage(''), 2500);
    } catch (error) {
      setCopyMessage(error instanceof Error ? error.message : 'Could not download this attachment.');
    }
  }

  function handleQuickDownload(item: ItemSummary, e: MouseEvent) {
    e.stopPropagation();
    downloadAttachment(`${API}/items/${encodeURIComponent(item.id)}/download`, item.displayName);
  }

  async function handleQuickDelete(id: string, e: MouseEvent) {
    e.stopPropagation();
    if (rowDeleteConfirmId !== id) {
      setRowDeleteConfirmId(id);
      return;
    }
    if (isDemo) {
      setItems((prev) => prev.filter((i) => i.id !== id));
      if (selectedId === id) {
        setSelectedId(null);
        window.history.replaceState({}, '', '/');
      }
      setRowDeleteConfirmId(null);
      setInboxNotice('Item deleted (Demo).');
      setTimeout(() => setInboxNotice(''), 3000);
      return;
    }
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
      detailCache.current.delete(id);
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
    if (isDemo) {
      setItems((previous) => previous.filter((item) => item.id !== detail.id));
      setDeleteConfirm(false);
      setDetail(null);
      window.history.replaceState({}, '', '/');
      setSelectedId(null);
      setCopyMessage('');
      setInboxNotice('Item deleted (Demo).');
      setTimeout(() => setInboxNotice(''), 3000);
      return;
    }
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
      detailCache.current.delete(detail.id);
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

  const filteredItems = useMemo(() => {
    return items.filter((item) => {
      if (filterType !== 'all' && item.type !== filterType) return false;
      if (searchQuery.trim()) {
        const query = searchQuery.trim().toLowerCase();
        const nameMatch = (item.displayName ?? '').toLowerCase().includes(query);
        const deviceMatch = item.sourceDevice.name.toLowerCase().includes(query);
        const content = (item.textContent ?? detailCache.current.get(item.id) ?? '').toLowerCase();
        const contentMatch = content.includes(query);

        if (item.type === 'text' || item.type === 'url') {
          return contentMatch || nameMatch || deviceMatch;
        } else {
          // File: search by file name, extension, and device
          return nameMatch || deviceMatch;
        }
      }
      return true;
    });
  }, [items, filterType, searchQuery]);

  const fileCount = useMemo(() => items.filter((i) => i.type === 'file').length, [items]);
  const linkCount = useMemo(() => items.filter((i) => i.type === 'url').length, [items]);
  const textCount = useMemo(() => items.filter((i) => i.type === 'text').length, [items]);

  return (
    <main className={`app-shell ${view === 'signed-out' ? 'app-shell--signed-out' : ''}`}>
      <Topbar
        view={view}
        session={session}
        quota={quota}
        serviceOnline={serviceOnline}
        theme={theme}
        toggleTheme={toggleTheme}
        userMenuOpen={userMenuOpen}
        setUserMenuOpen={setUserMenuOpen}
        userMenuRef={userMenuRef}
        busy={busy}
        onOpenDevicesModal={() => {
          setPairModalOpen(false);
          setSecurityModalOpen(false);
          setDevicesModalOpen(true);
        }}
        onOpenPairModal={() => {
          setDevicesModalOpen(false);
          setSecurityModalOpen(false);
          setPairModalOpen(true);
        }}
        onOpenSecurityModal={() => {
          setDevicesModalOpen(false);
          setPairModalOpen(false);
          setSecurityModalOpen(true);
        }}
        onLogout={() => void handleLogout()}
        isDemo={isDemo}
        onExitDemo={exitDemo}
      />

      {serviceOnline === false && (
        <div className="global-offline-banner" role="status">
          <span>You are currently working offline. Reconnecting to Vault…</span>
        </div>
      )}

      {/* Loading View */}
      {view === 'loading' && (
        <section className="loading-panel" aria-live="polite" aria-busy="true">
          <span className="loading-ring" aria-hidden="true" />
          <p>Restoring your private space…</p>
        </section>
      )}

      {/* Signed-out Auth View */}
      {view === 'signed-out' && (
        <AuthLayout
          serviceOnline={serviceOnline}
          identifier={identifier}
          setIdentifier={setIdentifier}
          password={password}
          setPassword={setPassword}
          busy={busy}
          retrySeconds={retrySeconds}
          message={message}
          messageKind={messageKind}
          identifierRef={identifierRef}
          onLogin={handleLogin}
          onEnterDemo={enterDemo}
        />
      )}

      {/* Signed-in Zero-Waste Dashboard */}
      {view === 'signed-in' && session && (
        <section className="inbox-layout" aria-label="Vault Workspace">
          {isDemo && (
            <div className="demo-ribbon" role="region" aria-label="Demo mode banner">
              <div className="demo-ribbon-left">
                <span className="demo-ribbon-pill font-mono">DEMO</span>
                <span className="demo-ribbon-text">
                  You are previewing Vault in demo mode. All data is stored locally in memory.
                </span>
              </div>
              <div className="demo-ribbon-actions">
                <button
                  type="button"
                  className="demo-ribbon-btn"
                  onClick={() => setTourOpen(true)}
                  title="Replay guided tour walkthrough"
                >
                  Tour walkthrough
                </button>
                <button
                  type="button"
                  className="demo-ribbon-btn demo-ribbon-btn--primary"
                  onClick={exitDemo}
                  title="Exit demo mode and go to sign in"
                >
                  Sign in
                </button>
              </div>
            </div>
          )}
          <div className={`vault-workspace ${selectedId ? 'vault-workspace--detail' : ''}`}>
            {/* Left Panel: Unified Compose Hub (360px–400px) */}
            <div className={`send-stack ${!mobileComposeOpen ? 'mobile-collapsed' : ''}`}>
              <button
                type="button"
                className="mobile-compose-toggle"
                onClick={() => setMobileComposeOpen((prev) => !prev)}
                aria-expanded={mobileComposeOpen}
              >
                <PlusIcon size={14} />
                <span>{mobileComposeOpen ? 'Hide Compose' : '＋ New Transfer to Vault'}</span>
              </button>
              <ComposeHub
                onSuccess={() => {
                  setMobileComposeOpen(false);
                  void loadPage(null, false, generationRef.current);
                }}
                onUnauthorized={signOutOnUnauthorized}
                fetchCsrf={fetchCsrf}
                handleApiFailure={handleApiFailure}
                isDemo={isDemo}
                onDemoCreateItem={handleDemoCreateItem}
              />
            </div>

            {/* Right Panel: High-Density Inbox & Master-Detail Timeline */}
            <section className="items-panel" aria-label="Inbox items">
              <div className={`items-master-detail ${selectedId ? 'has-detail' : ''}`}>
                <div className="inbox-master-column">
                  <InboxList
                    items={items}
                    filteredItems={filteredItems}
                    filterType={filterType}
                    setFilterType={setFilterType}
                    fileCount={fileCount}
                    linkCount={linkCount}
                    textCount={textCount}
                    searchQuery={searchQuery}
                    setSearchQuery={setSearchQuery}
                    searchInputRef={searchInputRef}
                    listBusy={listBusy}
                    listError={listError}
                    inboxNotice={inboxNotice}
                    nextCursor={nextCursor}
                    onRefresh={() => void loadPage(null, false)}
                    onLoadMore={() => void loadPage(nextCursor, true)}
                    onOpenItem={openItem}
                    rowDeleteConfirmId={rowDeleteConfirmId}
                    setRowDeleteConfirmId={setRowDeleteConfirmId}
                    copiedItemId={copiedItemId}
                    onQuickDownload={handleQuickDownload}
                    onQuickCopy={handleQuickCopy}
                    onQuickDelete={handleQuickDelete}
                    selectedId={selectedId}
                  />
                </div>
                {selectedId && (
                  <div className="inbox-detail-column">
                    <ItemDetailPanel
                      detail={detail}
                      detailBusy={detailBusy}
                      detailError={detailError}
                      deleteConfirm={deleteConfirm}
                      deleteBusy={deleteBusy}
                      copyMessage={copyMessage}
                      onBack={backToList}
                      onCopyText={copyText}
                      onDownload={downloadAttachment}
                      onSetDeleteConfirm={setDeleteConfirm}
                      onDelete={() => void deleteDetailItem()}
                    />
                  </div>
                )}
              </div>
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
      <ConnectedDevicesModal
        isOpen={devicesModalOpen}
        onClose={() => setDevicesModalOpen(false)}
        onOpenPairModal={() => {
          setDevicesModalOpen(false);
          setPairModalOpen(true);
        }}
        onOpenSecurityModal={() => {
          setDevicesModalOpen(false);
          setSecurityModalOpen(true);
        }}
        onUnauthorized={signOutOnUnauthorized}
        fetchCsrf={fetchCsrf}
        isDemo={isDemo}
      />

      <PairDeviceModal
        isOpen={pairModalOpen}
        onClose={() => setPairModalOpen(false)}
        onUnauthorized={signOutOnUnauthorized}
        isDemo={isDemo}
        onDevicePaired={() => {
          setInboxNotice('Android phone paired successfully!');
          setTimeout(() => setInboxNotice(''), 4000);
          void loadPage(null, false, generationRef.current);
        }}
      />

      <SecurityModal
        isOpen={securityModalOpen}
        onClose={() => setSecurityModalOpen(false)}
        onConfirm={handleRevokeEverywhere}
        busy={securityModalBusy}
        error={securityModalError}
      />

      <TourGuideModal
        isOpen={tourOpen}
        onClose={() => setTourOpen(false)}
        onFinish={() => setTourOpen(false)}
      />
    </main>
  );
}
