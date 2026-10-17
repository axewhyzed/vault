export type Session = {
  userId: string;
  email: string;
  deviceId: string;
  username?: string | null;
};

export type Csrf = {
  token: string;
  headerName: 'X-CSRF-TOKEN';
};

export type ViewState = 'loading' | 'signed-out' | 'signed-in';

export type ItemSummary = {
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
  textContent?: string | null;
};

export type ItemDetail = ItemSummary & {
  text: string | null;
  contentType?: string;
  sha256?: string;
  downloadUrl?: string;
};

export type Quota = {
  activeBytes: number;
  maxActiveBytes: number;
  activeItems: number;
  maxActiveItems: number;
};

export type ItemPage = {
  items: ItemSummary[];
  nextCursor: string | null;
  quota: Quota;
};

export type ApiError = {
  code?: string;
  message?: string;
  correlationId?: string;
};

export type DeviceSummary = {
  id: string;
  name: string;
  platform: 'android' | 'web' | string;
  createdAt: string;
  isCurrent: boolean;
};

export type DeviceListResponse = {
  devices: DeviceSummary[];
};

