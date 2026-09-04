// 병렬 BUYER 시더 — seed-load-test.ps1의 순차 signup+login이 이 PC 리허설 스택(2~4 vCPU)에서
// 너무 느려서(~100/분) 만든 대체 스크립트. 이벤트/좌석은 seed-load-test.ps1이 이미 만들었다고 가정하고
// BUYER 계정 풀만 만든다. 동시 요청 수를 제한해 batch로 signup+login 한다.
//
// 사용: node scripts/seed-buyers-parallel.mjs <count> [existingPrefix]
//   <count>          : 만들 BUYER 총 수
//   [existingPrefix]  : 이미 만들어진 계정 email 접두사(있으면 그건 login만 해서 재사용)
//
// 출력: ticketrush-backend/src/gatling/resources/data/buyers.csv (email,password,accessToken)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const BASE = process.env.BASE_URL || "http://localhost:8080";
const PASSWORD = "loadtest1234";
const CONCURRENCY = Number(process.env.CONCURRENCY || 40);
const total = Number(process.argv[2] || 3000);
const existingPrefix = process.argv[3] || null;
const existingCount = Number(process.argv[4] || 0);

const __dirname = dirname(fileURLToPath(import.meta.url));
const csvPath = join(__dirname, "..", "ticketrush-backend", "src", "gatling", "resources", "data", "buyers.csv");

const ts = Math.floor(Date.now() / 1000);
const newPrefix = `load-buyer-${ts}`;

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}

async function makeBuyer(email, needSignup) {
  if (needSignup) {
    await post("/api/v1/auth/signup", { email, password: PASSWORD, role: "BUYER" });
  }
  const { accessToken } = await post("/api/v1/auth/login", { email, password: PASSWORD });
  return { email, accessToken };
}

// 작업 목록: 앞쪽 existingCount개는 기존 계정 재사용(login만), 나머지는 신규(signup+login)
const jobs = [];
for (let i = 0; i < total; i++) {
  if (existingPrefix && i < existingCount) {
    jobs.push({ email: `${existingPrefix}-${i}@ticketrush.test`, needSignup: false });
  } else {
    jobs.push({ email: `${newPrefix}-${i}@ticketrush.test`, needSignup: true });
  }
}

const rows = [];
let done = 0;
let failed = 0;
const t0 = Date.now();

async function worker(queue) {
  while (queue.length) {
    const job = queue.pop();
    try {
      const r = await makeBuyer(job.email, job.needSignup);
      rows.push(`${r.email},${PASSWORD},${r.accessToken}`);
    } catch (e) {
      failed++;
      if (failed <= 5) console.error("fail:", e.message.slice(0, 120));
    }
    if (++done % 200 === 0) {
      const rate = (done / ((Date.now() - t0) / 1000)).toFixed(0);
      console.log(`  ${done}/${total}  (${rate}/s, fail ${failed})`);
    }
  }
}

const queue = jobs.slice();
await Promise.all(Array.from({ length: CONCURRENCY }, () => worker(queue)));

mkdirSync(dirname(csvPath), { recursive: true });
writeFileSync(csvPath, "email,password,accessToken\n" + rows.join("\n") + "\n");
console.log(`\ndone: ${rows.length} buyers written to buyers.csv  (${failed} failed, ${((Date.now() - t0) / 1000).toFixed(0)}s)`);
