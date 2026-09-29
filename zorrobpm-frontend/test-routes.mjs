import { chromium } from 'playwright';

const BASE = 'http://localhost:5173';

const ROUTES = [
  { path: '/', name: 'Dashboard' },
  { path: '/processes/definitions', name: 'Process Definitions' },
  { path: '/processes/instances', name: 'Process Instances' },
  { path: '/processes/deploy', name: 'Deploy Process' },
  { path: '/tasks', name: 'Tasks' },
  { path: '/service-tasks', name: 'Service Tasks' },
  { path: '/incidents', name: 'Incidents' },
  { path: '/timers', name: 'Timers' },
  { path: '/messages', name: 'Messages' },
  { path: '/dmn', name: 'DMN' },
  { path: '/analytics', name: 'Analytics' },
  { path: '/admin/users', name: 'Users' },
];

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  const results = [];

  for (const route of ROUTES) {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));

    try {
      const response = await page.goto(`${BASE}${route.path}`, {
        waitUntil: 'networkidle',
        timeout: 15000,
      });
      const status = response?.status() ?? 'N/A';
      const bodyText = await page.locator('body').innerText({ timeout: 5000 }).catch(() => '');
      results.push({ name: route.name, status, hasContent: bodyText.trim().length > 20, pageErrors: errors.length });
    } catch (err) {
      results.push({ name: route.name, status: 'ERROR', hasContent: false, pageErrors: 1 });
    }
    page.removeAllListeners('pageerror');
  }

  console.log('\n=== ROUTE TEST RESULTS ===\n');
  for (const r of results) {
    const icon = r.status === 200 && r.pageErrors === 0 ? '✅' : '❌';
    console.log(`${icon} ${r.name} — HTTP ${r.status}, content: ${r.hasContent ? 'yes' : 'no'}, errors: ${r.pageErrors}`);
  }

  const passed = results.filter((r) => r.status === 200 && r.pageErrors === 0).length;
  console.log(`\n${passed}/${results.length} routes passed`);

  await browser.close();
})();
