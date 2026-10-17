import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { chromium } from '../web/node_modules/playwright/index.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..');
const assetsDir = path.join(repoRoot, 'docs', 'assets');

const statusAndNavStyles = `
  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
    -webkit-font-smoothing: antialiased;
  }
  body {
    width: 360px;
    height: 800px;
    background: #0D1117;
    color: #E6EDF3;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    position: relative;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    user-select: none;
  }
  .status-bar {
    height: 38px;
    padding: 0 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 13px;
    font-weight: 600;
    color: #E6EDF3;
    letter-spacing: -0.2px;
    z-index: 100;
  }
  .status-icons {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .status-icons svg {
    width: 14px;
    height: 14px;
    fill: #E6EDF3;
  }
  .android-camera-hole {
    width: 12px;
    height: 12px;
    background: #000;
    border-radius: 50%;
    margin-left: -18px;
  }
  .bottom-nav {
    height: 68px;
    background: #161B22;
    border-top: 1px solid rgba(255, 255, 255, 0.08);
    display: flex;
    align-items: center;
    justify-content: space-around;
    padding: 0 12px 6px;
    z-index: 100;
  }
  .nav-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    color: #8B949E;
    font-size: 11px;
    font-weight: 500;
    flex: 1;
    cursor: pointer;
  }
  .nav-item.active {
    color: #388BFD;
  }
  .nav-pill {
    padding: 4px 18px;
    border-radius: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .nav-item.active .nav-pill {
    background: rgba(56, 139, 253, 0.18);
  }
  .nav-item svg {
    width: 20px;
    height: 20px;
    fill: currentColor;
  }
  .android-gesture-bar {
    position: absolute;
    bottom: 6px;
    left: 50%;
    transform: translateX(-50%);
    width: 110px;
    height: 4px;
    background: #8B949E;
    border-radius: 2px;
    opacity: 0.6;
    z-index: 200;
  }
`;

const statusBarHtml = `
  <div class="status-bar">
    <span>9:41</span>
    <div class="android-camera-hole"></div>
    <div class="status-icons">
      <svg viewBox="0 0 24 24"><path d="M12 3c-4.97 0-9 4.03-9 9 0 2.12.74 4.07 1.97 5.61L4.35 18.25A10.93 10.93 0 0 1 1 12C1 5.92 5.92 1 12 1s11 4.92 11 11c0 2.33-.72 4.49-1.95 6.27l-.62-.62A9.97 9.97 0 0 0 21 12c0-4.97-4.03-9-9-9zm0 4c-2.76 0-5 2.24-5 5 0 1.25.46 2.39 1.22 3.28l.71-.71A3.98 3.98 0 0 1 8 12c0-2.21 1.79-4 4-4s4 1.79 4 4c0 .99-.36 1.89-.95 2.59l.71.71A4.98 4.98 0 0 0 17 12c0-2.76-2.24-5-5-5zm0 4c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z"/></svg>
      <svg viewBox="0 0 24 24"><path d="M12 4C7.31 4 3.07 5.9 0 8.98L12 21 24 8.98A16.88 16.88 0 0 0 12 4z"/></svg>
      <svg viewBox="0 0 24 24"><path d="M17 4h-3V2h-4v2H7v18h10V4z"/></svg>
    </div>
  </div>
`;

