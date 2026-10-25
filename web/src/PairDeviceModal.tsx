import { useEffect, useMemo, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { AndroidIcon, CheckIcon, CloseIcon, CopyIcon, ExternalLinkIcon, QrCodeIcon, RefreshIcon } from './Icons';
import { useFocusTrap } from './useFocusTrap';
import { copyToClipboard, VAULT_ANDROID_RELEASE_URL } from './utils';
import type { DeviceListResponse } from './types';

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

type Props = {
  isOpen: boolean;
  onClose: () => void;
  onUnauthorized: () => void;
  onDevicePaired?: () => void;
};

export default function PairDeviceModal({ isOpen, onClose, onUnauthorized, onDevicePaired }: Props) {
  const modalRef = useFocusTrap(isOpen, onClose);
  const [pairing, setPairing] = useState<PairingSession | null>(null);
  const [now, setNow] = useState(Date.now());
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState(false);
  const [secretCopied, setSecretCopied] = useState(false);
  const [pairedSuccess, setPairedSuccess] = useState(false);
  const initialDeviceIdsRef = useRef<Set<string>>(new Set());
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

  const deepLink = pairing
    ? `vault://pair?serverOrigin=${encodeURIComponent(pairing.serverOrigin)}&pairingSessionId=${encodeURIComponent(pairing.pairingSessionId)}`
    : '';

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestRef.current?.abort();
      requestRef.current = null;
    };
  }, []);

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
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', tick);
    };
  }, [pairing]);

  useEffect(() => {
    if (!isOpen) {
      setPairedSuccess(false);
      initialDeviceIdsRef.current.clear();
      return;
    }
    const fetchInitial = async () => {
      try {
        const res = await fetch(`${API}/devices`, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        });
        if (res.ok) {
          const data = await readJson<DeviceListResponse>(res);
          if (data?.devices) {
            initialDeviceIdsRef.current = new Set(data.devices.map((d) => d.id));
          }
        }
      } catch {
        // ignore
      }
    };
    void fetchInitial();
  }, [isOpen]);

  // Poll for Android pairing completion
  useEffect(() => {
    if (!isOpen || !pairing || pairedSuccess) return undefined;
    const pollInterval = window.setInterval(async () => {
      try {
        const res = await fetch(`${API}/devices`, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        });
        if (res.status === 401) {
          onUnauthorized();
          return;
        }
        if (res.ok) {
          const data = await readJson<DeviceListResponse>(res);
          const hasNew = data?.devices?.some((d) => !initialDeviceIdsRef.current.has(d.id));
          if (hasNew) {
            setPairedSuccess(true);
            setPairing(null);
            setMessage('Device successfully linked to your Vault!');
            setError(false);
            window.setTimeout(() => {
              onDevicePaired?.();
              onClose();
            }, 1400);
          }
        }
      } catch {
        // ignore
      }
    }, 2500);

    return () => window.clearInterval(pollInterval);
  }, [isOpen, pairing, pairedSuccess, onClose, onUnauthorized, onDevicePaired]);

  async function createPairing() {
    if (busy) return;
    const controller = new AbortController();
    requestRef.current = controller;
    setBusy(true);
    setPairing(null);
    setMessage('');
    setError(false);
    setSecretCopied(false);
    try {
      const csrf = await getCsrf(controller.signal);
      const response = await fetch(`${API}/devices/pairing-sessions`, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', [csrf.headerName]: csrf.token },
        body: '{}',
        signal: controller.signal,
      });
      if (!mountedRef.current) return;
      if (response.status === 401) {
        onUnauthorized();
        throw new Error('Your session has expired. Sign in again to continue.');
      }
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
      try { returnedOrigin = new URL(created?.serverOrigin ?? '').origin; } catch { /* handled below */ }
      if (!created?.pairingSessionId || !created.pairingSecret || returnedOrigin !== expectedOrigin || !Number.isFinite(parsedExpiry) || parsedExpiry <= Date.now()) {
        throw new Error('Vault returned an invalid pairing session. Try again.');
      }
      setNow(Date.now());
      setPairing(created);
      setMessage('Scan the QR code with Vault on your phone or copy the pairing secret.');
      setError(false);
    } catch (reason) {
      if (!mountedRef.current || (reason instanceof DOMException && reason.name === 'AbortError')) return;
      setError(true);
      setMessage(reason instanceof TypeError ? 'Vault is unreachable. Check your connection and retry.' : reason instanceof Error ? reason.message : 'Vault could not create a pairing code.');
    } finally {
      if (mountedRef.current) setBusy(false);
      if (requestRef.current === controller) requestRef.current = null;
    }
  }

  // Automatically request code on open if none exists
  useEffect(() => {
    if (isOpen && !pairing && !busy && !error) {
      void createPairing();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  async function copySecret() {
    if (!pairing?.pairingSecret) return;
    const ok = await copyToClipboard(pairing.pairingSecret);
    if (ok) {
      setSecretCopied(true);
      setTimeout(() => setSecretCopied(false), 2500);
    }
  }

  if (!isOpen) return null;

  return (
    <div className="modal-backdrop" onClick={onClose} role="presentation">
      <div
        ref={modalRef}
        tabIndex={-1}
        className="modal-card modal-card--pairing"
        role="dialog"
        aria-modal="true"
        aria-labelledby="pair-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div className="modal-header-copy">
            <span className="card-kicker"><QrCodeIcon size={12} /> PAIR DEVICE</span>
            <h2 id="pair-modal-title">Pair Android Phone</h2>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            onClick={onClose}
            aria-label="Close pairing dialog"
          >
            <CloseIcon size={18} />
          </button>
        </div>

        <p className="modal-description">
          Open Vault on your Android phone and scan this one-time code to link your device securely.
        </p>

        {busy && (
          <div className="modal-loading-state">
            <span className="loading-ring" aria-hidden="true" />
            <p>Generating cryptographically signed pairing session…</p>
          </div>
        )}

        {!busy && pairing && remaining > 0 && (
          <div className="pairing-content">
            <div className="pairing-qr" role="img" aria-label="QR code for Android pairing">
              <QRCodeSVG
                value={qrPayload}
                size={200}
                level="M"
                includeMargin
                bgColor="#ffffff"
                fgColor="#0f172a"
              />
            </div>

            <div className="pairing-countdown font-mono tabular-nums" role="timer" aria-label={`Code expires in ${formatRemaining(remaining)}`}>
              <span className="status-dot-pulse" aria-hidden="true" />
              <span>Expires in <strong>{formatRemaining(remaining)}</strong></span>
            </div>

            <div className="pairing-secret-group">
              <label htmlFor="modal-pairing-secret">Fallback secret (for manual entry)</label>
              <div className="secret-copy-box">
                <input
                  id="modal-pairing-secret"
                  className="pairing-secret"
                  type="text"
                  readOnly
                  autoComplete="off"
                  spellCheck={false}
                  value={pairing.pairingSecret}
                  onFocus={(e) => e.currentTarget.select()}
                />
                <button
                  type="button"
                  className={`copy-pill-button ${secretCopied ? 'is-copied' : ''}`}
                  onClick={() => void copySecret()}
                  aria-label="Copy pairing secret to clipboard"
                >
                  {secretCopied ? <CheckIcon size={14} /> : <CopyIcon size={14} />}
                  <span>{secretCopied ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
            </div>

            <div className="pairing-deep-link-row">
              <a
                href={deepLink}
                className="secondary-button deep-link-btn"
                target="_blank"
                rel="noopener noreferrer"
              >
                <span>Launch deep link on this device</span>
                <ExternalLinkIcon size={13} />
              </a>
            </div>

            <div className="pairing-apk-download-box">
              <span className="pairing-apk-hint">Don't have the Vault Android app installed?</span>
              <a
                href={VAULT_ANDROID_RELEASE_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="pairing-apk-link"
              >
                <AndroidIcon size={14} />
                <span>Download APK (v1.0.0)</span>
              </a>
            </div>

            <details className="pairing-paste-details">
              <summary>Show raw QR JSON payload</summary>
              <textarea
                aria-label="Pairing JSON payload"
                readOnly
                rows={4}
                value={qrPayload}
                onFocus={(e) => e.currentTarget.select()}
              />
            </details>
          </div>
        )}

        {message && (
          <p
            className={`modal-status-message ${error ? 'modal-status-message--error' : 'modal-status-message--notice'}`}
            role={error ? 'alert' : 'status'}
          >
            {message}
          </p>
        )}

        <div className="modal-actions">
          <button
            type="button"
            className="secondary-button"
            onClick={() => void createPairing()}
            disabled={busy}
          >
            <RefreshIcon size={14} className={busy ? 'icon-spin' : ''} />
            <span>{busy ? 'Preparing code…' : pairing ? 'Generate new code' : 'Retry pairing'}</span>
          </button>
          <button type="button" className="secondary-button" onClick={onClose}>
            Done
          </button>
        </div>
      </div>
    </div>
  );
}
