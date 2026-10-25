import type { DeviceSummary, ItemDetail, ItemSummary, Quota } from '../types';
import {
  DEMO_DETAILS,
  DEMO_QUOTA,
  DEMO_SESSION,
  DEMO_SVG_PREVIEW,
  INITIAL_DEMO_ITEMS,
} from '../demoData';

export const INITIAL_DEMO_DEVICES: DeviceSummary[] = [
  {
    id: 'demo-dev-1',
    name: 'Pixel 9 Pro',
    platform: 'android',
    createdAt: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString(),
    isCurrent: false,
  },
  {
    id: 'demo-dev-2',
    name: 'This Browser (Chrome)',
    platform: 'web',
    createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    isCurrent: true,
  },
];

type PendingUpload = {
  uploadId: string;
  displayName: string;
  contentType: string;
  sizeBytes: number;
  expiresAt: string;
};

let isActive = false;
let originalFetch: typeof window.fetch | null = null;
let originalXHR: typeof window.XMLHttpRequest | null = null;

// In-memory demo state
let items: ItemSummary[] = [];
let details: Record<string, ItemDetail> = {};
let quota: Quota = { ...DEMO_QUOTA };
let devices: DeviceSummary[] = [];
const pendingUploads = new Map<string, PendingUpload>();
let pairingTimer: number | null = null;

export function resetDemoMockData() {
  items = JSON.parse(JSON.stringify(INITIAL_DEMO_ITEMS)) as ItemSummary[];
  details = JSON.parse(JSON.stringify(DEMO_DETAILS)) as Record<string, ItemDetail>;
  quota = { ...DEMO_QUOTA };
  devices = JSON.parse(JSON.stringify(INITIAL_DEMO_DEVICES)) as DeviceSummary[];
  pendingUploads.clear();
  if (pairingTimer) {
    window.clearTimeout(pairingTimer);
    pairingTimer = null;
  }
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
    },
  });
}

function emptyResponse(status = 204): Response {
  return new Response(null, { status });
}

