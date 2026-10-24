import { useEffect, useRef, useState } from 'react';
import type { DragEvent, FormEvent, KeyboardEvent as ReactKeyboardEvent } from 'react';
import {
  ArrowRightIcon,
  ChevronDownIcon,
  CloseIcon,
  FileIcon,
  LinkIcon,
  PlusIcon,
  SendIcon,
  TextIcon,
} from './Icons';
import { formatBytes, readJson } from './utils';

type Csrf = { token: string; headerName: 'X-CSRF-TOKEN' };
type SendIntent = {
  key: string;
  kind: 'text' | 'url';
  body: { text?: string; url?: string; displayName?: string; retentionDays?: number; retentionHours?: number };
};
type UploadInit = { uploadId: string; uploadUrl: string; expiresAt: string; maxAcceptedBytes: number };
type UploadResult = { uploadId: string; state: 'staged'; sizeBytes: number; sha256: string };
type ApiError = { message?: string; code?: string };
type UploadPhase =
  | 'idle'
  | 'initiating'
  | 'uploading'
  | 'completing'
  | 'failed-initiate'
  | 'failed-upload'
  | 'failed-complete'
  | 'restart-required'
  | 'complete'
  | 'cancelled';

const API = '/api/v1';

function safeError(status: number, message?: string) {
  if (status === 401) return 'Your session has expired. Sign in again to continue.';
  if (status === 403) return message || 'Vault could not verify this browser request. Refresh and try again.';
  if (status === 413) return 'This file is larger than Vault allows.';
  if (status === 409) return message || 'Vault could not accept this upload. Start a new upload.';
  return message || 'Vault could not complete the upload. Check your connection and retry.';
}

class UploadApiError extends Error {
  constructor(readonly status: number, readonly code: string | undefined, message: string) {
    super(message);
  }
}

type Props = {
  onSuccess: () => void;
  onUnauthorized: () => void;
  fetchCsrf: () => Promise<Csrf>;
  handleApiFailure: (response: Response) => Promise<string>;
};

