import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';

type Session = { userId: string; email: string; deviceId: string };
type Csrf = { token: string; headerName: 'X-CSRF-TOKEN' };
type ViewState = 'loading' | 'signed-out' | 'signed-in';
type ItemSummary = {
  id: string; type: 'text' | 'url'; state: string; displayName: string | null; sizeBytes: number;
  createdAt: string; availableAt: string; expiresAt: string; version: number;
  sourceDevice: { id: string; name: string; platform: string };
};
type ItemDetail = ItemSummary & { text: string };
type Quota = { activeBytes: number; maxActiveBytes: number; activeItems: number; maxActiveItems: number };
type ItemPage = { items: ItemSummary[]; nextCursor: string | null; quota: Quota };
type ApiError = { code?: string; message?: string; correlationId?: string };
type SendIntent = { key: string; kind: 'text' | 'url'; body: { text?: string; url?: string; displayName?: string; retentionDays: number } };

const API = '/api/v1';

function VaultMark() {
  return <svg aria-hidden="true" viewBox="0 0 32 32" className="vault-mark"><rect x="2" y="2" width="28" height="28" rx="9" fill="currentColor" /><path d="M9 11.25h2.55l4.45 10.1 4.45-10.1H23l-6.05 13.5h-1.9L9 11.25Z" fill="#f5f4ef" /><circle cx="23.1" cy="8.9" r="1.35" fill="#b9d48e" /></svg>;
}