// 1. Unpaired / Welcome Screen (AccountUi.kt)
const welcomeHtml = `
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
${statusAndNavStyles}
.welcome-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 32px 24px 40px;
}
.brand-mark {
  width: 58px;
  height: 58px;
  background: linear-gradient(135deg, #238636 0%, #2ea043 100%);
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 24px rgba(46, 160, 67, 0.35);
  margin-bottom: 24px;
}
.brand-mark svg {
  width: 32px;
  height: 32px;
  fill: #FFFFFF;
}
.kicker {
  color: #3FB950;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1.4px;
  text-transform: uppercase;
  margin-bottom: 8px;
}
.headline {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.18;
  color: #FFFFFF;
  margin-bottom: 12px;
  letter-spacing: -0.6px;
}
.subheadline {
  font-size: 14px;
  line-height: 1.5;
  color: #8B949E;
  margin-bottom: 24px;
}
.actions-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: auto;
}
.btn-primary {
  height: 52px;
  background: #238636;
  border-radius: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #FFFFFF;
  font-size: 15px;
  font-weight: 600;
  box-shadow: 0 4px 16px rgba(35, 134, 54, 0.35);
}
.btn-primary svg {
  width: 20px;
  height: 20px;
  fill: #FFFFFF;
}
.btn-secondary {
  height: 52px;
  background: #21262D;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #E6EDF3;
  font-size: 15px;
  font-weight: 600;
}
.btn-secondary svg {
  width: 18px;
  height: 18px;
  fill: #E6EDF3;
}
.security-note {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #8B949E;
  font-size: 11px;
  margin-top: 18px;
}
.security-note svg {
  width: 12px;
  height: 12px;
  fill: #3FB950;
}
</style>
</head>
<body>
  ${statusBarHtml}
  <div class="welcome-container">
    <div>
      <div class="brand-mark">
        <svg viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z"/></svg>
      </div>
      <div class="kicker">Personal Relay</div>
      <h1 class="headline">A quieter way<br>to move things.</h1>
      <p class="subheadline">Instant encrypted transfer of files, URLs, and clipboard snippets between your phone and desktop.</p>
    </div>

    <div class="actions-card">
      <div class="btn-primary">
        <svg viewBox="0 0 24 24"><path d="M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm13 0h3v3h-3v-3zm-3 3h3v3h-3v-3zm3 3h3v3h-3v-3zm-6-3h3v3h-3v-3zm0-3h3v3h-3v-3z"/></svg>
        <span>Scan QR Code</span>
      </div>
      <div class="btn-secondary">
        <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 3c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.2c-2.5 0-4.71-1.28-6-3.22.03-1.99 4-3.08 6-3.08 1.99 0 5.97 1.09 6 3.08-1.29 1.94-3.5 3.22-6 3.22z"/></svg>
        <span>Sign In with Credentials</span>
      </div>
      <div class="security-note">
        <svg viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>
        <span>14-day sliding session · Zero cloud storage</span>
      </div>
    </div>
  </div>
  <div class="android-gesture-bar"></div>
</body>
</html>
`;

