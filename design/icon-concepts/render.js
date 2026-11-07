// Renders sheet.html to sheet.png: node design/icon-concepts/render.js
const path = require('path');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1244, height: 800 }, deviceScaleFactor: 2 });
  await page.goto('file://' + path.join(__dirname, 'sheet.html'));
  await page.screenshot({ path: path.join(__dirname, 'sheet.png'), fullPage: true });
  await browser.close();
})();
