import type { FormEvent, RefObject } from 'react';
import { ArrowRightIcon, ShieldLockIcon } from '../Icons';

type AuthLayoutProps = {
  serviceOnline: boolean | null;
  identifier: string;
  setIdentifier: (val: string) => void;
  password: string;
  setPassword: (val: string) => void;
  busy: boolean;
  retrySeconds: number;
  message: string;
  messageKind: 'error' | 'notice';
  identifierRef: RefObject<HTMLInputElement | null>;
  onLogin: (e: FormEvent<HTMLFormElement>) => void | Promise<void>;
};

export default function AuthLayout({
  serviceOnline,
  identifier,
  setIdentifier,
  password,
  setPassword,
  busy,
  retrySeconds,
  message,
  messageKind,
  identifierRef,
  onLogin,
}: AuthLayoutProps) {
  return (
    <section className="auth-layout" aria-labelledby="welcome-title">
      <div className="intro-panel">
        <p className="eyebrow">
          <span className="eyebrow-line" /> YOUR DEVICES, IN SYNC
        </p>
        <h1 id="welcome-title">
          A quieter way
          <br />
          to move things.
        </h1>
        <p className="intro">
          An encrypted personal relay between your Android phone and desktop browser.
          Sign in to access your Vault.
        </p>
        <div
          className={`service-status ${serviceOnline === false ? 'service-status--offline' : 'service-status--ready'}`}
          role="status"
        >
          <span className="status-dot" />
          <span>
            {serviceOnline === null
              ? 'Checking Vault'
              : serviceOnline
                ? 'Vault service ready'
                : 'Vault is having trouble connecting'}
          </span>
        </div>
      </div>
      <section className="auth-card" aria-labelledby="signin-title">
        <div className="auth-card-top">
          <span className="card-kicker">PRIVATE SPACE</span>
          <span className="secure-label">
            <ShieldLockIcon size={12} className="secure-icon" /> SECURE SIGN-IN
          </span>
        </div>
        <h2 id="signin-title">Welcome back</h2>
        <p className="auth-description">Enter your Vault credentials to continue.</p>
        <form onSubmit={onLogin} noValidate>
          <div className="field-group">
            <label htmlFor="identifier">Email or username</label>
            <input
              ref={identifierRef}
              id="identifier"
              name="identifier"
              type="text"
              autoComplete="username"
              inputMode="text"
              required
              value={identifier}
              onChange={(event) => setIdentifier(event.target.value)}
              disabled={busy}
              placeholder="Email address or username"
            />
          </div>
          <div className="field-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={busy}
            />
          </div>
          <div
            className={`form-message ${message ? (messageKind === 'error' ? 'form-message--error' : 'form-message--notice') : ''}`}
            role={messageKind === 'error' ? 'alert' : 'status'}
            aria-live="polite"
          >
            {message}
          </div>
          <button
            className="primary-button"
            type="submit"
            disabled={busy || retrySeconds > 0 || !identifier.trim() || !password}
          >
            <span>
              {busy ? 'Signing in…' : retrySeconds > 0 ? `Try again in ${retrySeconds}s` : 'Sign in'}
            </span>
            <ArrowRightIcon size={16} />
          </button>
        </form>
        <p className="card-footnote">
          <ShieldLockIcon size={13} className="footnote-icon" />
          Your credentials are only transmitted over this encrypted connection.
        </p>
      </section>
    </section>
  );
}