export default function ComposeHub({ onSuccess, onUnauthorized, fetchCsrf, handleApiFailure }: Props) {
  const [tab, setTab] = useState<'text' | 'url' | 'file'>('text');
  const [retentionValue, setRetentionValue] = useState(7);
  const [retentionUnit, setRetentionUnit] = useState<'days' | 'hours'>('days');

  // Text state
  const [textContent, setTextContent] = useState('');
  const [textLabel, setTextLabel] = useState('');

  // URL state
  const [urlContent, setUrlContent] = useState('');
  const [urlLabel, setUrlLabel] = useState('');

  // File state
  const [file, setFile] = useState<File | null>(null);
  const [fileLabel, setFileLabel] = useState('');
  const [uploadPhase, setUploadPhase] = useState<UploadPhase>('idle');
  const [uploadMessage, setUploadMessage] = useState('');
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadSha256, setUploadSha256] = useState('');
  const [isDragOver, setIsDragOver] = useState(false);

  const [sendBusy, setSendBusy] = useState(false);
  const [sendMessage, setSendMessage] = useState('');
  const [sendError, setSendError] = useState(false);
  const [retryIntent, setRetryIntent] = useState<SendIntent | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const intentKeyRef = useRef<string | null>(null);
  const intentRetentionRef = useRef<{ retentionDays?: number; retentionHours?: number } | null>(null);
  const uploadRef = useRef<UploadInit | null>(null);
  const xhrRef = useRef<XMLHttpRequest | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const generationRef = useRef(0);
  const mountedRef = useRef(true);

  const isUploading = ['initiating', 'uploading', 'completing'].includes(uploadPhase);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      generationRef.current += 1;
      xhrRef.current?.abort();
      abortRef.current?.abort();
    };
  }, []);

  function resetSendFeedback() {
    setRetryIntent(null);
    if (sendMessage.startsWith('Vault may have received')) {
      setSendMessage('');
    }
  }

  // --- Text & URL Submission ---
  async function submitTextOrUrl(event?: FormEvent) {
    event?.preventDefault();
    if (sendBusy || tab === 'file') return;

    const rawContent = tab === 'text' ? textContent : urlContent;
    const rawLabel = tab === 'text' ? textLabel : urlLabel;
    const trimmed = rawContent.trim();
    if (!trimmed) {
      setSendError(true);
      setSendMessage(tab === 'url' ? 'Enter a web address to send.' : 'Enter some text to send.');
      return;
    }

    if (tab === 'text') {
      const bytes = new TextEncoder().encode(textContent).byteLength;
      if (bytes > 256 * 1024) {
        setSendError(true);
        setSendMessage('Text exceeds the 256 KiB UTF-8 limit. Shorten it and try again.');
        return;
      }
    }

    if (tab === 'url') {
      try {
        const parsed = new URL(trimmed);
        if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error();
      } catch {
        setSendError(true);
        setSendMessage('Enter a valid URL starting with http:// or https://');
        return;
      }
    }

    const retentionPayload =
      retentionUnit === 'hours'
        ? { retentionHours: retentionValue }
        : { retentionDays: retentionValue };

    const intent: SendIntent =
      retryIntent ?? {
        key: crypto.randomUUID(),
        kind: tab,
        body: {
          ...(tab === 'text' ? { text: textContent } : { url: trimmed }),
          ...(rawLabel.trim() ? { displayName: rawLabel.trim() } : {}),
          ...retentionPayload,
        },
      };

    setSendBusy(true);
    setSendMessage('');
    setSendError(false);

    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`${API}/items/${intent.kind}`, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          [csrf.headerName]: csrf.token,
          'Idempotency-Key': intent.key,
        },
        body: JSON.stringify(intent.body),
      });

      if (response.status === 401) {
        onUnauthorized();
        return;
      }

      if (!response.ok) {
        const text = await handleApiFailure(response);
        setSendError(true);
        setSendMessage(text);
        if (response.status >= 500) {
          setRetryIntent(intent);
        } else {
          setRetryIntent(null);
        }
        return;
      }

      setRetryIntent(null);
      if (tab === 'text') {
        setTextContent('');
        setTextLabel('');
      } else {
        setUrlContent('');
        setUrlLabel('');
      }
      setSendMessage(tab === 'url' ? 'Link saved to Vault.' : 'Text added to Vault.');
      setSendError(false);
      onSuccess();
    } catch (error) {
      setRetryIntent(intent);
      setSendError(true);
      setSendMessage(
        error instanceof TypeError
          ? 'Vault may have received this item, but the reply was lost. Retry to avoid a duplicate.'
          : error instanceof Error
            ? error.message
            : 'Vault could not send this item.'
      );
    } finally {
      setSendBusy(false);
    }
  }

  // Keyboard shortcut: Cmd+Enter / Ctrl+Enter in Textarea
  function handleTextKeyDown(e: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      void submitTextOrUrl();
    }
  }

  // --- File Upload Logic ---
  function selectFile(next: File | null) {
    generationRef.current += 1;
    xhrRef.current?.abort();
    xhrRef.current = null;
    abortRef.current?.abort();
    abortRef.current = null;

    if (next && next.size === 0) {
      setFile(null);
      setUploadPhase('failed-initiate');
      setUploadMessage('Cannot upload empty (0-byte) files. Choose a valid file.');
      return;
    }

    setFile(next);
    setUploadPhase('idle');
    setUploadMessage('');
    setUploadProgress(0);
    setUploadSha256('');
    intentKeyRef.current = next ? crypto.randomUUID() : null;
    intentRetentionRef.current = null;
    uploadRef.current = null;
  }

  function cancelUpload() {
    const wasFinalizing = uploadPhase === 'completing';
    generationRef.current += 1;
    xhrRef.current?.abort();
    xhrRef.current = null;
    abortRef.current?.abort();
    abortRef.current = null;
    uploadRef.current = null;
    intentKeyRef.current = null;
    setUploadPhase(wasFinalizing ? 'failed-complete' : 'cancelled');
    setUploadMessage(
      wasFinalizing
        ? 'Finalization was interrupted and may have completed. Retry finalization before uploading again.'
        : 'Upload cancelled.'
    );
  }

  function putFile(upload: UploadInit, selected: File, key: string, generation: number): Promise<UploadResult> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhrRef.current = xhr;
      xhr.open('PUT', upload.uploadUrl, true);
      xhr.withCredentials = true;
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.setRequestHeader('Idempotency-Key', key);
      const controller = new AbortController();
      abortRef.current = controller;

      void fetchCsrf()
        .then((csrf) => {
          if (generation !== generationRef.current || !mountedRef.current) {
            reject(new Error('Upload cancelled.'));
            return;
          }
          xhr.setRequestHeader(csrf.headerName, csrf.token);
          xhr.upload.onprogress = (event) => {
            if (generation !== generationRef.current || !mountedRef.current) return;
            if (event.lengthComputable) {
              setUploadProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)));
            }
          };
          xhr.onload = () => {
            if (generation !== generationRef.current || !mountedRef.current) return;
            if (xhr.status === 401) {
              onUnauthorized();
              reject(new Error('Your session has expired. Sign in again to continue.'));
              return;
            }
            let result: UploadResult | null = null;
            try {
              result = JSON.parse(xhr.responseText) as UploadResult;
            } catch {
              /* handled below */
            }
            if (xhr.status < 200 || xhr.status >= 300) {
              let error: ApiError | null = null;
              try {
                error = JSON.parse(xhr.responseText) as ApiError;
              } catch {
                /* fallback */
              }
              reject(new UploadApiError(xhr.status, error?.code, safeError(xhr.status, error?.message)));
              return;
            }
            if (!result || result.uploadId !== upload.uploadId || result.state !== 'staged' || result.sizeBytes !== selected.size || !result.sha256) {
              reject(new Error('Vault returned an invalid upload response. Retry this upload.'));
              return;
            }
            resolve(result);
          };
          xhr.onerror = () =>
            reject(new Error('The upload connection was interrupted. Retry to send it from the beginning.'));
          xhr.onabort = () => reject(new Error('Upload cancelled.'));
          xhr.send(selected);
        })
        .catch(reject)
        .finally(() => {
          if (abortRef.current === controller) abortRef.current = null;
        });
    });
  }

  async function completeUpload(upload: UploadInit, key: string, generation: number) {
    setUploadPhase('completing');
    setUploadMessage('Finalizing file in Vault…');
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`${API}/uploads/${encodeURIComponent(upload.uploadId)}/complete`, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          [csrf.headerName]: csrf.token,
          'Idempotency-Key': key,
        },
        body: '{}',
      });
      if (generation !== generationRef.current || !mountedRef.current) return;
      if (response.status === 401) {
        onUnauthorized();
        throw new Error('Your session has expired. Sign in again to continue.');
      }
      if (!response.ok) {
        const error = await readJson<ApiError>(response);
        throw new UploadApiError(response.status, error?.code, safeError(response.status, error?.message));
      }
      const created = await readJson<{ id: string; type: string; state: string }>(response);
      if (!created?.id || created.type !== 'file' || created.state !== 'available') {
        throw new Error('Vault returned an invalid completion response. Retry finalization.');
      }
      if (generation !== generationRef.current || !mountedRef.current) return;
      setUploadPhase('complete');
      setUploadMessage('File successfully added to Vault.');
      setUploadProgress(100);
      onSuccess();
    } catch (error) {
      if (generation !== generationRef.current || !mountedRef.current) return;
      if (
        error instanceof UploadApiError &&
        (error.status === 404 || error.status === 410 || error.status === 422 || error.code === 'staged_file_missing')
      ) {
        setUploadPhase('restart-required');
        setUploadMessage('This upload session expired. Start a new upload to continue.');
      } else if (error instanceof UploadApiError && error.code === 'upload_not_staged') {
        setUploadPhase('failed-upload');
        setUploadMessage('The file is not staged yet. Retry the upload from the beginning.');
      } else {
        setUploadPhase('failed-complete');
        setUploadMessage(
          error instanceof TypeError || (error instanceof DOMException && error.name === 'AbortError')
            ? 'Vault may have completed this file, but the reply was lost. Retry finalization before uploading again.'
            : error instanceof Error
              ? error.message
              : 'Vault could not finalize this file. Retry finalization.'
        );
      }
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
    }
  }

  async function runUpload(mode: 'new' | 'upload' | 'complete' = 'new') {
    if (!file || isUploading) return;
    const key = intentKeyRef.current ?? crypto.randomUUID();
    intentKeyRef.current = key;
    if (intentRetentionRef.current === null) {
      intentRetentionRef.current =
        retentionUnit === 'hours'
          ? { retentionHours: retentionValue }
          : { retentionDays: retentionValue };
    }
    const generation = ++generationRef.current;
    setUploadMessage('');
    setUploadProgress(0);
    setUploadSha256('');
    try {
      let upload = uploadRef.current;
      if (mode === 'complete') {
        if (!upload) throw new Error('Upload session is unavailable. Start a new upload.');
        await completeUpload(upload, key, generation);
        return;
      }
      if (!upload || mode === 'new') {
        setUploadPhase('initiating');
        setUploadMessage('Preparing secure upload session…');
        const controller = new AbortController();
        abortRef.current = controller;
        let response: Response;
        try {
          const csrf = await fetchCsrf();
          response = await fetch(`${API}/uploads/initiate`, {
            method: 'POST',
            credentials: 'same-origin',
            cache: 'no-store',
            signal: controller.signal,
            headers: {
              'Content-Type': 'application/json',
              Accept: 'application/json',
              [csrf.headerName]: csrf.token,
              'Idempotency-Key': key,
            },
            body: JSON.stringify({
              displayName: fileLabel.trim() || file.name,
              contentType: file.type || 'application/octet-stream',
              sizeBytes: file.size,
              ...intentRetentionRef.current,
            }),
          });
        } finally {
          if (abortRef.current === controller) abortRef.current = null;
        }
        if (generation !== generationRef.current || !mountedRef.current) return;
        if (response.status === 401) {
          onUnauthorized();
          throw new Error('Your session has expired. Sign in again to continue.');
        }
        if (!response.ok) {
          const error = await readJson<ApiError>(response);
          throw new UploadApiError(response.status, error?.code, safeError(response.status, error?.message));
        }
        upload = await readJson<UploadInit>(response);
        if (!upload || !upload.uploadId || !upload.uploadUrl || !Number.isFinite(upload.maxAcceptedBytes)) {
          throw new Error('Vault returned an invalid upload session. Choose the file again and retry.');
        }
        uploadRef.current = upload;
        if (file.size > upload.maxAcceptedBytes) {
          setUploadPhase('restart-required');
          setUploadMessage(`File exceeds the ${formatBytes(upload.maxAcceptedBytes)} limit. Choose a smaller file.`);
          return;
        }
        const target = new URL(upload.uploadUrl, window.location.origin);
        const expectedPath = `${API}/uploads/${encodeURIComponent(upload.uploadId)}/content`;
        if (target.origin !== window.location.origin || target.username || target.password || target.pathname !== expectedPath || target.search || target.hash) {
          uploadRef.current = upload;
          setUploadPhase('restart-required');
          setUploadMessage('Vault returned an invalid upload destination. Start a fresh upload.');
          return;
        }
        upload = { ...upload, uploadUrl: expectedPath };
        uploadRef.current = upload;
      }
      setUploadPhase('uploading');
      setUploadMessage('Uploading file to Vault…');
      const result = await putFile(upload, file, key, generation);
      if (generation !== generationRef.current || !mountedRef.current) return;
      setUploadSha256(result.sha256);
      await completeUpload(upload, key, generation);
    } catch (error) {
      if (generation !== generationRef.current || !mountedRef.current) return;
      if (error instanceof UploadApiError && error.code === 'upload_size_mismatch') {
        setUploadPhase('failed-upload');
        setUploadMessage('The uploaded byte count did not match. Retry the upload from the beginning.');
        return;
      }
      if (
        error instanceof UploadApiError &&
        (error.status === 404 || error.status === 410 || error.status === 413 || error.status === 422 || error.code === 'upload_checksum_mismatch')
      ) {
        setUploadPhase('restart-required');
      } else if (uploadPhase === 'initiating' || !uploadRef.current) {
        setUploadPhase('failed-initiate');
      } else {
        setUploadPhase('failed-upload');
      }
      const text =
        error instanceof TypeError
          ? 'Vault is unreachable. Check your connection and retry.'
          : error instanceof Error
            ? error.message
            : 'Vault could not upload this file.';
      setUploadMessage(text);
      if (text.startsWith('Your session has expired')) onUnauthorized();
    }
  }

  function handleDragOver(e: DragEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (isUploading) return;
    setIsDragOver(true);
  }

  function handleDragLeave(e: DragEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (e.currentTarget.contains(e.relatedTarget as Node)) return;
    setIsDragOver(false);
  }

  function handleDrop(e: DragEvent) {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
    if (isUploading) return;

    // Check directory drag
    const items = e.dataTransfer.items;
    if (items && items.length > 0) {
      const item = items[0];
      const entry = (item as unknown as { webkitGetAsEntry?: () => { isDirectory: boolean } | null }).webkitGetAsEntry?.();
      if (entry?.isDirectory) {
        setTab('file');
        setUploadPhase('failed-initiate');
        setUploadMessage('Folders/directories cannot be uploaded. Compress the folder into a zip file first.');
        return;
      }
    }

    const files = e.dataTransfer.files;
    if (!files || files.length === 0) return;

    if (files.length > 1) {
      setUploadMessage(`Note: Only "${files[0].name}" was selected. Multi-file batch drops are not supported.`);
    }

    const dropped = files[0];
    setTab('file');
    selectFile(dropped);
  }

  const textByteLength = new TextEncoder().encode(textContent).byteLength;
  const isNearTextLimit = textByteLength > 200 * 1024;
  const isOverTextLimit = textByteLength > 256 * 1024;
  const urlByteLength = new TextEncoder().encode(urlContent).byteLength;
  const isOverUrlLimit = urlByteLength > 8192;
  const retryMode =
    uploadPhase === 'failed-complete'
      ? 'complete'
      : uploadPhase === 'failed-upload' || (uploadPhase === 'cancelled' && uploadRef.current)
        ? 'upload'
        : 'new';

  return (
    <section className="compose-hub-card" aria-labelledby="compose-hub-title">
      {/* Header with Title and Retention Dropdown */}
      <div className="compose-hub-header">
        <div className="compose-hub-heading">
          <span className="card-kicker"><SendIcon size={12} /> ADD TO VAULT</span>
          <h2 id="compose-hub-title">Compose</h2>
        </div>
        <div className="compose-retention-pill">
          <label htmlFor="compose-retention-val">Keep:</label>
          <div className="retention-input-group">
            <input
              id="compose-retention-val"
              type="number"
              min={1}
              max={retentionUnit === 'hours' ? 168 : 7}
              value={retentionValue}
              onChange={(e) => {
                const parsed = parseInt(e.target.value, 10);
                const max = retentionUnit === 'hours' ? 168 : 7;
                if (!Number.isNaN(parsed)) {
                  setRetentionValue(Math.max(1, Math.min(parsed, max)));
                } else {
                  setRetentionValue(1);
                }
                resetSendFeedback();
              }}
              disabled={sendBusy || isUploading}
              className="retention-num-input"
              aria-label="Retention amount"
            />
            <div className="retention-select-wrap">
              <select
                id="compose-retention-unit"
                value={retentionUnit}
                onChange={(e) => {
                  const nextUnit = e.target.value as 'hours' | 'days';
                  setRetentionUnit(nextUnit);
                  if (nextUnit === 'hours' && retentionUnit === 'days') {
                    setRetentionValue(Math.min(168, retentionValue * 24));
                  } else if (nextUnit === 'days' && retentionUnit === 'hours') {
                    setRetentionValue(Math.max(1, Math.min(7, Math.round(retentionValue / 24))));
                  }
                  resetSendFeedback();
                }}
                disabled={sendBusy || isUploading}
                aria-label="Retention unit"
              >
                <option value="hours">hours</option>
                <option value="days">days</option>
              </select>
              <ChevronDownIcon size={12} className="retention-select-chevron" />
            </div>
          </div>
        </div>
      </div>

      {/* Segmented Tabs */}
      <div className="compose-tabs" role="tablist" aria-label="Compose item type">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'text'}
          className={`compose-tab ${tab === 'text' ? 'is-active' : ''}`}
          onClick={() => {
            setTab('text');
            resetSendFeedback();
          }}
        >
          <TextIcon size={14} />
          <span>Text</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'url'}
          className={`compose-tab ${tab === 'url' ? 'is-active' : ''}`}
          onClick={() => {
            setTab('url');
            resetSendFeedback();
          }}
        >
          <LinkIcon size={14} />
          <span>Link</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'file'}
          className={`compose-tab ${tab === 'file' ? 'is-active' : ''}`}
          onClick={() => {
            setTab('file');
            resetSendFeedback();
          }}
        >
          <FileIcon size={14} />
          <span>File</span>
        </button>
      </div>

      {/* Tab Panels */}
      {tab === 'text' && (
        <form onSubmit={submitTextOrUrl} className="compose-tab-panel" noValidate>
          <div className="field-group">
            <label htmlFor="compose-text-input">Text content</label>
            <textarea
              id="compose-text-input"
              rows={4}
              value={textContent}
              onChange={(e) => {
                setTextContent(e.target.value);
                resetSendFeedback();
              }}
              onKeyDown={handleTextKeyDown}
              disabled={sendBusy}
              placeholder="Write or paste something…"
              aria-describedby="text-byte-hint"
            />
            <div className="compose-meta-row" id="text-byte-hint">
              <span className="field-hint">⌘/Ctrl + Enter to send</span>
              <span
                className={`byte-counter font-mono tabular-nums ${
                  isOverTextLimit ? 'is-over-limit' : isNearTextLimit ? 'is-near-limit' : ''
                }`}
              >
                {formatBytes(textByteLength)} / 256 KiB
              </span>
            </div>
          </div>

          <div className="field-group">
            <label htmlFor="compose-text-label">
              Label <span>(optional)</span>
            </label>
            <input
              id="compose-text-label"
              type="text"
              maxLength={200}
              value={textLabel}
              onChange={(e) => {
                setTextLabel(e.target.value);
                resetSendFeedback();
              }}
              disabled={sendBusy}
              placeholder="e.g. WiFi password, code snippet"
            />
            {textLabel.length > 150 && (
              <small className="field-hint label-counter font-mono tabular-nums">
                {200 - textLabel.length} characters remaining
              </small>
            )}
          </div>

          {sendMessage && (
            <div
              className={`send-message ${sendError ? 'send-message--error' : 'send-message--notice'}`}
              role={sendError ? 'alert' : 'status'}
              aria-live="polite"
            >
              {sendMessage}
            </div>
          )}

          <div className="compose-actions">
            <button
              className="primary-button"
              type="submit"
              disabled={sendBusy || !textContent.trim() || isOverTextLimit}
            >
              <span>{sendBusy ? 'Sending to Vault…' : 'Send to Vault'}</span>
              <ArrowRightIcon size={16} />
            </button>
            {retryIntent && (
              <button
                className="secondary-button"
                type="button"
                disabled={sendBusy}
                onClick={() => void submitTextOrUrl()}
              >
                Retry send
              </button>
            )}
          </div>
        </form>
      )}

      {tab === 'url' && (
        <form onSubmit={submitTextOrUrl} className="compose-tab-panel" noValidate>
          <div className="field-group">
            <label htmlFor="compose-url-input">Web address</label>
            <input
              id="compose-url-input"
              type="url"
              inputMode="url"
              value={urlContent}
              onChange={(e) => {
                setUrlContent(e.target.value);
                resetSendFeedback();
              }}
              disabled={sendBusy}
              placeholder="https://example.com/article"
              required
            />
            <small className="field-hint">Links are saved as text for fast handoff. Vault never opens or pre-fetches them.</small>
            {urlByteLength > 0 && (
              <div className="compose-meta-row">
                <span />
                <span
                  className={`byte-counter font-mono tabular-nums ${isOverUrlLimit ? 'is-over-limit' : ''}`}
                >
                  {formatBytes(urlByteLength)} / 8 KiB
                </span>
              </div>
            )}
          </div>

          <div className="field-group">
            <label htmlFor="compose-url-label">
              Label <span>(optional)</span>
            </label>
            <input
              id="compose-url-label"
              type="text"
              maxLength={200}
              value={urlLabel}
              onChange={(e) => {
                setUrlLabel(e.target.value);
                resetSendFeedback();
              }}
              disabled={sendBusy}
              placeholder="e.g. Tech doc, article reference"
            />
            {urlLabel.length > 150 && (
              <small className="field-hint label-counter font-mono tabular-nums">
                {200 - urlLabel.length} characters remaining
              </small>
            )}
          </div>

          {sendMessage && (
            <div
              className={`send-message ${sendError ? 'send-message--error' : 'send-message--notice'}`}
              role={sendError ? 'alert' : 'status'}
              aria-live="polite"
            >
              {sendMessage}
            </div>
          )}

          <div className="compose-actions">
            <button
              className="primary-button"
              type="submit"
              disabled={sendBusy || !urlContent.trim() || isOverUrlLimit}
            >
              <span>{sendBusy ? 'Sending link…' : 'Send to Vault'}</span>
              <ArrowRightIcon size={16} />
            </button>
            {retryIntent && (
              <button
                className="secondary-button"
                type="button"
                disabled={sendBusy}
                onClick={() => void submitTextOrUrl()}
              >
                Retry send
              </button>
            )}
          </div>
        </form>
      )}

      {tab === 'file' && (
        <div className="compose-tab-panel">
          <input
            ref={fileInputRef}
            type="file"
            className="visually-hidden"
            onChange={(e) => selectFile(e.target.files?.[0] ?? null)}
            disabled={isUploading}
          />

          {!file ? (
            <div
              className={`compose-dropzone ${isDragOver ? 'is-dragover' : ''}`}
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  fileInputRef.current?.click();
                }
              }}
              aria-label="Upload file dropzone. Click or drag file here."
            >
              <div className="dropzone-icon-circle" aria-hidden="true">
                <PlusIcon size={20} />
              </div>
              <div className="dropzone-copy">
                <strong>Choose a file or drag & drop</strong>
                <span>Any file type up to 2 GB · Kept as encrypted attachment</span>
              </div>
            </div>
          ) : (
            <div
              className={`file-selected-chip-card ${isDragOver ? 'is-dragover' : ''}`}
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
            >
              <div className="file-chip-left">
                <span className="file-chip-icon" aria-hidden="true">
                  <FileIcon size={18} />
                </span>
                <div className="file-chip-info">
                  <strong title={file.name}>{file.name}</strong>
                  <small>
                    {formatBytes(file.size)} · {file.type || 'Binary file'}
                  </small>
                </div>
              </div>
              {!isUploading && (
                <button
                  type="button"
                  className="file-remove-btn"
                  onClick={() => selectFile(null)}
                  aria-label="Remove chosen file"
                  title="Remove file"
                >
                  <CloseIcon size={14} />
                </button>
              )}
            </div>
          )}

          {file && (
            <div className="field-group" style={{ marginTop: '12px' }}>
              <label htmlFor="compose-file-label">
                Label <span>(optional)</span>
              </label>
              <input
                id="compose-file-label"
                type="text"
                maxLength={200}
                value={fileLabel}
                onChange={(e) => setFileLabel(e.target.value)}
                disabled={isUploading}
                placeholder={file.name}
              />
              {fileLabel.length > 150 && (
                <small className="field-hint label-counter font-mono tabular-nums">
                  {200 - fileLabel.length} characters remaining
                </small>
              )}
            </div>
          )}

          {/* Progress bar */}
          {uploadPhase === 'uploading' && (
            <div className="compose-upload-progress">
              <div className="progress-label-row">
                <span>Uploading encrypted chunk…</span>
                <span>{uploadProgress}%</span>
              </div>
              <progress max={100} value={uploadProgress}>
                {uploadProgress}%
              </progress>
            </div>
          )}

          {/* SHA-256 hash */}
          {uploadSha256 && (
            <div className="upload-hash-pill">
              <span>SHA-256</span>
              <code>{uploadSha256}</code>
            </div>
          )}

          {/* Upload Status message */}
          {uploadMessage && (
            <div
              className={`send-message ${uploadPhase.startsWith('failed') || uploadPhase === 'restart-required' ? 'send-message--error' : 'send-message--notice'}`}
              role={uploadPhase.startsWith('failed') ? 'alert' : 'status'}
              aria-live="polite"
            >
              {uploadMessage}
            </div>
          )}

          <div className="compose-actions">
            {uploadPhase === 'complete' ? (
              <button
                className="secondary-button"
                type="button"
                onClick={() => selectFile(null)}
              >
                Choose another file
              </button>
            ) : uploadPhase === 'restart-required' ? (
              <button
                className="primary-button"
                type="button"
                onClick={() => void runUpload('new')}
                disabled={!file || isUploading}
              >
                <span>Start new upload intent</span>
                <ArrowRightIcon size={16} />
              </button>
            ) : uploadPhase.startsWith('failed') || uploadPhase === 'cancelled' ? (
              <button
                className="primary-button"
                type="button"
                onClick={() => void runUpload(retryMode)}
                disabled={!file}
              >
                <span>
                  {uploadPhase === 'failed-complete'
                    ? 'Retry finalization'
                    : uploadPhase === 'failed-upload' || (uploadPhase === 'cancelled' && uploadRef.current)
                      ? 'Retry upload'
                      : 'Retry setup'}
                </span>
                <ArrowRightIcon size={16} />
              </button>
            ) : (
              <button
                className="primary-button"
                type="button"
                onClick={() => void runUpload('new')}
                disabled={!file || isUploading}
              >
                <span>
                  {isUploading
                    ? uploadPhase === 'initiating'
                      ? 'Preparing…'
                      : uploadPhase === 'uploading'
                        ? 'Uploading…'
                        : 'Finalizing…'
                    : 'Upload to Vault'}
                </span>
                <ArrowRightIcon size={16} />
              </button>
            )}

            {isUploading && (
              <button
                className="secondary-button"
                type="button"
                onClick={cancelUpload}
              >
                Cancel upload
              </button>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
