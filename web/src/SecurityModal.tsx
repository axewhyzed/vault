import { useEffect } from 'react';
import { AlertTriangleIcon, CloseIcon, ShieldLockIcon } from './Icons';

type Props = {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => Promise<void>;
  busy: boolean;
  error?: string;
};

export default function SecurityModal({ isOpen, onClose, onConfirm, busy, error }: Props) {
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, busy, onClose]);

  if (!isOpen) return null;

  return (
    <div className="modal-backdrop" onClick={() => !busy && onClose()} role="presentation">
      <div
        className="modal-card modal-card--security"
        role="dialog"
        aria-modal="true"
        aria-labelledby="security-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div className="modal-header-copy">
            <span className="card-kicker card-kicker--danger">
              <ShieldLockIcon size={12} /> ACCOUNT SECURITY
            </span>
            <h2 id="security-modal-title">Sign out everywhere</h2>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            onClick={onClose}
            disabled={busy}
            aria-label="Close security dialog"
          >
            <CloseIcon size={18} />
          </button>
        </div>

        <div className="security-warning-callout">
          <div className="warning-icon-wrapper" aria-hidden="true">
            <AlertTriangleIcon size={22} />
          </div>
          <div className="warning-copy">
            <strong>Revoke all connected devices & sessions</strong>
            <p>
              This will immediately revoke every web session and Android refresh token.
              Any linked phones and browsers will be signed out instantly.
            </p>
          </div>
        </div>

        <p className="modal-description">
          You will also be signed out of this browser session and redirected to the sign-in page.
          Use this if you lost a device or suspect unauthorized access.
        </p>

        {error && (
          <p className="modal-status-message modal-status-message--error" role="alert">
            {error}
          </p>
        )}

        <div className="modal-actions">
          <button
            type="button"
            className="secondary-button"
            onClick={onClose}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            type="button"
            className="danger-button"
            onClick={() => void onConfirm()}
            disabled={busy}
          >
            <ShieldLockIcon size={14} />
            <span>{busy ? 'Revoking sessions…' : 'Confirm sign out everywhere'}</span>
          </button>
        </div>
      </div>
    </div>
  );
}
