import type { ItemDetail, ItemSummary, Quota, Session } from './types';

export const DEMO_SESSION: Session = {
  userId: 'demo-user-001',
  email: 'visitor@vault.local',
  deviceId: 'demo-browser-device',
  username: 'Demo Visitor',
};

export const DEMO_QUOTA: Quota = {
  activeBytes: 42_350_000,
  maxActiveBytes: 2_000_000_000,
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

export const DEMO_DETAILS: Record<string, ItemDetail> = {
  'demo-file-1': {
    ...INITIAL_DEMO_ITEMS[0],
    text: null,
    contentType: 'image/png',
    sha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
    downloadUrl: '#demo-download',
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