async function handleMockFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const urlString = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
  const url = new URL(urlString, window.location.origin);
  const method = (init?.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
  const path = url.pathname;

  // 1. Health endpoint
  if (path === '/health/ready') {
    return jsonResponse({ status: 'healthy', database: 'connected', storage: 'ready' });
  }

  // 2. Auth CSRF token
  if (path === '/api/v1/auth/csrf') {
    return jsonResponse({ token: 'vault-demo-csrf-token', headerName: 'X-CSRF-TOKEN' });
  }

  // 3. Current authenticated session
  if (path === '/api/v1/auth/me') {
    return jsonResponse(DEMO_SESSION);
  }

  // 4. Auth login
  if (path === '/api/v1/auth/login') {
    return jsonResponse(DEMO_SESSION);
  }

  // 5. Auth logout
  if (path === '/api/v1/auth/logout') {
    return jsonResponse({ message: 'Signed out' });
  }

  // 6. Revoke all sessions
  if (path === '/api/v1/auth/sessions/revoke-all') {
    resetDemoMockData();
    return emptyResponse(204);
  }

  // 7. Get items listing & quota
  if (path === '/api/v1/items' && method === 'GET') {
    return jsonResponse({
      items: [...items],
      nextCursor: null,
      quota: { ...quota },
    });
  }

  // 8. Create text item
  if (path === '/api/v1/items/text' && method === 'POST') {
    let body: { text?: string; displayName?: string; retentionDays?: number; retentionHours?: number } = {};
    if (init?.body && typeof init.body === 'string') {
      try { body = JSON.parse(init.body) as typeof body; } catch { /* ignore */ }
    }
    const id = `demo-${Date.now()}`;
    const text = body.text || '';
    const sizeBytes = new TextEncoder().encode(text).byteLength;
    const hours = body.retentionHours || (body.retentionDays ? body.retentionDays * 24 : 168);
    const expiresAt = new Date(Date.now() + hours * 3600 * 1000).toISOString();
    const displayName = body.displayName?.trim() || 'Text note';

    const newItem: ItemSummary = {
      id,
      type: 'text',
      state: 'available',
      displayName,
      sizeBytes,
      createdAt: new Date().toISOString(),
      availableAt: new Date().toISOString(),
      expiresAt,
      version: 1,
      sourceDevice: { id: 'd-web', name: 'This Browser', platform: 'web' },
      textContent: text,
    };
    const newDetail: ItemDetail = {
      ...newItem,
      text,
      contentType: 'text/plain',
    };

    items = [newItem, ...items];
    details[id] = newDetail;
    quota = {
      ...quota,
      activeItems: quota.activeItems + 1,
      activeBytes: quota.activeBytes + sizeBytes,
    };

    return jsonResponse({ id, state: 'available', displayName }, 201);
  }

  // 9. Create URL / link item
  if (path === '/api/v1/items/url' && method === 'POST') {
    let body: { url?: string; displayName?: string; retentionDays?: number; retentionHours?: number } = {};
    if (init?.body && typeof init.body === 'string') {
      try { body = JSON.parse(init.body) as typeof body; } catch { /* ignore */ }
    }
    const id = `demo-${Date.now()}`;
    const linkUrl = body.url || '';
    const sizeBytes = new TextEncoder().encode(linkUrl).byteLength;
    const hours = body.retentionHours || (body.retentionDays ? body.retentionDays * 24 : 168);
    const expiresAt = new Date(Date.now() + hours * 3600 * 1000).toISOString();
    const displayName = body.displayName?.trim() || 'Saved link';

    const newItem: ItemSummary = {
      id,
      type: 'url',
      state: 'available',
      displayName,
      sizeBytes,
      createdAt: new Date().toISOString(),
      availableAt: new Date().toISOString(),
      expiresAt,
      version: 1,
      sourceDevice: { id: 'd-web', name: 'This Browser', platform: 'web' },
      textContent: linkUrl,
    };
    const newDetail: ItemDetail = {
      ...newItem,
      text: linkUrl,
      contentType: 'text/uri-list',
    };

    items = [newItem, ...items];
    details[id] = newDetail;
    quota = {
      ...quota,
      activeItems: quota.activeItems + 1,
      activeBytes: quota.activeBytes + sizeBytes,
    };

    return jsonResponse({ id, state: 'available', displayName }, 201);
  }

  // 10. File upload initiate
  if (path === '/api/v1/uploads/initiate' && method === 'POST') {
    let body: { displayName?: string; contentType?: string; sizeBytes?: number; retentionDays?: number; retentionHours?: number } = {};
    if (init?.body && typeof init.body === 'string') {
      try { body = JSON.parse(init.body) as typeof body; } catch { /* ignore */ }
    }
    const uploadId = `demo-up-${Date.now()}`;
    const displayName = body.displayName || 'Uploaded file';
    const contentType = body.contentType || 'application/octet-stream';
    const sizeBytes = body.sizeBytes || 1024;
    const expiresAt = new Date(Date.now() + 3600 * 1000).toISOString();

    if (sizeBytes > 10_485_760) {
      return jsonResponse({ code: 'file_too_large', message: 'Demo mode is limited to files up to 10 MB.' }, 400);
    }
    if (quota.activeBytes + sizeBytes > quota.maxActiveBytes) {
      return jsonResponse({ code: 'quota_exceeded', message: 'Demo storage limit reached (10 MB). Delete an item to free space.' }, 409);
    }

    pendingUploads.set(uploadId, {
      uploadId,
      displayName,
      contentType,
      sizeBytes,
      expiresAt,
    });

    return jsonResponse({
      uploadId,
      uploadUrl: `/api/v1/uploads/${encodeURIComponent(uploadId)}/content`,
      expiresAt,
      maxAcceptedBytes: 10_485_760,
    }, 201);
  }

  // 11. File upload complete
  const completeMatch = path.match(/^\/api\/v1\/uploads\/([^/]+)\/complete$/);
  if (completeMatch && method === 'POST') {
    const uploadId = decodeURIComponent(completeMatch[1]);
    const pending = pendingUploads.get(uploadId);
    const id = `demo-${Date.now()}`;
    const displayName = pending?.displayName || 'Uploaded file';
    const contentType = pending?.contentType || 'application/octet-stream';
    const sizeBytes = pending?.sizeBytes || 2048;

    const newItem: ItemSummary = {
      id,
      type: 'file',
      state: 'available',
      displayName,
      sizeBytes,
      createdAt: new Date().toISOString(),
      availableAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString(),
      version: 1,
      sourceDevice: { id: 'd-web', name: 'This Browser', platform: 'web' },
      textContent: null,
    };
    const newDetail: ItemDetail = {
      ...newItem,
      text: null,
      contentType,
      sha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      downloadUrl: contentType.startsWith('image/') ? DEMO_SVG_PREVIEW : `/api/v1/items/${id}/download`,
    };

    items = [newItem, ...items];
    details[id] = newDetail;
    quota = {
      ...quota,
      activeItems: quota.activeItems + 1,
      activeBytes: quota.activeBytes + sizeBytes,
    };
    pendingUploads.delete(uploadId);

    return jsonResponse({
      id,
      type: 'file',
      state: 'available',
      displayName,
      sizeBytes,
      availableAt: newItem.availableAt,
      expiresAt: newItem.expiresAt,
      version: 1,
    }, 201);
  }

  // 12. Single item detail
  const itemDetailMatch = path.match(/^\/api\/v1\/items\/([^/]+)$/);
  if (itemDetailMatch) {
    const id = decodeURIComponent(itemDetailMatch[1]);
    if (method === 'GET') {
      const detail = details[id];
      if (detail) return jsonResponse(detail);
      const summary = items.find((x) => x.id === id);
      if (summary) {
        return jsonResponse({
          ...summary,
          text: summary.textContent || null,
          contentType: summary.type === 'file' ? 'application/octet-stream' : 'text/plain',
          sha256: summary.type === 'file' ? '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08' : undefined,
          downloadUrl: summary.type === 'file' ? DEMO_SVG_PREVIEW : undefined,
        });
      }
      return jsonResponse({ code: 'item_not_found', message: 'Item not found' }, 404);
    }
    if (method === 'DELETE') {
      const target = items.find((x) => x.id === id);
      if (target) {
        quota = {
          ...quota,
          activeItems: Math.max(0, quota.activeItems - 1),
          activeBytes: Math.max(0, quota.activeBytes - target.sizeBytes),
        };
      }
      items = items.filter((x) => x.id !== id);
      delete details[id];
      return emptyResponse(204);
    }
  }

  // 13. Item download simulation
  const downloadMatch = path.match(/^\/api\/v1\/items\/([^/]+)\/download$/);
  if (downloadMatch && method === 'GET') {
    const id = decodeURIComponent(downloadMatch[1]);
    const summary = items.find((x) => x.id === id);
    const detail = details[id];
    if (detail?.downloadUrl?.startsWith('data:')) {
      const dataUrl = detail.downloadUrl;
      const commaIdx = dataUrl.indexOf(',');
      const meta = dataUrl.slice(5, commaIdx);
      const isBase64 = meta.includes(';base64');
      const rawData = dataUrl.slice(commaIdx + 1);
      const mime = meta.split(';')[0] || 'application/octet-stream';
      if (isBase64) {
        const binStr = atob(rawData);
        const bytes = new Uint8Array(binStr.length);
        for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
        return new Response(bytes, {
          status: 200,
          headers: {
            'Content-Type': mime,
            'Content-Disposition': `attachment; filename="${summary?.displayName || 'vault-demo-file'}"`,
          },
        });
      }
      const decoded = decodeURIComponent(rawData);
      return new Response(decoded, {
        status: 200,
        headers: {
          'Content-Type': mime,
          'Content-Disposition': `attachment; filename="${summary?.displayName || 'vault-demo-file'}"`,
        },
      });
    }
    const content = `Vault Demo Decrypted File Content: ${summary?.displayName || id}`;
    return new Response(content, {
      status: 200,
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Disposition': `attachment; filename="${summary?.displayName || 'vault-demo-file.bin'}"`,
      },
    });
  }

  // 14. Devices listing
  if (path === '/api/v1/devices' && method === 'GET') {
    return jsonResponse({ devices: [...devices] });
  }

  // 15. Create pairing session
  if (path === '/api/v1/devices/pairing-sessions' && method === 'POST') {
    const sessionId = `demo-session-${Date.now()}`;
    const secret = 'VAULT-DEMO-9823-7712';

    // Simulate phone camera scan and pairing completion after 4.5 seconds
    if (pairingTimer) window.clearTimeout(pairingTimer);
    pairingTimer = window.setTimeout(() => {
      const alreadyHasPhone = devices.some((d) => d.id === 'demo-pixel-phone');
      if (!alreadyHasPhone) {
        devices = [
          {
            id: 'demo-pixel-phone',
            name: 'Pixel 9 Pro (Paired)',
            platform: 'android',
            createdAt: new Date().toISOString(),
            isCurrent: false,
          },
          ...devices,
        ];
      }
    }, 4500);

    return jsonResponse({
      pairingSessionId: sessionId,
      pairingSecret: secret,
      serverOrigin: window.location.origin,
      expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
    }, 201);
  }

  // 16. Revoke / delete device
  const deviceDeleteMatch = path.match(/^\/api\/v1\/devices\/([^/]+)$/);
  if (deviceDeleteMatch && method === 'DELETE') {
    const devId = decodeURIComponent(deviceDeleteMatch[1]);
    devices = devices.filter((d) => d.id !== devId);
    return emptyResponse(204);
  }

  // Fallback to real fetch for unhandled requests
  if (originalFetch) {
    return originalFetch(input, init);
  }
  return jsonResponse({ code: 'not_found', message: 'Not found' }, 404);
}

