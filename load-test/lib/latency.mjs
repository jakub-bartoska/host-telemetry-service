// Charts API client-side latency: sequential requests, prints p50/p95/p99 in ms.
// Usage: node latency.mjs <range: hour|day|week> <count> <deviceId|spread> [metric] [baseUrl]
// deviceId "spread" cycles through host-0001..host-<count> (one request per distinct device).
const [range, count, device, metric = "cpuUtilPct", base = "http://localhost:" + (process.env.APP_PORT ?? "18080")] = process.argv.slice(2);
const urlFor = (i) => `${base}/api/v1/devices/${device === "spread" ? "host-" + String(i + 1).padStart(4, "0") : device}/metrics/${metric}/series?range=${range}`;
const times = [];
let nonNull = 0;
let failures = 0;
for (let i = 0; i < Number(count); i++) {
  const t0 = performance.now();
  try {
    const res = await fetch(urlFor(i));
    const body = await res.json();
    times.push(performance.now() - t0);
    if (!res.ok) failures++;
    else nonNull = body.points.filter((p) => p !== null && p.avg !== null && p.avg !== undefined).length;
  } catch {
    failures++;
  }
}
times.sort((a, b) => a - b);
const q = (p) => times[Math.min(times.length - 1, Math.ceil(p * times.length) - 1)].toFixed(1);
console.log(JSON.stringify({ range, device, n: times.length, failures, nonNullPoints: nonNull,
  p50: q(0.5), p95: q(0.95), p99: q(0.99), max: times[times.length - 1].toFixed(1) }));
