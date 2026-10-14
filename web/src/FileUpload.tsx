import { useEffect, useRef, useState } from 'react';

type Csrf = { token: string; headerName: 'X-CSRF-TOKEN' };
type UploadInit = { uploadId: string; uploadUrl: string; expiresAt: string; maxAcceptedBytes: number };
type UploadResult = { uploadId: string; state: 'staged'; sizeBytes: number; sha256: string };
type ApiError = { message?: string; code?: string };
type Phase = 'idle' | 'initiating' | 'uploading' | 'completing' | 'failed-initiate' | 'failed-upload' | 'failed-complete' | 'restart-required' | 'complete' | 'cancelled';

const API = '/api/v1';

async function readJson<T>(response: Response): Promise<T | null> {
  try { return await response.json() as T; } catch { return null; }
}

function formatBytes(value: number) {
  if (value < 1000) return `${value} B`;
  const units = ['KB', 'MB', 'GB'];
  let size = value / 1000;
  let unit = 0;
  while (size >= 1000 && unit < units.length - 1) { size /= 1000; unit += 1; }
  return `${size >= 100 ? Math.round(size) : size.toFixed(1)} ${units[unit]}`;
}

async function getCsrf(signal?: AbortSignal): Promise<Csrf> {
  const response = await fetch(`${API}/auth/csrf`, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }, signal });
  if (!response.ok) throw new Error(response.status === 401 ? 'Your session has expired. Sign in again to continue.' : 'Vault could not prepare a secure upload. Refresh and try again.');
  const data = await readJson<Csrf>(response);
  if (!data?.token || data.headerName !== 'X-CSRF-TOKEN') throw new Error('Vault returned an invalid security token. Refresh the page and try again.');
  return data;
}

function safeError(status: number, message?: string) {
  if (status === 401) return 'Your session has expired. Sign in again to continue.';
  if (status === 403) return message || 'Vault could not verify this browser request. Refresh and try again.';
  if (status === 413) return 'This file is larger than Vault allows.';
  if (status === 409) return message || 'Vault could not accept this upload. Start a new upload.';
  return message || 'Vault could not complete the upload. Check your connection and retry.';
}

type Props = { retentionDays: number; onComplete: () => void; onUnauthorized: () => void };

class UploadApiError extends Error {
  constructor(readonly status: number, readonly code: string | undefined, message: string) { super(message); }
}

