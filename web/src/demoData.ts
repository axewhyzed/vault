import type { ItemDetail, ItemSummary, Quota, Session } from './types';

export const DEMO_SESSION: Session = {
  userId: 'demo-user-001',
  email: 'visitor@vault.local',
  deviceId: 'demo-browser-device',
  username: 'Demo Visitor',
};

export const DEMO_QUOTA: Quota = {
  activeBytes: 1_842_472,
  maxActiveBytes: 10_485_760,
  activeItems: 3,
  maxActiveItems: 250,
};

const now = new Date();
const fifteenMinutesAgo = new Date(now.getTime() - 15 * 60 * 1000).toISOString();
const twoHoursAgo = new Date(now.getTime() - 2 * 60 * 60 * 1000).toISOString();
const yesterday = new Date(now.getTime() - 22 * 60 * 60 * 1000).toISOString();
const inSevenDays = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000).toISOString();
const inThreeDays = new Date(now.getTime() + 3 * 24 * 60 * 60 * 1000).toISOString();
const inOneDay = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();

export const INITIAL_DEMO_ITEMS: ItemSummary[] = [
  {
    id: 'demo-file-1',
    type: 'file',
    state: 'available',
    displayName: 'vault-architecture-overview.png',
    sizeBytes: 1_842_190,
    createdAt: fifteenMinutesAgo,
    availableAt: fifteenMinutesAgo,
    expiresAt: inSevenDays,
    version: 1,
    sourceDevice: { id: 'd-android', name: 'Pixel 9 Pro', platform: 'android' },
    textContent: null,
  },
  {
    id: 'demo-url-2',
    type: 'url',
    state: 'available',
    displayName: 'Docker Compose Deployment Guide',
    sizeBytes: 38,
    createdAt: twoHoursAgo,
    availableAt: twoHoursAgo,
    expiresAt: inThreeDays,
    version: 1,
    sourceDevice: { id: 'd-web', name: 'MacBook Pro Chrome', platform: 'web' },
    textContent: 'https://docs.docker.com/compose/',
  },
  {
    id: 'demo-text-3',
    type: 'text',
    state: 'available',
    displayName: 'Office Guest WiFi & Gateway Config',
    sizeBytes: 132,
    createdAt: yesterday,
    availableAt: yesterday,
    expiresAt: inOneDay,
    version: 1,
    sourceDevice: { id: 'd-android', name: 'Pixel 9 Pro', platform: 'android' },
    textContent:
      'SSID: Vault-Secure-LAN\nPass: 8x!k9#mQ2$pL\nGateway: 192.168.1.150\nPort: 8443\nFingerprint: SHA256:7a8f9c1e',
  },
];

export const DEMO_SVG_PREVIEW = `data:image/svg+xml;utf8,${encodeURIComponent(`
<svg xmlns="http://www.w3.org/2000/svg" width="600" height="340" viewBox="0 0 600 340">
  <rect width="600" height="340" fill="#0f1117" rx="12"/>
  <rect x="30" y="30" width="540" height="280" fill="#181a24" stroke="#2d3142" stroke-width="2" rx="8"/>
  <circle cx="70" cy="70" r="14" fill="#6366f1"/>
  <text x="100" y="75" fill="#f3f4f6" font-family="system-ui, sans-serif" font-size="16" font-weight="600">Vault Secure Sync Architecture</text>
  <rect x="60" y="110" width="130" height="70" fill="#232736" stroke="#4f46e5" stroke-width="1.5" rx="6"/>
  <text x="125" y="142" fill="#e0e7ff" font-family="system-ui, sans-serif" font-size="13" text-anchor="middle" font-weight="500">Android Client</text>
  <text x="125" y="162" fill="#9ca3af" font-family="system-ui, sans-serif" font-size="11" text-anchor="middle">Kotlin + Compose</text>
  <path d="M 190 145 L 250 145" stroke="#6366f1" stroke-width="2" stroke-dasharray="4,4"/>
  <rect x="250" y="110" width="130" height="70" fill="#232736" stroke="#10b981" stroke-width="1.5" rx="6"/>
  <text x="315" y="142" fill="#d1fae5" font-family="system-ui, sans-serif" font-size="13" text-anchor="middle" font-weight="500">ASP.NET Core</text>
  <text x="315" y="162" fill="#9ca3af" font-family="system-ui, sans-serif" font-size="11" text-anchor="middle">Minimal API (.NET 10)</text>
  <path d="M 380 145 L 440 145" stroke="#10b981" stroke-width="2" stroke-dasharray="4,4"/>
  <rect x="440" y="110" width="120" height="70" fill="#232736" stroke="#38bdf8" stroke-width="1.5" rx="6"/>
  <text x="500" y="142" fill="#e0f2fe" font-family="system-ui, sans-serif" font-size="13" text-anchor="middle" font-weight="500">Web App</text>
  <text x="500" y="162" fill="#9ca3af" font-family="system-ui, sans-serif" font-size="11" text-anchor="middle">React 19 + TS</text>
  <rect x="180" y="215" width="270" height="60" fill="#1f2333" stroke="#374151" stroke-width="1" rx="6"/>
  <text x="315" y="242" fill="#f9fafb" font-family="system-ui, sans-serif" font-size="12" text-anchor="middle" font-weight="500">Zero-Knowledge Storage &amp; SQLite</text>
  <text x="315" y="260" fill="#9ca3af" font-family="system-ui, sans-serif" font-size="11" text-anchor="middle">Chunked SHA-256 Staged Ingest</text>
</svg>
`)}`;

export const DEMO_DETAILS: Record<string, ItemDetail> = {
  'demo-file-1': {
    ...INITIAL_DEMO_ITEMS[0],
    text: null,
    contentType: 'image/svg+xml',
    sha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
    downloadUrl: DEMO_SVG_PREVIEW,
  },
  'demo-url-2': {
    ...INITIAL_DEMO_ITEMS[1],
    text: 'https://docs.docker.com/compose/',
    contentType: 'text/uri-list',
    sha256: undefined,
    downloadUrl: undefined,
  },
  'demo-text-3': {
    ...INITIAL_DEMO_ITEMS[2],
    text: 'SSID: Vault-Secure-LAN\nPass: 8x!k9#mQ2$pL\nGateway: 192.168.1.150\nPort: 8443\nFingerprint: SHA256:7a8f9c1e',
    contentType: 'text/plain',
    sha256: undefined,
    downloadUrl: undefined,
  },
};
