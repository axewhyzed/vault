import { useEffect, useMemo, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { QrCodeIcon } from './Icons';

type Csrf = { token: string; headerName: 'X-CSRF-TOKEN' };
type PairingSession = { pairingSessionId: string; pairingSecret: string; serverOrigin: string; expiresAt: string };

const API = '/api/v1';

async function readJson<T>(response: Response): Promise<T | null> {
  try { return await response.json() as T; } catch { return null; }
}

async function getCsrf(signal: AbortSignal): Promise<Csrf> {
  const response = await fetch(`${API}/auth/csrf`, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }, signal });
  if (!response.ok) throw new Error(response.status === 401 ? 'Your session has expired. Sign in again to continue.' : 'Vault could not prepare a secure pairing request. Refresh and try again.');
  const data = await readJson<Csrf>(response);
  if (!data?.token || data.headerName !== 'X-CSRF-TOKEN') throw new Error('Vault returned an invalid security token. Refresh the page and try again.');
  return data;
}

function formatRemaining(milliseconds: number) {
  const seconds = Math.max(0, Math.ceil(milliseconds / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

type Props = { onUnauthorized: () => void };

export default function PairingPanel({ onUnauthorized }: Props) {
  const [pairing, setPairing] = useState<PairingSession | null>(null);
  const [now, setNow] = useState(Date.now());
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState(false);
  const requestRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);
  const expiresAt = pairing ? Date.parse(pairing.expiresAt) : 0;
  const remaining = expiresAt - now;
  const qrPayload = useMemo(() => pairing ? JSON.stringify({
    version: 1,
    serverOrigin: pairing.serverOrigin,
    pairingSessionId: pairing.pairingSessionId,
    pairingSecret: pairing.pairingSecret,
    expiresAt: pairing.expiresAt,
  }) : '', [pairing]);
  const deepLink = pairing ? `vault://pair?serverOrigin=${encodeURIComponent(pairing.serverOrigin)}&pairingSessionId=${encodeURIComponent(pairing.pairingSessionId)}` : '';

  useEffect(() => {
    if (!pairing) return undefined;
    const tick = () => {
      const current = Date.now();
      setNow(current);
      if (current >= Date.parse(pairing.expiresAt)) {
        setPairing(null);
        setMessage('This pairing code expired. Generate a new code to continue.');
        setError(true);
      }
    };
    const timer = window.setInterval(tick, 1000);
    document.addEventListener('visibilitychange', tick);
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', tick); };
  }, [pairing]);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; requestRef.current?.abort(); requestRef.current = null; };
  }, []);

  async function createPairing() {
    if (busy) return;
    const controller = new AbortController(); requestRef.current = controller;
    setBusy(true); setPairing(null); setMessage(''); setError(false);
    try {
      const csrf = await getCsrf(controller.signal);
      const response = await fetch(`${API}/devices/pairing-sessions`, {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', [csrf.headerName]: csrf.token },
        body: '{}',
        signal: controller.signal,
      });
      if (!mountedRef.current) return;
      if (response.status === 401) { onUnauthorized(); throw new Error('Your session has expired. Sign in again to continue.'); }
      if (!response.ok) {
        const body = await readJson<{ message?: string }>(response);
        if (response.status === 429) throw new Error('Too many pairing requests. Wait a moment and try again.');
        if (response.status === 403) throw new Error(body?.message || 'Vault could not verify this browser request. Refresh and try again.');
        throw new Error(body?.message || 'Vault could not create a pairing code. Try again.');
      }
      const created = await readJson<PairingSession>(response);
      const parsedExpiry = created ? Date.parse(created.expiresAt) : NaN;
      const expectedOrigin = window.location.origin;
      let returnedOrigin = '';
      try { returnedOrigin = new URL(created?.serverOrigin ?? '').origin; } catch { /* rejected by response validation below */ }
      if (!created?.pairingSessionId || !created.pairingSecret || returnedOrigin !== expectedOrigin || !Number.isFinite(parsedExpiry) || parsedExpiry <= Date.now()) {
        throw new Error('Vault returned an invalid pairing session. Try again.');
      }
      setNow(Date.now()); setPairing(created); setMessage('Scan the QR code or paste the JSON on your Android device.'); setError(false);
    } catch (reason) {
      if (!mountedRef.current || (reason instanceof DOMException && reason.name === 'AbortError')) return;
      setError(true); setMessage(reason instanceof TypeError ? 'Vault is unreachable. Check your connection and retry.' : reason instanceof Error ? reason.message : 'Vault could not create a pairing code.');
    } finally { if (mountedRef.current) setBusy(false); if (requestRef.current === controller) requestRef.current = null; }
  }

  return <section className="send-card pairing-card" aria-labelledby="pairing-title">
    <div className="section-heading"><div><p className="card-kicker">ADD A DEVICE</p><h2 id="pairing-title">Pair Android</h2></div><span className="send-lock" aria-hidden="true"><QrCodeIcon /></span></div>
    <p className="pairing-description">Create a one-time code for your Android app. It expires after ten minutes. Generating a new code does not invalidate an earlier unused code.</p>
    {pairing && remaining > 0 && <div className="pairing-content">
      <div className="pairing-qr" role="img" aria-label="QR code for Android pairing. It contains a short-lived enrollment secret."><QRCodeSVG value={qrPayload} size={208} level="M" includeMargin /></div>
      <p className="pairing-countdown" role="timer" aria-label={`Pairing code expires in ${formatRemaining(remaining)}`}><span className="device-indicator" /> Expires in <strong>{formatRemaining(remaining)}</strong></p>
      <div className="field-group pairing-secret-group"><label htmlFor="pairing-secret">Short-lived secret · paste fallback</label><input id="pairing-secret" className="pairing-secret" type="text" readOnly autoComplete="off" spellCheck={false} value={pairing.pairingSecret} onFocus={(event) => event.currentTarget.select()} aria-describedby="pairing-secret-hint" /><small id="pairing-secret-hint" className="field-hint">Enter this secret separately in Android. It is not in the deep link and stays only on this page until expiry.</small></div>
      <div className="pairing-link"><span>Android deep link</span><a href={deepLink}>Open pairing in Android</a><code>{deepLink}</code></div>
      <details className="pairing-paste"><summary>Show full QR JSON for paste</summary><textarea aria-label="Pairing JSON payload" readOnly rows={5} value={qrPayload} onFocus={(event) => event.currentTarget.select()} /></details>
    </div>}
    <p className={`pairing-message ${message ? (error ? 'pairing-message--error' : 'pairing-message--notice') : ''}`} role={error ? 'alert' : 'status'} aria-live="polite">{message}</p>
    <button className="secondary-button pairing-create" type="button" onClick={() => void createPairing()} disabled={busy}>{busy ? 'Preparing code…' : pairing ? 'Generate a new code' : 'Generate pairing code'}</button>
  </section>;
}
