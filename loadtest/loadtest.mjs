// Load test for the ScoutAgent REST API. Measures throughput and latency percentiles over a realistic endpoint mix.
// Usage: node loadtest/loadtest.mjs [--url http://localhost:8080] [--concurrency 64] [--seconds 20]
import { Agent, setGlobalDispatcher } from 'undici';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? [...acc, [v.slice(2), a[i + 1]]] : acc), []),
);
const BASE = args.url ?? 'http://localhost:8080';
const CONCURRENCY = Number(args.concurrency ?? 64);
const SECONDS = Number(args.seconds ?? 20);
const WARMUP_SECONDS = Number(args.warmup ?? 5);

setGlobalDispatcher(new Agent({ connections: CONCURRENCY, pipelining: 1, keepAliveTimeout: 30_000 }));

const meta = await (await fetch(`${BASE}/api/meta`)).json();
const teams = meta.teams.map((t) => t.abbr);
const pick = () => teams[Math.floor(Math.random() * teams.length)];
const pair = () => {
  const a = pick();
  let b = pick();
  while (b === a) b = pick();
  return [a, b];
};

// Weighted mix of read endpoints (the agent pipeline itself is bounded by LLM latency, not the API).
const mix = [
  [30, () => `/api/teams/${pick()}/summary`],
  [20, () => `/api/teams/${pick()}/games?limit=10`],
  [20, () => { const [h, a] = pair(); return `/api/predict?home=${h}&away=${a}`; }],
  [10, () => `/api/teams/${pick()}/splits`],
  [10, () => { const [h, a] = pair(); return `/api/head-to-head?team=${h}&opponent=${a}`; }],
  [5, () => `/api/teams/${pick()}/trend`],
  [5, () => `/api/standings`],
];
const totalWeight = mix.reduce((s, [w]) => s + w, 0);
const nextPath = () => {
  let r = Math.random() * totalWeight;
  for (const [w, f] of mix) if ((r -= w) < 0) return f();
  return mix[0][1]();
};

async function run(seconds, record) {
  const latencies = [];
  let ok = 0, errors = 0;
  const end = performance.now() + seconds * 1000;
  const worker = async () => {
    while (performance.now() < end) {
      const t0 = performance.now();
      try {
        const res = await fetch(BASE + nextPath());
        await res.arrayBuffer();
        res.ok ? ok++ : errors++;
      } catch {
        errors++;
      }
      if (record) latencies.push(performance.now() - t0);
    }
  };
  const start = performance.now();
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  return { ok, errors, latencies, elapsed: (performance.now() - start) / 1000 };
}

console.log(`Warming up for ${WARMUP_SECONDS}s...`);
await run(WARMUP_SECONDS, false);
console.log(`Measuring for ${SECONDS}s at concurrency ${CONCURRENCY} against ${BASE}`);
const { ok, errors, latencies, elapsed } = await run(SECONDS, true);

latencies.sort((a, b) => a - b);
const pct = (p) => latencies[Math.min(latencies.length - 1, Math.floor((p / 100) * latencies.length))].toFixed(1);
console.log(JSON.stringify({
  requests: ok + errors,
  errors,
  requests_per_sec: Math.round((ok + errors) / elapsed),
  latency_ms: { p50: pct(50), p95: pct(95), p99: pct(99), max: latencies.at(-1).toFixed(1) },
}, null, 2));