// Custom mock XMLHttpRequest to support progress bars during file uploads
class MockXMLHttpRequest extends EventTarget {
  public upload: EventTarget = new EventTarget();
  public readyState = 0;
  public status = 0;
  public statusText = '';
  public responseText = '';
  public response: unknown = null;
  public withCredentials = false;
  public onload: (() => void) | null = null;
  public onerror: (() => void) | null = null;
  public onreadystatechange: (() => void) | null = null;

  private _url = '';
  private _method = 'GET';
  private _headers: Record<string, string> = {};

  public open(method: string, url: string) {
    this._method = method;
    this._url = url;
    this.readyState = 1;
    this.onreadystatechange?.();
  }

  public setRequestHeader(name: string, value: string) {
    this._headers[name] = value;
  }

  public send(body?: Document | XMLHttpRequestBodyInit | null) {
    // If it's a file staging upload PUT
    if (this._method.toUpperCase() === 'PUT' && this._url.includes('/api/v1/uploads/')) {
      const fileSize = body instanceof Blob ? body.size : 1024 * 64;
      const urlMatch = this._url.match(/\/api\/v1\/uploads\/([^/]+)/);
      const currentUploadId = urlMatch ? decodeURIComponent(urlMatch[1]) : 'mock-upload';

      // Simulate progress ticks
      window.setTimeout(() => {
        const p1 = new ProgressEvent('progress', {
          lengthComputable: true,
          loaded: Math.round(fileSize * 0.45),
          total: fileSize,
        });
        this.upload.dispatchEvent(p1);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (this.upload as any).onprogress?.(p1);
      }, 80);

      window.setTimeout(() => {
        const p2 = new ProgressEvent('progress', {
          lengthComputable: true,
          loaded: fileSize,
          total: fileSize,
        });
        this.upload.dispatchEvent(p2);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (this.upload as any).onprogress?.(p2);

        this.readyState = 4;
        this.status = 200;
        this.statusText = 'OK';
        this.responseText = JSON.stringify({
          uploadId: currentUploadId,
          state: 'staged',
          sizeBytes: fileSize,
          sha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
        });
        this.response = this.responseText;
        this.onreadystatechange?.();
        this.onload?.();
      }, 220);
      return;
    }

    // Default immediate 200 for other PUT/POST requests
    window.setTimeout(() => {
      this.readyState = 4;
      this.status = 200;
      this.statusText = 'OK';
      this.responseText = '{}';
      this.response = '{}';
      this.onreadystatechange?.();
      this.onload?.();
    }, 100);
  }