async function readJson<T>(response: Response): Promise<T | null> {
  try { return await response.json() as T; } catch { return null; }
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
  return Number.isNaN(date.getTime()) ? 'Unknown' : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function formatBytes(value: number) {
  if (value < 1000) return `${value} B`;
  const units = ['KB', 'MB', 'GB'];
  let size = value / 1000;
  let unit = 0;
  while (size >= 1000 && unit < units.length - 1) { size /= 1000; unit += 1; }
  return `${size >= 100 ? Math.round(size) : size.toFixed(1)} ${units[unit]}`;
}

function safePathId() {
  const match = window.location.pathname.match(/^\/items\/([0-9a-f-]{36})\/?$/i);
  return match?.[1] ?? null;
}

function App() {
  const [view, setView] = useState<ViewState>('loading');
  const [session, setSession] = useState<Session | null>(null);
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [messageKind, setMessageKind] = useState<'error' | 'notice'>('error');
  const [retrySeconds, setRetrySeconds] = useState(0);
  const [serviceOnline, setServiceOnline] = useState<boolean | null>(null);
  const identifierRef = useRef<HTMLInputElement>(null);
  const inboxHeadingRef = useRef<HTMLHeadingElement>(null);
  const mountedRef = useRef(true);
  const generationRef = useRef(0);
  const detailRequestRef = useRef(0);
  const [items, setItems] = useState<ItemSummary[]>([]);
  const [quota, setQuota] = useState<Quota | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [listBusy, setListBusy] = useState(false);
  const [listError, setListError] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(() => safePathId());
  const [detail, setDetail] = useState<ItemDetail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [kind, setKind] = useState<'text' | 'url'>('text');
  const [content, setContent] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [retentionDays, setRetentionDays] = useState(7);
  const [sendBusy, setSendBusy] = useState(false);
  const [sendMessage, setSendMessage] = useState('');
  const [sendError, setSendError] = useState(false);
  const [retryIntent, setRetryIntent] = useState<SendIntent | null>(null);
  const [copyMessage, setCopyMessage] = useState('');
  const [inboxNotice, setInboxNotice] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);

  const publishError = (text: string) => { setMessageKind('error'); setMessage(text); };
  const invalidateRequests = () => { generationRef.current += 1; };

  const fetchCsrf = useCallback(async () => {
    const response = await fetch(`${API}/auth/csrf`, { method: 'GET', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!response.ok) {
      if (response.status === 429) throw new Error(`Too many requests. Try again in ${retryDelay(response)} seconds.`);
      throw new Error(response.status === 403 ? 'Vault could not verify this browser request. Refresh the page and try again.' : 'Vault could not prepare a secure request. Check your connection and try again.');
    }
    const data = await readJson<Csrf>(response);
    if (!data?.token || data.headerName !== 'X-CSRF-TOKEN') throw new Error('Vault returned an invalid security token. Refresh the page and try again.');
    return data;
  }, []);

  const signOutOnUnauthorized = useCallback(() => {
    invalidateRequests();
    detailRequestRef.current += 1;
    setSession(null); setView('signed-out'); setItems([]); setQuota(null); setDetail(null); setSelectedId(null);
    setContent(''); setDisplayName(''); setRetryIntent(null); setSendMessage(''); setSendError(false); setSendBusy(false);
    setCopyMessage(''); setInboxNotice(''); setDeleteConfirm(false);
    setMessageKind('error'); setMessage('Your session has expired. Sign in again to continue.');
    if (window.location.pathname !== '/') window.history.replaceState({}, '', '/');
    void fetchCsrf().catch(() => undefined);
  }, [fetchCsrf]);

  const restoreSession = useCallback(async () => {
    try {
      const response = await fetch(`${API}/auth/me`, { method: 'GET', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
      if (response.status === 401) {
        if (mountedRef.current) { setSession(null); setView('signed-out'); }
        await fetchCsrf(); return;
      }
      if (!response.ok) throw new Error('Vault could not restore your session. Try refreshing the page.');
      const data = await readJson<Session>(response);
      if (!data?.userId || !data.email || !data.deviceId) throw new Error('Vault returned an incomplete session.');
      if (mountedRef.current) { setSession(data); setView('signed-in'); void fetchCsrf().catch(() => undefined); }
    } catch (error) {
      if (mountedRef.current) { setSession(null); setView('signed-out'); publishError(error instanceof TypeError ? 'Vault is unreachable. Check your connection and try again.' : error instanceof Error ? error.message : 'Vault could not restore your session.'); }
    }
  }, [fetchCsrf]);

  useEffect(() => {
    mountedRef.current = true; void restoreSession();
    const checkHealth = async () => { try { const response = await fetch('/health/ready', { cache: 'no-store' }); if (mountedRef.current) setServiceOnline(response.ok); } catch { if (mountedRef.current) setServiceOnline(false); } };
    void checkHealth(); const interval = window.setInterval(checkHealth, 15_000);
    return () => { mountedRef.current = false; invalidateRequests(); window.clearInterval(interval); };
  }, [restoreSession]);

  useEffect(() => {
    if (view !== 'signed-in' || !session) return;
    const gen = ++generationRef.current;
    void loadPage(null, false, gen);
    return () => { if (generationRef.current === gen) generationRef.current += 1; };
    // Loading inbox data is tied to the signed-in session; route changes retain loaded pages.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, session?.userId]);

  useEffect(() => {
    if (view !== 'signed-in' || !session) return;
    if (!selectedId) {
      detailRequestRef.current += 1;
      setDetail(null); setDetailError('');
      if (window.location.pathname !== '/') window.history.replaceState({}, '', '/');
      return;
    }
    void loadDetail(selectedId, generationRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, session?.userId, selectedId]);

  useEffect(() => {
    const handlePopState = () => { setSelectedId(safePathId()); setDeleteConfirm(false); };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  useEffect(() => { if (retrySeconds <= 0) return undefined; const timer = window.setTimeout(() => setRetrySeconds((seconds) => Math.max(0, seconds - 1)), 1000); return () => window.clearTimeout(timer); }, [retrySeconds]);
  useEffect(() => { if (view === 'signed-out') identifierRef.current?.focus(); if (view === 'signed-in') inboxHeadingRef.current?.focus(); }, [view]);

  async function handleApiFailure(response: Response) {
    if (response.status === 401) { signOutOnUnauthorized(); return 'Your session has expired. Sign in again to continue.'; }
    const error = await readJson<ApiError>(response);
    if (response.status === 403) return error?.message || 'Vault could not verify this browser request. Refresh and try again.';
    if (response.status === 429) return `Please wait ${retryDelay(response)} seconds before trying again.`;
    const known: Record<number, string> = { 400: 'The request was not valid.', 404: 'This item is no longer available.', 410: 'This item has expired.', 413: 'This item is too large.', 422: 'Check the item details and try again.', 409: error?.code === 'quota_exceeded' ? 'Your Vault storage quota is full. Delete an item or try again later.' : error?.code === 'idempotency_conflict' ? 'This retry no longer matches the original send. Start a new send.' : 'Vault could not accept this request.' };
    return error?.message || known[response.status] || 'Vault could not complete the request. Try again.';
  }

  async function loadPage(cursor: string | null, append: boolean, gen = generationRef.current) {
    if (gen !== generationRef.current) return;
    setListBusy(true); setListError('');
    try {
      const query = cursor ? `?limit=30&cursor=${encodeURIComponent(cursor)}` : '?limit=30';
      const response = await fetch(`${API}/items${query}`, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
      if (gen !== generationRef.current) return;
      if (response.status === 401) { signOutOnUnauthorized(); return; }
      if (!response.ok) throw new Error(await handleApiFailure(response));
      const data = await readJson<ItemPage>(response);
      if (!data || !Array.isArray(data.items) || !data.quota) throw new Error('Vault returned an invalid inbox response.');
      if (gen === generationRef.current) { setItems((previous) => append ? [...previous, ...data.items] : data.items); setNextCursor(data.nextCursor); setQuota(data.quota); }
    } catch (error) { if (gen === generationRef.current) setListError(error instanceof TypeError ? 'Vault is unreachable. Check your connection and retry.' : error instanceof Error ? error.message : 'Could not load your inbox.'); }
    finally { if (gen === generationRef.current) setListBusy(false); }
  }

  async function loadDetail(id: string, gen = generationRef.current) {
    const detailRequest = ++detailRequestRef.current;
    setDetail(null); setDetailError(''); setDetailBusy(true);
    try {
      const response = await fetch(`${API}/items/${encodeURIComponent(id)}`, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
      if (gen !== generationRef.current || detailRequest !== detailRequestRef.current) return;
      if (response.status === 401) { signOutOnUnauthorized(); return; }
      if (response.status === 404 || response.status === 410) { const messageText = response.status === 410 ? 'This item has expired.' : 'This item could not be found.'; setDetailError(messageText); setItems((previous) => previous.filter((item) => item.id !== id)); if (window.location.pathname !== '/') { window.history.replaceState({}, '', '/'); setSelectedId(null); } return; }
      if (!response.ok) throw new Error(await handleApiFailure(response));
      const data = await readJson<ItemDetail>(response);
      if (!data || typeof data.text !== 'string') throw new Error('Vault returned an invalid item response.');
      if (gen === generationRef.current) setDetail(data);
    } catch (error) { if (gen === generationRef.current && detailRequest === detailRequestRef.current) setDetailError(error instanceof TypeError ? 'Vault is unreachable. Check your connection and retry.' : error instanceof Error ? error.message : 'Could not load this item.'); }
    finally { if (gen === generationRef.current && detailRequest === detailRequestRef.current) setDetailBusy(false); }
  }

  async function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || retrySeconds > 0) return; setBusy(true); setMessage('');
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/auth/login`, { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json', Accept: 'application/json', [token.headerName]: token.token }, body: JSON.stringify({ identifier: identifier.trim(), password }) });
      if (response.status === 401) { publishError('That email or username and password did not match. Check them and try again.'); setPassword(''); return; }
      if (response.status === 403) { publishError('Vault could not verify this sign-in request. Refresh the security token and try again.'); return; }
      if (response.status === 429) { const delay = retryDelay(response); setRetrySeconds(delay); publishError(`Too many sign-in attempts. Try again in ${delay} seconds.`); return; }
      if (!response.ok) throw new Error('Vault could not sign you in. Try again.');
      setPassword(''); await restoreSession();
    } catch (error) { publishError(error instanceof TypeError ? 'Vault is unreachable. Check your connection and try again.' : error instanceof Error ? error.message : 'Vault could not sign you in.'); }
    finally { setBusy(false); }
  }

  async function handleLogout() {
    if (busy) return; setBusy(true); setMessage('');
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/auth/logout`, { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json', [token.headerName]: token.token } });
      if (response.status === 401 || response.ok) { invalidateRequests(); detailRequestRef.current += 1; setSession(null); setView('signed-out'); setItems([]); setDetail(null); setSelectedId(null); setContent(''); setDisplayName(''); setRetryIntent(null); setSendMessage(''); setSendError(false); setSendBusy(false); setCopyMessage(''); setInboxNotice(''); setDeleteConfirm(false); setMessageKind('notice'); setMessage('You are signed out.'); if (window.location.pathname !== '/') window.history.replaceState({}, '', '/'); await fetchCsrf(); return; }
      publishError(await handleApiFailure(response));
    } catch (error) { publishError(error instanceof TypeError ? 'Vault is unreachable. Check your connection and try again.' : error instanceof Error ? error.message : 'Vault could not sign you out.'); }
    finally { setBusy(false); }
  }

  function resetSendRetry() { setRetryIntent(null); if (sendMessage.startsWith('Vault may have received')) setSendMessage(''); }

  async function sendItem(event?: FormEvent<HTMLFormElement>, retry = false) {
    event?.preventDefault(); if (sendBusy) return;
    const trimmed = content.trim();
    const intent: SendIntent = retry && retryIntent ? retryIntent : { key: crypto.randomUUID(), kind, body: { ...(kind === 'text' ? { text: content } : { url: trimmed }), ...(displayName.trim() ? { displayName: displayName.trim() } : {}), retentionDays } };
    if (!trimmed) { setSendError(true); setSendMessage(kind === 'url' ? 'Enter a URL to send.' : 'Enter some text to send.'); return; }
    if (kind === 'text' && new TextEncoder().encode(content).byteLength > 256 * 1024) { setSendError(true); setSendMessage('Text exceeds the 256 KiB UTF-8 limit. Shorten it and try again.'); return; }
    if (kind === 'url') { try { const parsed = new URL(trimmed); if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error(); } catch { setSendError(true); setSendMessage('Enter a valid http or https URL.'); return; } }
    setSendBusy(true); setSendMessage(''); setSendError(false);
    const gen = generationRef.current;
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/items/${intent.kind}`, { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json', Accept: 'application/json', [token.headerName]: token.token, 'Idempotency-Key': intent.key }, body: JSON.stringify(intent.body) });
      if (gen !== generationRef.current) return;
      if (response.status === 401) { signOutOnUnauthorized(); return; }
      if (!response.ok) { const text = await handleApiFailure(response); setSendError(true); setSendMessage(text); if (response.status >= 500) setRetryIntent(intent); else setRetryIntent(null); return; }
      setRetryIntent(null); setContent(''); setDisplayName(''); setSendMessage('Item sent to your inbox.'); setSendError(false); await loadPage(null, false, gen);
    } catch (error) {
      if (gen !== generationRef.current) return;
      setRetryIntent(intent); setSendError(true); setSendMessage(error instanceof TypeError ? 'Vault may have received this item, but the reply was lost. Retry the same item to avoid a duplicate.' : error instanceof Error ? error.message : 'Vault may have received this item. Retry the same item to avoid a duplicate.');
    } finally { if (gen === generationRef.current) setSendBusy(false); }
  }

  function openItem(id: string) { window.history.pushState({}, '', `/items/${id}`); setSelectedId(id); setDeleteConfirm(false); setCopyMessage(''); setInboxNotice(''); }
  function backToList() { window.history.pushState({}, '', '/'); setSelectedId(null); setDeleteConfirm(false); setDetailError(''); }

  async function copyText() {
    if (!detail) return;
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(detail.text);
      else throw new Error('clipboard unavailable');
      setCopyMessage('Copied to clipboard.');
    } catch { setCopyMessage('Clipboard access failed. Select the text above and copy it.'); }
  }

  async function deleteItem() {
    if (!detail || deleteBusy) return; setDeleteBusy(true); setDetailError('');
    try {
      const token = await fetchCsrf();
      const response = await fetch(`${API}/items/${encodeURIComponent(detail.id)}`, { method: 'DELETE', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json', [token.headerName]: token.token } });
      if (response.status === 401) { signOutOnUnauthorized(); return; }
      if (response.status !== 204 && !response.ok) throw new Error(await handleApiFailure(response));
      const gen = generationRef.current; setItems((previous) => previous.filter((item) => item.id !== detail.id)); setDeleteConfirm(false); setDetail(null); window.history.replaceState({}, '', '/'); setSelectedId(null); setCopyMessage(''); setInboxNotice('Item deleted.'); await loadPage(null, false, gen);
    } catch (error) { setDetailError(error instanceof TypeError ? 'Vault is unreachable. Check your connection and retry.' : error instanceof Error ? error.message : 'Could not delete this item.'); }
    finally { setDeleteBusy(false); }
  }

  const bytePercent = quota ? Math.min(100, (quota.activeBytes / quota.maxActiveBytes) * 100) : 0;

  return (
    <main className="app-shell">
      <header className="topbar"><a className="brand" href="/" aria-label="Vault home"><VaultMark /><span>vault</span></a><div className="topbar-right"><span className="topbar-note">PERSONAL RELAY</span>{view === 'signed-in' && <button className="text-button" type="button" onClick={handleLogout} disabled={busy}>{busy ? 'Signing out…' : 'Sign out'}</button>}</div></header>
      {view === 'loading' && <section className="loading-panel" aria-live="polite" aria-busy="true"><span className="loading-ring" aria-hidden="true" /><p>Restoring your private space…</p></section>}
      {view === 'signed-out' && <section className="auth-layout" aria-labelledby="welcome-title"><div className="intro-panel"><p className="eyebrow"><span className="eyebrow-line" /> YOUR DEVICES, IN SYNC</p><h1 id="welcome-title">A quieter way<br />to move things.</h1><p className="intro">A private bridge between your devices. Sign in to open your personal Vault.</p><div className={`service-status ${serviceOnline === false ? 'service-status--offline' : 'service-status--ready'}`} role="status"><span className="status-dot" /><span>{serviceOnline === null ? 'Checking Vault' : serviceOnline ? 'Vault is ready' : 'Vault is having trouble connecting'}</span></div></div>
        <section className="auth-card" aria-labelledby="signin-title"><div className="auth-card-top"><span className="card-kicker">PRIVATE SPACE</span><span className="secure-label"><span aria-hidden="true">●</span> SECURE SIGN-IN</span></div><h2 id="signin-title">Welcome back</h2><p className="auth-description">Use your Vault account to continue.</p><form onSubmit={handleLogin} noValidate><div className="field-group"><label htmlFor="identifier">Email or username</label><input ref={identifierRef} id="identifier" name="identifier" type="text" autoComplete="username" inputMode="text" required value={identifier} onChange={(event) => setIdentifier(event.target.value)} disabled={busy} placeholder="Email address or username" /></div><div className="field-group"><label htmlFor="password">Password</label><input id="password" name="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></div><div className={`form-message ${message ? (messageKind === 'error' ? 'form-message--error' : 'form-message--notice') : ''}`} role={messageKind === 'error' ? 'alert' : 'status'} aria-live="polite">{message}</div><button className="primary-button" type="submit" disabled={busy || retrySeconds > 0 || !identifier.trim() || !password}><span>{busy ? 'Signing in…' : retrySeconds > 0 ? `Try again in ${retrySeconds}s` : 'Sign in'}</span><span aria-hidden="true">→</span></button></form><p className="card-footnote"><span aria-hidden="true">⌑</span> Your password is only sent over this secure connection.</p></section></section>}
      {view === 'signed-in' && session && <section className="inbox-layout" aria-labelledby="inbox-title">
        <div className="inbox-heading-row"><div><p className="eyebrow"><span className="eyebrow-line" /> YOUR PRIVATE SPACE</p><h1 ref={inboxHeadingRef} id="inbox-title" tabIndex={-1}>Your inbox</h1><p className="intro">Signed in as <strong>{session.email}</strong></p></div><div className="device-chip"><span className="device-indicator" /><span><b>This browser</b><small>Current session</small></span></div></div>
        <div className="quota-card" aria-label="Storage quota"><div className="quota-title"><strong>Vault storage</strong><span>{quota ? `${formatBytes(quota.activeBytes)} of ${formatBytes(quota.maxActiveBytes)}` : 'Loading usage…'}</span></div><div className="quota-track" role="progressbar" aria-label="Storage used" aria-valuemin={0} aria-valuemax={quota?.maxActiveBytes ?? 2_000_000_000} aria-valuenow={quota?.activeBytes ?? 0}><span style={{ width: `${bytePercent}%` }} /></div><small>{quota ? `${quota.activeItems.toLocaleString()} of ${quota.maxActiveItems.toLocaleString()} items` : ' '}</small></div>
        <div className={`vault-workspace ${selectedId ? 'vault-workspace--detail' : ''}`}>
          <section className="send-card" aria-labelledby="send-title"><div className="section-heading"><div><p className="card-kicker">SEND SOMETHING</p><h2 id="send-title">Add to Vault</h2></div><span className="send-lock" aria-hidden="true">↗</span></div>
            <form onSubmit={(event) => void sendItem(event)}>
              <div className="send-tabs" role="group" aria-label="Item type"><button type="button" className={kind === 'text' ? 'send-tab is-active' : 'send-tab'} aria-pressed={kind === 'text'} onClick={() => { setKind('text'); setContent(''); resetSendRetry(); setSendMessage(''); }}>Text</button><button type="button" className={kind === 'url' ? 'send-tab is-active' : 'send-tab'} aria-pressed={kind === 'url'} onClick={() => { setKind('url'); setContent(''); resetSendRetry(); setSendMessage(''); }}>Link</button></div>
              <div className="field-group"><label htmlFor="item-content">{kind === 'text' ? 'Text to save' : 'Web address'}</label>{kind === 'text' ? <><textarea id="item-content" rows={4} value={content} onChange={(event) => { setContent(event.target.value); resetSendRetry(); }} disabled={sendBusy} placeholder="Write or paste something…" /><small className="field-hint">Up to 256 KiB UTF-8 · {formatBytes(new TextEncoder().encode(content).byteLength)}</small></> : <input id="item-content" type="url" inputMode="url" value={content} onChange={(event) => { setContent(event.target.value); resetSendRetry(); }} disabled={sendBusy} placeholder="https://example.com" />}</div>
              <div className="send-options"><div className="field-group"><label htmlFor="item-label">Label <span>(optional)</span></label><input id="item-label" maxLength={200} value={displayName} onChange={(event) => { setDisplayName(event.target.value); resetSendRetry(); }} disabled={sendBusy} placeholder="Give it a name" /></div><div className="field-group"><label htmlFor="item-retention">Keep for</label><select id="item-retention" value={retentionDays} onChange={(event) => { setRetentionDays(Number(event.target.value)); resetSendRetry(); }} disabled={sendBusy}><option value={1}>1 day</option><option value={3}>3 days</option><option value={7}>7 days</option></select></div></div>
              {kind === 'url' && <p className="send-hint">Links are saved as text. Vault does not open them.</p>}
              <div className={`send-message ${sendMessage ? (sendError ? 'send-message--error' : 'send-message--notice') : ''}`} role={sendError ? 'alert' : 'status'} aria-live="polite">{sendMessage}</div>
              <div className="send-actions"><button className="primary-button" type="submit" disabled={sendBusy || !content.trim()}><span>{sendBusy ? 'Sending…' : 'Send to Vault'}</span><span aria-hidden="true">→</span></button>{retryIntent && <button className="secondary-button retry-send" type="button" disabled={sendBusy} onClick={() => void sendItem(undefined, true)}>Retry same item</button>}</div>
            </form>
          </section>
          <section className="items-panel" aria-label="Inbox items">
            <div className="items-heading"><div><p className="card-kicker">YOUR ITEMS</p><h2>{selectedId ? 'Item details' : 'Recent items'}</h2></div>{selectedId ? <button className="secondary-button mobile-back" type="button" onClick={backToList}>← Back</button> : <button className="secondary-button" type="button" onClick={() => void loadPage(null, false)} disabled={listBusy}>{listBusy ? 'Refreshing…' : 'Refresh'}</button>}</div>
            {!selectedId && inboxNotice && <p className="inbox-notice" role="status" aria-live="polite">{inboxNotice}</p>}
            {listError && <p className="panel-error" role="alert">{listError}</p>}
            {!selectedId && <><ul className="item-list" aria-label="Newest first">{items.map((item) => <li key={item.id}><button type="button" className="item-row" onClick={() => openItem(item.id)}><span className="item-type-icon" aria-hidden="true">{item.type === 'url' ? '↗' : 'T'}</span><span className="item-row-copy"><strong>{item.displayName?.trim() || (item.type === 'url' ? itemPreview(item) : 'Text item')}</strong><small>Added {formatDate(item.createdAt)} · {formatBytes(item.sizeBytes)}</small><small>Expires {formatDate(item.expiresAt)}</small></span><span className="item-chevron" aria-hidden="true">›</span></button></li>)}</ul>{!items.length && !listBusy && <div className="inbox-empty"><span aria-hidden="true">◇</span><h3>Your inbox is clear.</h3><p>Send text or a link and it will appear here.</p></div>}{listBusy && !items.length && <p className="panel-loading" role="status">Loading your items…</p>}{nextCursor && <button className="load-more" type="button" disabled={listBusy} onClick={() => void loadPage(nextCursor, true)}>{listBusy ? 'Loading…' : 'Load more'}</button>}</>}
            {selectedId && <div className="detail-view">{detailBusy && <p className="panel-loading" role="status">Loading item…</p>}{detailError && <p className="panel-error" role="alert">{detailError}</p>}{detail && <><div className="detail-title"><span className="item-type-icon" aria-hidden="true">{detail.type === 'url' ? '↗' : 'T'}</span><div><p className="card-kicker">{detail.type === 'url' ? 'LINK' : 'TEXT'}</p><h3>{detail.displayName?.trim() || (detail.type === 'url' ? 'Saved link' : 'Text item')}</h3></div></div><pre className="detail-text" tabIndex={0} aria-label="Saved item text">{detail.text}</pre><dl className="detail-meta"><div><dt>Sent from</dt><dd>{detail.sourceDevice.name} · {detail.sourceDevice.platform === 'web' ? 'This browser' : detail.sourceDevice.platform}</dd></div><div><dt>Added</dt><dd>{formatDate(detail.createdAt)}</dd></div><div><dt>Expires</dt><dd>{formatDate(detail.expiresAt)}</dd></div><div><dt>Size</dt><dd>{formatBytes(detail.sizeBytes)}</dd></div></dl><p className="copy-message" role="status" aria-live="polite">{copyMessage}</p>{!deleteConfirm ? <div className="detail-actions"><button className="primary-button" type="button" onClick={() => void copyText()}>Copy text</button><button className="danger-button" type="button" onClick={() => setDeleteConfirm(true)}>Delete</button></div> : <div className="delete-confirm" role="group" aria-label="Confirm item deletion"><p>Delete this item from Vault?</p><button className="danger-button" type="button" disabled={deleteBusy} onClick={() => void deleteItem()}>{deleteBusy ? 'Deleting…' : 'Delete item'}</button><button className="secondary-button" type="button" disabled={deleteBusy} onClick={() => setDeleteConfirm(false)}>Cancel</button></div>}{detailError && <p className="panel-error" role="alert">{detailError}</p>}</>}</div>}
          </section>
        </div>
      </section>}
      <footer className="footer"><span>BUILT FOR YOUR OWN DEVICES</span><span className="footer-mark"><span /> PRIVATE BY DESIGN</span></footer>
    </main>
  );
}

function itemPreview(item: ItemSummary) { return item.type === 'url' ? 'Saved link' : 'Text item'; }

export default App;
