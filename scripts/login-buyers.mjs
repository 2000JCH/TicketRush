// buyer-emails.txt에서 앞 N개 계정을 병렬로 로그인해 buyers.csv(email,password,accessToken)를 새로 쓴다.
// signup 없음 — 계정은 이미 있다고 가정. 한계 테스트 재시드용(토큰만 갱신).
// 사용: node scripts/login-buyers.mjs <count>
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const BASE = process.env.BASE_URL || "http://localhost:8080";
const PASSWORD = "loadtest1234";
const CONCURRENCY = Number(process.env.CONCURRENCY || 60);
const count = Number(process.argv[2] || 3000);

const __dirname = dirname(fileURLToPath(import.meta.url));
const dataDir = join(__dirname, "..", "ticketrush-backend", "src", "gatling", "resources", "data");
const emails = readFileSync(join(dataDir, "buyer-emails.txt"), "utf8").split(/\r?\n/).filter(Boolean).slice(0, count);

async function login(email) {
  const res = await fetch(BASE + "/api/v1/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password: PASSWORD }),
  });
  if (!res.ok) throw new Error(`${res.status}`);
  return (await res.json()).accessToken;
}

const rows = [];
let done = 0, failed = 0;
const t0 = Date.now();
const queue = emails.slice();
async function worker() {
  while (queue.length) {
    const email = queue.pop();
    try { rows.push(`${email},${PASSWORD},${await login(email)}`); }
    catch (e) { failed++; }
    if (++done % 500 === 0) console.log(`  ${done}/${emails.length} (${(done/((Date.now()-t0)/1000)).toFixed(0)}/s, fail ${failed})`);
  }
}
await Promise.all(Array.from({ length: CONCURRENCY }, worker));
writeFileSync(join(dataDir, "buyers.csv"), "email,password,accessToken\n" + rows.join("\n") + "\n");
console.log(`\ndone: ${rows.length} tokens -> buyers.csv (${failed} failed, ${((Date.now()-t0)/1000).toFixed(0)}s)`);
