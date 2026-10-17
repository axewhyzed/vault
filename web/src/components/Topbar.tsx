import type { RefObject } from 'react';
import type { Session, Quota, ViewState } from '../types';
import { formatBytes } from '../utils';
import {
  ChevronDownIcon,
  MoonIcon,
  QrCodeIcon,
  ShieldLockIcon,
  SunIcon,
} from '../Icons';

export function VaultMark() {
  return (
    <svg aria-hidden="true" viewBox="0 0 32 32" className="vault-mark">
      <rect x="2" y="2" width="28" height="28" rx="9" fill="currentColor" />
      <path d="M9 11.25h2.55l4.45 10.1 4.45-10.1H23l-6.05 13.5h-1.9L9 11.25Z" fill="var(--paper, #f5f4ef)" />
      <circle cx="23.1" cy="8.9" r="1.35" fill="#10B981" />
    </svg>
  );
}

type TopbarProps = {
  view: ViewState;
  session: Session | null;
  quota: Quota | null;
  serviceOnline: boolean | null;
  theme: 'light' | 'dark';
  toggleTheme: () => void;
  userMenuOpen: boolean;
  setUserMenuOpen: React.Dispatch<React.SetStateAction<boolean>>;
  userMenuRef: RefObject<HTMLDivElement | null>;
  busy: boolean;
  onOpenPairModal: () => void;
  onOpenSecurityModal: () => void;
  onLogout: () => void;
};

export default function Topbar({
  view,
  session,
  quota,
  serviceOnline,
  theme,
  toggleTheme,
  userMenuOpen,
  setUserMenuOpen,
  userMenuRef,
  busy,
  onOpenPairModal,
  onOpenSecurityModal,
  onLogout,
}: TopbarProps) {
  const bytePercent = quota ? Math.min(100, (quota.activeBytes / quota.maxActiveBytes) * 100) : 0;
  const displayName = session?.username || session?.email || '';
  const avatarInitial = displayName ? displayName.charAt(0).toUpperCase() : 'U';

  return (
    <header className="topbar">
      <div className="topbar-left">
        <a className="brand" href="/" aria-label="Vault home">
          <VaultMark />
          <span>vault</span>
        </a>
        {view === 'signed-in' && (
          <div
            className={`topbar-status-badge ${serviceOnline === false ? 'is-offline' : 'is-ready'}`}
            title={serviceOnline === false ? 'Service connection issue' : 'Vault personal relay is active and ready'}
          >
            <span className="status-dot-pulse" aria-hidden="true" />
            <span>{serviceOnline === false ? 'Offline' : 'Ready'}</span>
          </div>
        )}
      </div>

      {/* Center: Storage Pill Meter */}
      {view === 'signed-in' && (
        <div
          className="storage-pill"
          title={
            quota
              ? `${quota.activeItems.toLocaleString()} of ${quota.maxActiveItems.toLocaleString()} items · ${formatBytes(quota.activeBytes)} of ${formatBytes(quota.maxActiveBytes)} used`
              : 'Loading quota usage…'
          }
        >
          <div
            className="storage-pill-track"
            role="progressbar"
            aria-label="Storage quota usage"
            aria-valuemin={0}
            aria-valuemax={quota?.maxActiveBytes ?? 2_000_000_000}
            aria-valuenow={quota?.activeBytes ?? 0}
          >
            <span className="storage-pill-fill" style={{ width: `${bytePercent}%` }} />
          </div>
          <span className="storage-pill-text">
            {quota
              ? `${formatBytes(quota.activeBytes)} / ${formatBytes(quota.maxActiveBytes)} · ${bytePercent.toFixed(0)}%`
              : 'Storage'}
          </span>
        </div>
      )}

      {/* Right: Actions, Theme Toggle, User Menu */}
      <div className="topbar-right">
        {view === 'signed-in' && (
          <button
            type="button"
            className="topbar-action-btn"
            onClick={onOpenPairModal}
            aria-label="Pair Android Phone"
          >
            <QrCodeIcon size={15} />
            <span className="hide-mobile">Pair Phone</span>
          </button>
        )}

        <button
          type="button"
          className="theme-toggle-btn"
          onClick={toggleTheme}
          aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
          title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
        >
          {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
        </button>

        {view === 'signed-in' && session && (
          <div className="user-menu-container" ref={userMenuRef}>
            <button
              type="button"
              className="user-menu-btn"
              onClick={() => setUserMenuOpen((prev) => !prev)}
              aria-expanded={userMenuOpen}
              aria-haspopup="true"
              aria-label="Account and session settings"
            >
              <span className="user-avatar-initial" aria-hidden="true">
                {avatarInitial}
              </span>
              <span className="user-email-text hide-mobile">{displayName}</span>
              <ChevronDownIcon size={12} className={`chevron-indicator ${userMenuOpen ? 'is-open' : ''}`} />
            </button>

            {userMenuOpen && (
              <div className="user-menu-dropdown" role="menu">
                <div className="dropdown-account-header">
                  <span className="account-username">{displayName}</span>
                  {session.username && <span className="account-email">{session.email}</span>}
                  <span className="account-tag">
                    <span className="status-dot-pulse" aria-hidden="true" />
                    Web Client · Current session
                  </span>
                </div>

                <div className="dropdown-divider" role="separator" />

                <button
                  type="button"
                  className="dropdown-item"
                  role="menuitem"
                  onClick={onLogout}
                  disabled={busy}
                >
                  <span>{busy ? 'Signing out…' : 'Sign out'}</span>
                </button>

                <div className="dropdown-divider" role="separator" />

                <button
                  type="button"
                  className="dropdown-item dropdown-item--danger"
                  role="menuitem"
                  onClick={() => {
                    setUserMenuOpen(false);
                    onOpenSecurityModal();
                  }}
                >
                  <ShieldLockIcon size={14} />
                  <span>Security: Revoke all devices…</span>
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  );
}