  public abort() {
    this.readyState = 0;
    this.status = 0;
  }
}

export function installDemoMockTransport() {
  if (isActive) return;
  isActive = true;
  resetDemoMockData();

  if (!originalFetch) {
    originalFetch = window.fetch;
  }
  if (!originalXHR) {
    originalXHR = window.XMLHttpRequest;
  }

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    if (!isActive) {
      return (originalFetch || fetch)(input, init);
    }
    const urlString = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    // Intercept only Vault API routes and health check
    if (
      urlString.startsWith('/api/v1') ||
      urlString.startsWith('/health/ready') ||
      urlString.includes('/api/v1/') ||
      urlString.includes('/health/ready')
    ) {
      return handleMockFetch(input, init);
    }
    return (originalFetch || fetch)(input, init);
  };

  // Intercept XHR for upload streaming
  // @ts-expect-error MockXMLHttpRequest is a lightweight simulation of XMLHttpRequest
  window.XMLHttpRequest = MockXMLHttpRequest;
}

export function uninstallDemoMockTransport() {
  if (!isActive) return;
  isActive = false;
  if (originalFetch) {
    window.fetch = originalFetch;
    originalFetch = null;
  }
  if (originalXHR) {
    window.XMLHttpRequest = originalXHR;
    originalXHR = null;
  }
  if (pairingTimer) {
    window.clearTimeout(pairingTimer);
    pairingTimer = null;
  }
}

export function isDemoMockActive(): boolean {
  return isActive;
}