export default function FileUpload({ retentionDays, onComplete, onUnauthorized }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [phase, setPhase] = useState<Phase>('idle');
  const [message, setMessage] = useState('');
  const [progress, setProgress] = useState(0);
  const [sha256, setSha256] = useState('');
  const intentKeyRef = useRef<string | null>(null);
  const intentRetentionRef = useRef<number | null>(null);
  const uploadRef = useRef<UploadInit | null>(null);
  const xhrRef = useRef<XMLHttpRequest | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const generationRef = useRef(0);
  const mountedRef = useRef(true);
  const running = ['initiating', 'uploading', 'completing'].includes(phase);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      generationRef.current += 1;
      xhrRef.current?.abort();
      abortRef.current?.abort();
    };
  }, []);

  function selectFile(next: File | null) {
    generationRef.current += 1;
    xhrRef.current?.abort(); xhrRef.current = null;
    abortRef.current?.abort(); abortRef.current = null;
    setFile(next); setPhase('idle'); setMessage(''); setProgress(0); setSha256('');
    intentKeyRef.current = next ? crypto.randomUUID() : null;
    intentRetentionRef.current = null;
    uploadRef.current = null;
  }

  function cancel() {
    const wasFinalizing = phase === 'completing';
    generationRef.current += 1;
    xhrRef.current?.abort(); xhrRef.current = null;
    abortRef.current?.abort(); abortRef.current = null;
    setPhase(wasFinalizing ? 'failed-complete' : 'cancelled');
    setMessage(wasFinalizing ? 'Finalization was interrupted and may have completed. Retry finalization before uploading again.' : 'Upload cancelled. You can retry this file or choose another.');
  }

  function putFile(upload: UploadInit, selected: File, key: string, generation: number): Promise<UploadResult> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest(); xhrRef.current = xhr;
      xhr.open('PUT', upload.uploadUrl, true);
      xhr.withCredentials = true;
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.setRequestHeader('Idempotency-Key', key);
      const controller = new AbortController(); abortRef.current = controller;
      void getCsrf(controller.signal).then((csrf) => {
        if (generation !== generationRef.current || !mountedRef.current) { reject(new Error('Upload cancelled.')); return; }
        xhr.setRequestHeader(csrf.headerName, csrf.token);
        xhr.upload.onprogress = (event) => {
          if (generation !== generationRef.current || !mountedRef.current) return;
          if (event.lengthComputable) setProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)));
        };
        xhr.onload = () => {
          if (generation !== generationRef.current || !mountedRef.current) return;
          if (xhr.status === 401) { onUnauthorized(); reject(new Error('Your session has expired. Sign in again to continue.')); return; }
          let result: UploadResult | null = null;
          try { result = JSON.parse(xhr.responseText) as UploadResult; } catch { /* response validation below */ }
          if (xhr.status < 200 || xhr.status >= 300) {
            let error: ApiError | null = null;
            try { error = JSON.parse(xhr.responseText) as ApiError; } catch { /* use safe fallback */ }
            reject(new UploadApiError(xhr.status, error?.code, safeError(xhr.status, error?.message))); return;
          }
          if (!result || result.uploadId !== upload.uploadId || result.state !== 'staged' || result.sizeBytes !== selected.size || !result.sha256) { reject(new Error('Vault returned an invalid upload response. Retry this upload.')); return; }
          resolve(result);
        };
        xhr.onerror = () => reject(new Error('The upload connection was interrupted. Retry this file to send it from the beginning.'));
        xhr.onabort = () => reject(new Error('Upload cancelled.'));
        xhr.send(selected);
      }).catch(reject).finally(() => { if (abortRef.current === controller) abortRef.current = null; });
    });
  }

  async function complete(upload: UploadInit, key: string, generation: number) {
    setPhase('completing'); setMessage('Finalizing your file…');
    const controller = new AbortController(); abortRef.current = controller;
    try {
      const csrf = await getCsrf();
      const response = await fetch(`${API}/uploads/${encodeURIComponent(upload.uploadId)}/complete`, {
        method: 'POST', credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: '{}',
      });
      if (generation !== generationRef.current || !mountedRef.current) return;
      if (response.status === 401) { onUnauthorized(); throw new Error('Your session has expired. Sign in again to continue.'); }
      if (!response.ok) {
        const error = await readJson<ApiError>(response);
        throw new UploadApiError(response.status, error?.code, safeError(response.status, error?.message));
      }
      const created = await readJson<{ id: string; type: string; state: string }>(response);
      if (!created?.id || created.type !== 'file' || created.state !== 'available') throw new Error('Vault returned an invalid completion response. Retry finalization.');
      if (generation !== generationRef.current || !mountedRef.current) return;
      setPhase('complete'); setMessage('File added to your inbox.'); setProgress(100); onComplete();
    } catch (error) {
      if (generation !== generationRef.current || !mountedRef.current) return;
      if (error instanceof UploadApiError && (error.status === 404 || error.status === 410 || error.status === 422 || error.code === 'staged_file_missing')) {
        setPhase('restart-required'); setMessage('This upload session is no longer usable. Start a new upload intent to try again.');
      } else if (error instanceof UploadApiError && error.code === 'upload_not_staged') {
        setPhase('failed-upload'); setMessage('The file is not staged yet. Retry the upload from the beginning.');
      } else {
        setPhase('failed-complete'); setMessage(error instanceof TypeError || (error instanceof DOMException && error.name === 'AbortError') ? 'Vault may have completed this file, but the reply was lost. Retry finalization before uploading again.' : error instanceof Error ? error.message : 'Vault could not finalize this file. Retry finalization.');
      }
    } finally { if (abortRef.current === controller) abortRef.current = null; }
  }

  async function run(mode: 'new' | 'upload' | 'complete' = 'new') {
    if (!file || running) return;
    const key = intentKeyRef.current ?? crypto.randomUUID(); intentKeyRef.current = key;
    if (intentRetentionRef.current === null) intentRetentionRef.current = retentionDays;
    const generation = ++generationRef.current;
    setMessage(''); setProgress(0); setSha256('');
    try {
      let upload = uploadRef.current;
      if (mode === 'complete') {
        if (!upload) throw new Error('Upload session is unavailable. Start a new upload.');
        await complete(upload, key, generation); return;
      }
      if (!upload || mode === 'new') {
        setPhase('initiating'); setMessage('Preparing secure upload…');
        const controller = new AbortController(); abortRef.current = controller;
        let response: Response;
        try {
          const csrf = await getCsrf(controller.signal);
          response = await fetch(`${API}/uploads/initiate`, {
            method: 'POST', credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
            headers: { 'Content-Type': 'application/json', Accept: 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key },
            body: JSON.stringify({ displayName: file.name, contentType: file.type || 'application/octet-stream', sizeBytes: file.size, retentionDays: intentRetentionRef.current }),
          });
        } finally { if (abortRef.current === controller) abortRef.current = null; }
        if (generation !== generationRef.current || !mountedRef.current) return;
        if (response.status === 401) { onUnauthorized(); throw new Error('Your session has expired. Sign in again to continue.'); }
        if (!response.ok) { const error = await readJson<ApiError>(response); throw new UploadApiError(response.status, error?.code, safeError(response.status, error?.message)); }
        upload = await readJson<UploadInit>(response);
        if (!upload || !upload.uploadId || !upload.uploadUrl || !Number.isFinite(upload.maxAcceptedBytes)) throw new Error('Vault returned an invalid upload session. Choose the file again and retry.');
        uploadRef.current = upload;
        if (file.size > upload.maxAcceptedBytes) {
          setPhase('restart-required'); setMessage(`This file exceeds the ${formatBytes(upload.maxAcceptedBytes)} limit. Choose a smaller file to continue.`);
          return;
        }
        const target = new URL(upload.uploadUrl, window.location.origin);
        const expectedPath = `${API}/uploads/${encodeURIComponent(upload.uploadId)}/content`;
        if (target.origin !== window.location.origin || target.username || target.password || target.pathname !== expectedPath || target.search || target.hash) {
          uploadRef.current = upload;
          setPhase('restart-required'); setMessage('Vault returned an invalid upload destination. Choose the file again to start a fresh upload.');
          return;
        }
        upload = { ...upload, uploadUrl: expectedPath };
        uploadRef.current = upload;
      }
      setPhase('uploading'); setMessage('Uploading file…');
      const result = await putFile(upload, file, key, generation);
      if (generation !== generationRef.current || !mountedRef.current) return;
      setSha256(result.sha256);
      await complete(upload, key, generation);
    } catch (error) {
      if (generation !== generationRef.current || !mountedRef.current) return;
      if (error instanceof UploadApiError && error.code === 'upload_size_mismatch') {
        setPhase('failed-upload'); setMessage('The uploaded byte count did not match. Retry the same upload from the beginning.'); return;
      }
      if (error instanceof UploadApiError && (error.status === 404 || error.status === 410 || error.status === 413 || error.status === 422 || error.code === 'upload_checksum_mismatch')) setPhase('restart-required');
      else if (phase === 'initiating' || !uploadRef.current) setPhase('failed-initiate');
      else setPhase('failed-upload');
      const text = error instanceof TypeError ? 'Vault is unreachable. Check your connection and retry.' : error instanceof Error ? error.message : 'Vault could not upload this file.';
      setMessage(text);
      if (text.startsWith('Your session has expired')) onUnauthorized();
    }
  }

  function startFreshIntent() {
    if (!file || running) return;
    intentKeyRef.current = crypto.randomUUID(); intentRetentionRef.current = retentionDays; uploadRef.current = null;
    setPhase('idle'); setProgress(0); setSha256(''); setMessage('');
    void run('new');
  }

  const retryMode = phase === 'failed-complete' ? 'complete' : phase === 'failed-upload' || (phase === 'cancelled' && uploadRef.current) ? 'upload' : 'new';
  const percent = progress;

  return <section className="file-upload-card" aria-labelledby="file-upload-title">
    <div className="section-heading"><div><p className="card-kicker">ATTACHMENTS</p><h2 id="file-upload-title">Upload a file</h2></div><span className="send-lock" aria-hidden="true">＋</span></div>
    <div className="field-group"><label htmlFor="vault-file">Choose a file</label><input id="vault-file" type="file" onChange={(event) => selectFile(event.target.files?.[0] ?? null)} disabled={running || phase === 'failed-complete'} /><small className="field-hint">Files are kept as attachments. Vault never previews their contents.</small></div>
    {file && <dl className="upload-file-meta"><div><dt>File</dt><dd>{file.name}</dd></div><div><dt>Size</dt><dd>{formatBytes(file.size)}</dd></div><div><dt>Type</dt><dd>{file.type || 'Unknown file type'}</dd></div></dl>}
    {uploadRef.current && <p className="field-hint upload-limit">Maximum accepted size for this session: {formatBytes(uploadRef.current.maxAcceptedBytes)}</p>}
    {phase === 'uploading' && <div className="upload-progress"><label htmlFor="upload-progress">Upload progress</label><progress id="upload-progress" max={100} value={percent}>{percent}%</progress><span>{percent}%</span></div>}
    <div className={`send-message ${message ? (phase.startsWith('failed') ? 'send-message--error' : 'send-message--notice') : ''}`} role={phase.startsWith('failed') ? 'alert' : 'status'} aria-live="polite">{message}</div>
    {sha256 && <p className="upload-hash">SHA-256 <code>{sha256}</code></p>}
    <div className="send-actions">
      {phase === 'complete' ? <button className="secondary-button" type="button" onClick={() => selectFile(null)}>Choose another file</button> : phase === 'restart-required' ? <button className="primary-button" type="button" onClick={startFreshIntent} disabled={!file || running}><span>Start a new upload intent</span><span aria-hidden="true">→</span></button> : phase === 'failed-initiate' || phase === 'failed-upload' || phase === 'failed-complete' || phase === 'cancelled' ? <button className="primary-button" type="button" onClick={() => void run(retryMode)}><span>{phase === 'failed-complete' ? 'Retry finalization' : phase === 'failed-upload' || phase === 'cancelled' && uploadRef.current ? 'Retry upload' : 'Retry setup'}</span><span aria-hidden="true">→</span></button> : <button className="primary-button" type="button" onClick={() => void run()} disabled={!file || running}><span>{running ? phase === 'initiating' ? 'Preparing…' : phase === 'uploading' ? 'Uploading…' : 'Finalizing…' : 'Upload to Vault'}</span><span aria-hidden="true">→</span></button>}
      {running && <button className="secondary-button" type="button" onClick={cancel}>Cancel upload</button>}
    </div>
  </section>;
}