// 2. Authenticated Inbox Screen (InboxUi.kt)
const inboxHtml = `
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
${statusAndNavStyles}
.top-bar {
  height: 52px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.app-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 700;
  color: #FFFFFF;
}
.status-pill {
  background: rgba(35, 134, 54, 0.2);
  border: 1px solid rgba(46, 160, 67, 0.4);
  padding: 3px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  color: #3FB950;
  display: flex;
  align-items: center;
  gap: 5px;
}
.status-dot {
  width: 6px;
  height: 6px;
  background: #3FB950;
  border-radius: 50%;
}
.content-area {
  flex: 1;
  padding: 8px 16px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
/* Storage Quota Card */
.quota-card {
  background: #161B22;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 14px 16px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.25);
}
.quota-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  margin-bottom: 8px;
}
.quota-title {
  color: #E6EDF3;
  font-weight: 600;
}
.quota-bytes {
  color: #8B949E;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.progress-bar {
  height: 6px;
  background: #21262D;
  border-radius: 3px;
  overflow: hidden;
  display: flex;
  margin-bottom: 8px;
}
.progress-fill-file {
  width: 8%;
  background: #388BFD;
}
.progress-fill-link {
  width: 1%;
  background: #D29922;
}
.progress-fill-text {
  width: 2%;
  background: #3FB950;
}
.quota-footer {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #8B949E;
}
/* Inbox Rows */
.inbox-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.inbox-row {
  background: #161B22;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 14px;
  padding: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.item-badge {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.badge-file {
  background: rgba(56, 139, 253, 0.15);
  color: #58A6FF;
}
.badge-link {
  background: rgba(210, 153, 34, 0.15);
  color: #D29922;
}
.badge-text {
  background: rgba(46, 160, 67, 0.15);
  color: #3FB950;
}
.item-badge svg {
  width: 18px;
  height: 18px;
  fill: currentColor;
}
.item-info {
  flex: 1;
  min-width: 0;
}
.item-title {
  font-size: 13px;
  font-weight: 600;
  color: #E6EDF3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 3px;
}
.item-meta {
  font-size: 11px;
  color: #8B949E;
  display: flex;
  align-items: center;
  gap: 6px;
}
.expiry-tag {
  color: #3FB950;
  font-weight: 500;
}
</style>
</head>
<body>
  ${statusBarHtml}
  <div class="top-bar">
    <div class="app-title">
      <span>Vault</span>
      <div class="status-pill">
        <span class="status-dot"></span>
        <span>Ready</span>
      </div>
    </div>
    <div style="color: #8B949E; font-size: 12px; font-weight: 500;">
      4 items
    </div>
  </div>

  <div class="content-area">
    <!-- Storage Quota Card -->
    <div class="quota-card">
      <div class="quota-header">
        <span class="quota-title">Vault storage</span>
        <span class="quota-bytes">14.3 MB of 2.0 GB</span>
      </div>
      <div class="progress-bar">
        <div class="progress-fill-file"></div>
        <div class="progress-fill-link"></div>
        <div class="progress-fill-text"></div>
      </div>
      <div class="quota-footer">
        <span>4 of 10,000 items</span>
        <span style="color: #3FB950;">1% used</span>
      </div>
    </div>

    <!-- Inbox List -->
    <div class="inbox-list">
      <!-- Item 1: File -->
      <div class="inbox-row">
        <div class="item-badge badge-file">
          <svg viewBox="0 0 24 24"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>
        </div>
        <div class="item-info">
          <div class="item-title">quarterly-infrastructure-report.pdf</div>
          <div class="item-meta">
            <span>8.4 MB</span>
            <span>·</span>
            <span>2h ago</span>
            <span>·</span>
            <span class="expiry-tag">5d left</span>
          </div>
        </div>
      </div>

      <!-- Item 2: Link -->
      <div class="inbox-row">
        <div class="item-badge badge-link">
          <svg viewBox="0 0 24 24"><path d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"/></svg>
        </div>
        <div class="item-info">
          <div class="item-title">https://github.com/axewhyzed/vault</div>
          <div class="item-meta">
            <span>Web link</span>
            <span>·</span>
            <span>5h ago</span>
            <span>·</span>
            <span class="expiry-tag">3d left</span>
          </div>
        </div>
      </div>

      <!-- Item 3: Text -->
      <div class="inbox-row">
        <div class="item-badge badge-text">
          <svg viewBox="0 0 24 24"><path d="M14 17H4v2h10v-2zm6-8H4v2h16V9zM4 15h16v-2H4v2zM4 5v2h16V5H4z"/></svg>
        </div>
        <div class="item-info">
          <div class="item-title">Tailscale Exit Nodes & WireGuard Config</div>
          <div class="item-meta">
            <span>284 B</span>
            <span>·</span>
            <span>18h ago</span>
            <span>·</span>
            <span class="expiry-tag">1d left</span>
          </div>
        </div>
      </div>

      <!-- Item 4: File -->
      <div class="inbox-row">
        <div class="item-badge badge-file">
          <svg viewBox="0 0 24 24"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>
        </div>
        <div class="item-info">
          <div class="item-title">backup-snapshot-2026-10-17.tar.zst</div>
          <div class="item-meta">
            <span>5.8 MB</span>
            <span>·</span>
            <span>yesterday</span>
            <span>·</span>
            <span class="expiry-tag">4d left</span>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Bottom Navigation Bar (Send, Inbox, Hub) -->
  <div class="bottom-nav">
    <div class="nav-item">
      <div class="nav-pill">
        <svg viewBox="0 0 24 24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
      </div>
      <span>Send</span>
    </div>
    <div class="nav-item active">
      <div class="nav-pill">
        <svg viewBox="0 0 24 24"><path d="M19 3H4.99c-1.11 0-1.98.89-1.98 2L3 19c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19v10z"/></svg>
      </div>
      <span>Inbox</span>
    </div>
    <div class="nav-item">
      <div class="nav-pill">
        <svg viewBox="0 0 24 24"><path d="M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"/></svg>
      </div>
      <span>Hub</span>
    </div>
  </div>
  <div class="android-gesture-bar"></div>
</body>
</html>
`;

