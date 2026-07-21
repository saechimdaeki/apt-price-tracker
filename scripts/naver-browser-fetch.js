#!/usr/bin/env node
/*
 * 네이버 fin.land 매물 목록 조회 헬퍼.
 *
 * 배경: 네이버가 신규 매물 API(front-api)를 브라우저 TLS 지문 + 헤드리스 탐지 기반
 * WAF로 막아서, Java HttpClient/curl로는 항상 TOO_MANY_REQUESTS(429)가 난다.
 * 실제 헤드풀 Chrome에서 단지 페이지를 열고 그 페이지 컨텍스트에서 조회하면 정상 응답이 온다.
 *
 * 프로토콜(stdin/stdout, 한 줄에 JSON 하나):
 *   준비 완료:   {"ready":true}
 *   요청(stdin): {"id":1,"complexNo":"8762","orders":["PRICE_ASC"],"maxPages":1,"pageSize":30,"tradeType":"A1"}
 *   응답(stdout):{"id":1,"complexNo":"8762","pages":["<raw json>", ...]}
 *              또는 {"id":1,"error":"...","blocked":true}
 *   stdin EOF 시 브라우저를 닫고 종료.
 */
const readline = require('readline');
const { chromium } = require('playwright-core');

const CHANNEL = process.env.NAVER_BROWSER_CHANNEL || 'chrome';
const API = 'https://fin.land.naver.com/front-api/v1/complex/article/list';

let browser = null;
let page = null;
let primed = false; // fin.land 오리진 컨텍스트 확보 여부

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

async function ensureBrowser() {
  if (browser) return;
  browser = await chromium.launch({ channel: CHANNEL, headless: false });
  const ctx = await browser.newContext({ locale: 'ko-KR' });
  page = await ctx.newPage();
  // 프라이밍 후 메인프레임 문서 네비게이션(pstatic 404 리다이렉트 등)을 차단해
  // fin.land 오리진을 유지한다. 오리진만 유지되면 어떤 단지든 evaluate만으로 조회 가능.
  await page.route('**/*', (route) => {
    if (primed && route.request().isNavigationRequest() && route.request().frame() === page.mainFrame()) {
      return route.abort('aborted');
    }
    return route.continue();
  });
}

// goto는 세션당 1회(+컨텍스트 파괴 시 재시도)만: complexes/{id}에 commit 직후부터
// 네비게이션을 차단하면 페이지가 fin.land /map에 머물러 API 조회가 계속 된다.
async function primeContext(complexNo) {
  primed = false;
  await page.goto('https://fin.land.naver.com/complexes/' + complexNo, {
    waitUntil: 'commit',
    timeout: 25000,
  });
  primed = true;
}

async function fetchPage(complexNo, order, tradeType, pageSize, lastInfo, seed) {
  let lastErr;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      if (!primed) await primeContext(complexNo);
      return await page.evaluate(async (args) => {
        const body = {
          complexNumber: args.complexNo,
          tradeTypes: [args.tradeType],
          size: args.pageSize,
          userChannelType: 'MOBILE',
          articleSortType: args.order,
          lastInfo: args.lastInfo || [],
        };
        if (args.seed) body.seed = args.seed;
        const res = await fetch(args.api, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify(body),
        });
        return { status: res.status, text: await res.text() };
      }, { api: API, complexNo, order, tradeType, pageSize, lastInfo, seed });
    } catch (e) {
      lastErr = e;
      primed = false; // 컨텍스트 파괴 → 다음 시도에서 재프라이밍
      await page.waitForTimeout(800);
    }
  }
  return { status: -1, text: 'fetch-failed: ' + (lastErr && lastErr.message) };
}

async function handle(req) {
  const orders = (req.orders && req.orders.length) ? req.orders : ['PRICE_ASC'];
  const maxPages = Math.max(1, req.maxPages || 1);
  const pageSize = Math.min(30, Math.max(1, req.pageSize || 30));
  const tradeType = req.tradeType || 'A1';

  await ensureBrowser();

  const pages = [];
  let firstOrder = true;
  for (const order of orders) {
    // 이전엔 order마다 goto가 자연 딜레이 역할을 했으므로, 그에 준하는 간격만 유지
    if (!firstOrder) await page.waitForTimeout(400 + Math.floor(Math.random() * 500));
    firstOrder = false;
    let lastInfo = null;
    let seed = '';
    for (let p = 0; p < maxPages; p++) {
      const r = await fetchPage(String(req.complexNo), order, tradeType, pageSize, lastInfo, seed);
      if (r.status === 429) {
        return { id: req.id, complexNo: req.complexNo, error: 'TOO_MANY_REQUESTS', blocked: true };
      }
      if (r.status !== 200) {
        return { id: req.id, complexNo: req.complexNo, error: 'HTTP ' + r.status, pages };
      }
      pages.push(r.text);
      let parsed;
      try { parsed = JSON.parse(r.text); } catch (e) { break; }
      const result = parsed && parsed.result;
      if (!result || !result.hasNextPage) break;
      lastInfo = result.lastInfo;
      seed = result.seed || '';
      await page.waitForTimeout(400 + Math.floor(Math.random() * 500));
    }
  }
  return { id: req.id, complexNo: req.complexNo, pages };
}

const rl = readline.createInterface({ input: process.stdin });
let queue = Promise.resolve();

rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let req;
  try { req = JSON.parse(trimmed); } catch (e) { return; }
  // 요청을 순차 처리(브라우저 페이지는 단일)
  queue = queue.then(async () => {
    try {
      send(await handle(req));
    } catch (e) {
      send({ id: req.id, complexNo: req.complexNo, error: String(e && e.message || e) });
    }
  });
});

rl.on('close', async () => {
  try { await queue; } catch (e) {}          // 대기 중인 요청을 모두 처리한 뒤 종료
  try { if (browser) await browser.close(); } catch (e) {}
  process.exit(0);
});

ensureBrowser()
  .then(() => send({ ready: true }))
  .catch((e) => { send({ ready: false, error: String(e && e.message || e) }); process.exit(1); });
