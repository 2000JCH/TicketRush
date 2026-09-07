/**
 * 멱등 키용 랜덤 ID. `crypto.randomUUID()`는 보안 컨텍스트(HTTPS·localhost)에서만 보장돼,
 * 평문 http로 접속한 AWS 데모 환경에서는 브라우저에 따라 없을 수 있다 — 그 경우 폴백을 쓴다.
 */
export function randomId(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  if (c && typeof c.getRandomValues === "function") {
    const b = c.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = [...b].map((x) => x.toString(16).padStart(2, "0"));
    return `${h.slice(0, 4).join("")}-${h.slice(4, 6).join("")}-${h
      .slice(6, 8)
      .join("")}-${h.slice(8, 10).join("")}-${h.slice(10, 16).join("")}`;
  }
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;
}
