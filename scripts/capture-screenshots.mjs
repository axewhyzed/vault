import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { chromium } from '../web/node_modules/playwright/index.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..');
const distDir = path.join(repoRoot, 'web', 'dist');
const assetsDir = path.join(repoRoot, 'docs', 'assets');

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.png': 'image/png',
  '.ico': 'image/x-icon'
};

async function run() {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost:4173');
    let filePath = path.join(distDir, url.pathname === '/' ? 'index.html' : url.pathname);
    if (!fs.existsSync(filePath)) {
      filePath = path.join(distDir, 'index.html'); // SPA fallback
    }
    const ext = path.extname(filePath);
    res.writeHead(200, {
      'Content-Type': mimeTypes[ext] || 'application/octet-stream',
      'Cache-Control': 'no-store'
    });
    fs.createReadStream(filePath).pipe(res);
  });

  await new Promise(resolve => server.listen(4173, resolve));
  console.log('Static preview server listening on http://localhost:4173');

  const browser = await chromium.launch({
    channel: 'msedge',
    headless: true,
    args: ['--ignore-certificate-errors', '--allow-insecure-localhost', '--disable-web-security']
  });

  const context = await browser.newContext({
    viewport: { width: 1440, height: 960 },
    deviceScaleFactor: 2
  });

  const page = await context.newPage();

  const mockSession = {
    userId: 'usr_owner_01',
    email: 'owner@vault.local',
    deviceId: 'dev_web_macbook'
  };

  const mockQuota = {
    activeBytes: 14250000,
    maxActiveBytes: 2000000000, // 2 GB
    activeItems: 3,
    maxActiveItems: 10000
  };

  const mockItems = [
    {
      id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
      type: 'file',
      state: 'available',
      displayName: 'quarterly-infrastructure-report.pdf',
      sizeBytes: 8420000,
      createdAt: new Date(Date.now() - 3600000 * 2).toISOString(),
      availableAt: new Date(Date.now() - 3600000 * 2).toISOString(),
      expiresAt: new Date(Date.now() + 86400000 * 6).toISOString(),
      version: 1,
      sourceDevice: { id: 'dev_phone_01', name: 'OnePlus 12 (Android)', platform: 'android' }
    },
    {
      id: 'b2c3d4e5-f6a7-8901-bcde-f12345678901',
      type: 'url',
      state: 'available',
      displayName: 'https://github.com/axewhyzed/vault',
      sizeBytes: 35,
      createdAt: new Date(Date.now() - 3600000 * 5).toISOString(),
      availableAt: new Date(Date.now() - 3600000 * 5).toISOString(),
      expiresAt: new Date(Date.now() + 86400000 * 4).toISOString(),
      version: 1,
      sourceDevice: { id: 'dev_phone_01', name: 'OnePlus 12 (Android)', platform: 'android' }
    },
    {
      id: 'c3d4e5f6-a7b8-9012-cdef-123456789012',
      type: 'text',
      state: 'available',
      displayName: 'Tailscale Exit Nodes & WireGuard Config',
      sizeBytes: 284,
      createdAt: new Date(Date.now() - 3600000 * 18).toISOString(),
      availableAt: new Date(Date.now() - 3600000 * 18).toISOString(),
      expiresAt: new Date(Date.now() + 86400000 * 2).toISOString(),
      version: 1,
      sourceDevice: { id: 'dev_phone_01', name: 'OnePlus 12 (Android)', platform: 'android' }
    }
  ];

  await page.route('**/health/ready*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ status: 'ready' })
  }));

  await page.route('**/api/v1/auth/csrf*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ token: 'mock-csrf-token', headerName: 'X-CSRF-TOKEN' })
  }));

  let isSignedIn = true;

  await page.route('**/api/v1/auth/me*', route => {
    if (isSignedIn) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockSession)
      });
    } else {
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'unauthorized', message: 'Not signed in' })
      });
    }
  });

  await page.route('**/api/v1/items*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      items: mockItems,
      nextCursor: null,
      quota: mockQuota
    })
  }));

  await page.route('**/api/v1/devices/pairing-sessions*', route => route.fulfill({
    status: 201,
    contentType: 'application/json',
    body: JSON.stringify({
      pairingSessionId: 'e8f96e27-5d01-4be6-a212-6f29bf429b67',
      pairingSecret: '7xK9sW2mPq4tUv8yB1cE3gH5jL7nQ9rT1vX3zA5bC7d',
      serverOrigin: 'http://localhost:4173',
      expiresAt: new Date(Date.now() + 590000).toISOString()
    })
  }));

  // 1. Capture Auth Screen
  console.log('Capturing Auth Screen...');
  isSignedIn = false;
  await page.goto('http://localhost:4173', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.auth-card', { timeout: 10000 });
  await page.evaluate(() => document.activeElement?.blur());
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(assetsDir, 'web-auth.png') });
  console.log('Saved web-auth.png');

  // 2. Capture Authenticated Inbox
  console.log('Capturing Authenticated Inbox...');
  isSignedIn = true;
  await page.goto('http://localhost:4173', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.inbox-layout', { timeout: 10000 });
  await page.evaluate(() => document.activeElement?.blur());
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(assetsDir, 'web-inbox.png') });
  console.log('Saved web-inbox.png');

  // 3. Click Pair Android & Capture Pairing Modal / QR code
  console.log('Generating pairing code...');
  const pairButton = page.locator('button.pairing-create');
  await pairButton.waitFor({ state: 'visible', timeout: 5000 });
  await pairButton.click();
  await page.waitForSelector('.pairing-qr', { timeout: 5000 });
  await page.evaluate(() => document.activeElement?.blur());
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(assetsDir, 'web-pairing.png') });
  console.log('Saved web-pairing.png');

  await browser.close();
  server.close();
  console.log('All screenshots captured successfully!');
  process.exit(0);
}

run().catch(err => {
  console.error('Fatal error:', err);
  process.exit(1);
});