// 3. Vault Hub Screen (HubUi.kt)
const hubHtml = `
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
${statusAndNavStyles}
.top-bar {
  height: 52px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.app-title {
  font-size: 20px;
  font-weight: 700;
  color: #FFFFFF;
}
.content-area {
  flex: 1;
  padding: 8px 16px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
.hub-card {
  background: #161B22;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 14px 16px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.25);
}
.hub-card-header {
  font-size: 13px;
  font-weight: 600;
  color: #E6EDF3;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ping-badge {
  background: rgba(35, 134, 54, 0.2);
  border: 1px solid rgba(46, 160, 67, 0.4);
  padding: 3px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  color: #3FB950;
  display: flex;
  align-items: center;
  gap: 5px;
}
.device-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}
.device-item:last-child {
  border-bottom: none;
}
.device-name {
  font-size: 13px;
  font-weight: 500;
  color: #E6EDF3;
}
.device-status {
  font-size: 11px;
  color: #8B949E;
}
.btn-action {
  height: 40px;
  background: #21262D;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #E6EDF3;
  font-size: 13px;
  font-weight: 600;
  margin-top: 8px;
}
.btn-danger {
  height: 40px;
  background: rgba(248, 81, 73, 0.1);
  border: 1px solid rgba(248, 81, 73, 0.35);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #F85149;
  font-size: 13px;
  font-weight: 600;
  margin-top: 8px;
}
</style>
</head>
<body>
  ${statusBarHtml}
  <div class="top-bar">
    <div class="app-title">Vault Hub</div>
    <div class="ping-badge">
      <span style="width: 6px; height: 6px; background: #3FB950; border-radius: 50%;"></span>
      <span>38 ms ping</span>
    </div>
  </div>

  <div class="content-area">
    <!-- Server Status Card -->
    <div class="hub-card">
      <div class="hub-card-header">
        <span>Server Connection</span>
        <span style="color: #3FB950; font-size: 11px; font-weight: 500;">Online</span>
      </div>
      <div style="font-size: 12px; color: #8B949E; font-family: monospace;">https://vault.axewhyzed.dev</div>
    </div>

    <!-- Connected Devices -->
    <div class="hub-card">
      <div class="hub-card-header">
        <span>Connected Devices (2)</span>
      </div>
      <div class="device-item">
        <div>
          <div class="device-name">Pixel 9 Pro</div>
          <div class="device-status">This phone · Android</div>
        </div>
        <span style="color: #3FB950; font-size: 11px; font-weight: 600;">Active</span>
      </div>
      <div class="device-item">
        <div>
          <div class="device-name">Chrome on Linux</div>
          <div class="device-status">Web Client · Paired</div>
        </div>
        <span style="color: #8B949E; font-size: 11px;">Active 2m ago</span>
      </div>
    </div>

    <!-- Local Cache Card -->
    <div class="hub-card">
      <div class="hub-card-header">
        <span>Local Device Cache</span>
        <span style="color: #8B949E; font-size: 12px;">14.3 MB spool</span>
      </div>
      <div style="font-size: 12px; color: #8B949E; margin-bottom: 6px;">Encrypted local SQLite & blob staging store.</div>
      <div class="btn-action">Clear Local Cache</div>
    </div>

    <!-- Account & Danger Zone -->
    <div class="hub-card">
      <div class="hub-card-header">
        <span>Account & Sessions</span>
      </div>
      <div class="btn-action">Sign Out (This Phone)</div>
      <div class="btn-danger">Sign Out Everywhere</div>
    </div>
  </div>

  <!-- Bottom Navigation Bar (Send, Inbox, Hub) -->
  <div class="bottom-nav">
    <div class="nav-item">
      <div class="nav-pill">
        <svg viewBox="0 0 24 24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
      </div>
      <span>Send</span>
    </div>
    <div class="nav-item">
      <div class="nav-pill">
        <svg viewBox="0 0 24 24"><path d="M19 3H4.99c-1.11 0-1.98.89-1.98 2L3 19c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19v10z"/></svg>
      </div>
      <span>Inbox</span>
    </div>
    <div class="nav-item active">
      <div class="nav-pill">
        <svg viewBox="0 0 24 24"><path d="M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"/></svg>
      </div>
      <span>Hub</span>
    </div>
  </div>
  <div class="android-gesture-bar"></div>
</body>
</html>
`;

async function main() {
  const browser = await chromium.launch({
    channel: 'msedge',
    headless: true
  });

  const context = await browser.newContext({
    viewport: { width: 360, height: 800 },
    deviceScaleFactor: 3.0 // 1080 x 2400
  });

  const page = await context.newPage();

  console.log('Generating android-unpaired.png...');
  await page.setContent(welcomeHtml, { waitUntil: 'load' });
  await page.screenshot({ path: path.join(assetsDir, 'android-unpaired.png') });
  console.log('Saved android-unpaired.png (1080x2400)');

  console.log('Generating android-client.png...');
  await page.setContent(inboxHtml, { waitUntil: 'load' });
  await page.screenshot({ path: path.join(assetsDir, 'android-client.png') });
  console.log('Saved android-client.png (1080x2400)');

  console.log('Generating android-paired.png...');
  await page.setContent(hubHtml, { waitUntil: 'load' });
  await page.screenshot({ path: path.join(assetsDir, 'android-paired.png') });
  console.log('Saved android-paired.png (1080x2400)');

  await browser.close();
  console.log('All Android visual assets generated successfully!');
}

main().catch(err => {
  console.error('Fatal error generating Android assets:', err);
  process.exit(1);
});
