import { useCallback, useEffect, useState } from 'react';
import type { DeviceSummary, DeviceListResponse } from '../types';
import {
  CloseIcon,
  DevicesIcon,
  LaptopIcon,
  QrCodeIcon,
  RefreshIcon,
  ShieldLockIcon,
  SmartphoneIcon,
} from '../Icons';

type Props = {
  isOpen: boolean;
  onClose: () => void;
  onOpenPairModal: () => void;
  onOpenSecurityModal: () => void;
  onUnauthorized: () => void;
};

export default function ConnectedDevicesModal({
  isOpen,
  onClose,
  onOpenPairModal,
  onOpenSecurityModal,
  onUnauthorized,
}: Props) {
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchDevices = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('/api/v1/devices', {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
      });
      if (res.status === 401) {
        onUnauthorized();
        return;
      }
      if (!res.ok) {
        throw new Error('Could not load connected devices. Try again.');
      }
      const data = (await res.json()) as DeviceListResponse;
      setDevices(data.devices || []);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to fetch devices');
    } finally {
      setLoading(false);
    }
  }, [onUnauthorized]);

  useEffect(() => {
    if (!isOpen) return;
    void fetchDevices();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, fetchDevices, onClose]);

  if (!isOpen) return null;

  return (
    <div className="modal-backdrop" onClick={onClose} role="presentation">
      <div
        className="modal-card modal-card--devices"
        role="dialog"
        aria-modal="true"
        aria-labelledby="devices-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div className="modal-header-copy">
            <span className="card-kicker">
              <DevicesIcon size={13} /> SESSIONS & RELAY
            </span>
            <h2 id="devices-modal-title">Connected Devices</h2>
          </div>
          <div className="modal-header-actions">
            <button
              type="button"
              className="icon-btn-compact"
              onClick={() => void fetchDevices()}
              disabled={loading}
              title="Refresh devices"
              aria-label="Refresh devices"
            >
              <RefreshIcon size={15} className={loading ? 'is-spinning' : ''} />
            </button>
            <button
              type="button"
              className="modal-close-btn"
              onClick={onClose}
              aria-label="Close dialog"
            >
              <CloseIcon size={18} />
            </button>
          </div>
        </div>

        <p className="modal-description">
          These devices are authenticated and actively paired with your Vault account.
        </p>

        {error && (
          <p className="modal-status-message modal-status-message--error" role="alert">
            {error}
          </p>
        )}

        <div className="devices-list" role="list">
          {loading && devices.length === 0 ? (
            <div className="devices-loading-state">
              <RefreshIcon size={18} className="is-spinning" />
              <span>Fetching connected devices…</span>
            </div>
          ) : devices.length === 0 ? (
            <div className="devices-empty-state">
              <p>No active devices found.</p>
            </div>
          ) : (
            devices.map((device) => {
              const isAndroid = device.platform.toLowerCase() === 'android';
              const formattedDate = new Date(device.createdAt).toLocaleDateString(undefined, {
                month: 'short',
                day: 'numeric',
                year: 'numeric',
              });

              return (
                <div key={device.id} className="device-item-card" role="listitem">
                  <div className={`device-icon-wrapper ${isAndroid ? 'is-android' : 'is-web'}`}>
                    {isAndroid ? <SmartphoneIcon size={20} /> : <LaptopIcon size={20} />}
                  </div>
                  <div className="device-item-info">
                    <div className="device-item-title-row">
                      <span className="device-item-name">{device.name}</span>
                      {device.isCurrent && (
                        <span className="device-badge device-badge--current">This browser</span>
                      )}
                      {!device.isCurrent && (
                        <span className="device-badge device-badge--active">Active</span>
                      )}
                    </div>
                    <span className="device-item-meta">
                      {isAndroid ? 'Android Native Client' : 'Web Desktop Workspace'} · Linked {formattedDate}
                    </span>
                  </div>
                </div>
              );
            })
          )}
        </div>

        <div className="modal-actions modal-actions--devices">
          <button
            type="button"
            className="secondary-button"
            onClick={() => {
              onClose();
              onOpenPairModal();
            }}
          >
            <QrCodeIcon size={14} />
            <span>Pair another phone…</span>
          </button>
          <button
            type="button"
            className="ghost-button ghost-button--danger"
            onClick={() => {
              onClose();
              onOpenSecurityModal();
            }}
          >
            <ShieldLockIcon size={14} />
            <span>Sign out everywhere…</span>
          </button>
        </div>
      </div>
    </div>
  );
}
