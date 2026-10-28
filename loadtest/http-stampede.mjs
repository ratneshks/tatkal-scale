#!/usr/bin/env node
/**
 * HTTP Stampede: the same experiment as /api/v1/stampede, but from OUTSIDE the app, over real HTTP.
 * This goes through Tomcat's thread pool (200 by default), JSON parsing, and the full Spring MVC
 * stack, so it measures what a real client sees, not just database contention.
 *
 * No dependencies: needs Node 18+ (built-in fetch).
 *
 *   node loadtest/http-stampede.mjs
 *   node loadtest/http-stampede.mjs --base http://localhost:8080 --threads 500 --seats 5 --mode any
 *   node loadtest/http-stampede.mjs --strategies optimistic,pessimistic --mode same
 *
 * How oversell is detected from outside: every 201 response names the seat it got. If two
 * successful responses name the same seat, a ticket was sold twice.
 *
 * Note: unlike the in-app simulator, the database safety index stays ON here (normal app config),
 * so NAIVE's duplicates are usually caught by the database and show up as DB_GUARD_REJECTED.
 */

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : 'true']);
  return acc;
}, []));

const BASE = (args.base || 'http://localhost:8080').replace(/\/$/, '') + '/api/v1';
const THREADS = parseInt(args.threads || '500', 10);
const SEATS = parseInt(args.seats || '5', 10);
const MODE = (args.mode || 'any').toLowerCase();             // any | same
const STRATEGIES = (args.strategies || 'naive,optimistic,pessimistic,skip-locked').split(',');
const ARENA = 2;                                             // the stampede show

async function call(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

function pct(sorted, p) {
  if (!sorted.length) return 0;
  return sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)];
}

async function runOne(strategy) {
  const reset = await call('POST', `/admin/shows/${ARENA}/reset?seats=${SEATS}`);
  if (reset.status !== 200) throw new Error(`Reset failed: HTTP ${reset.status}`);

  let seatId = null;
  if (MODE === 'same') {
    const map = await call('GET', `/shows/${ARENA}/seats`);
    seatId = map.body.seats[0].id;
  }

  // Build every request first, then release them together (the HTTP version of a start gate).
  const latencies = new Array(THREADS);
  let release;
  const gate = new Promise(r => (release = r));
  const requests = Array.from({ length: THREADS }, async (_, i) => {
    await gate;
    const t0 = performance.now();
    try {
      const r = await call('POST', `/${strategy}/shows/${ARENA}/holds`, { userId: `http-user-${i}`, seatId });
      return r;
    } catch (e) {
      return { status: 0, body: { code: 'NETWORK_ERROR', detail: e.message } };
    } finally {
      latencies[i] = performance.now() - t0;
    }
  });

  const wallStart = performance.now();
  release();
  const results = await Promise.all(requests);
  const wall = performance.now() - wallStart;

  const codes = {};
  const wonSeats = [];
  for (const r of results) {
    const key = r.status === 201 ? 'SUCCESS' : `${r.status} ${r.body?.code ?? ''}`.trim();
    codes[key] = (codes[key] || 0) + 1;
    if (r.status === 201) wonSeats.push(r.body.seatId);
  }
  const successes = wonSeats.length;
  const distinct = new Set(wonSeats).size;
  const phantom = successes - distinct;               // tickets sold for a seat someone else already got

  // Cross-check with the server's own seat map.
  const map = await call('GET', `/shows/${ARENA}/seats`);
  const heldOnServer = map.body.seats.filter(s => s.status !== 'AVAILABLE').length;

  const sorted = latencies.slice().sort((a, b) => a - b);
  return {
    strategy,
    successes,
    phantom,
    heldOnServer,
    capacity: MODE === 'same' ? 1 : SEATS,
    codes,
    p50: pct(sorted, 50), p95: pct(sorted, 95), p99: pct(sorted, 99),
    wall,
  };
}

const f = n => n.toFixed(1).padStart(8);

console.log(`HTTP stampede -> ${BASE}`);
console.log(`${THREADS} concurrent requests, ${SEATS} seat(s), mode=${MODE}\n`);

try {
  await call('GET', '/shows');
} catch {
  console.error(`Cannot reach ${BASE}. Start the app first:  mvn spring-boot:run`);
  process.exit(1);
}

const rows = [];
for (const s of STRATEGIES) {
  const r = await runOne(s.trim());
  rows.push(r);
  const ok = r.phantom === 0 && r.successes <= r.capacity;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${r.strategy.padEnd(12)} sold ${r.successes}/${r.capacity}` +
    `  phantom tickets: ${r.phantom}  server shows ${r.heldOnServer} taken`);
  console.log(`      responses: ${Object.entries(r.codes).map(([k, v]) => `${k}=${v}`).join(', ')}`);
  console.log(`      latency ms  p50${f(r.p50)}  p95${f(r.p95)}  p99${f(r.p99)}   total${f(r.wall)}\n`);
}

// Exit code 1 if a SAFE strategy oversold (NAIVE is allowed to fail: that's its job).
const safeFailed = rows.some(r => r.strategy !== 'naive' && (r.phantom > 0 || r.successes > r.capacity));
process.exit(safeFailed ? 1 : 0);
