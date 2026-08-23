// 입장 토큰은 이벤트별로 sessionStorage에 둔다 — 탭을 닫으면 사라지는 게 자연스럽고
// (대기열을 다시 서야 하는 게 정상 동작), 새로고침/다른 페이지 이동 중에는 유지돼야 하기 때문이다.
function key(eventId: number): string {
  return `entryToken:${eventId}`;
}

export function getEntryToken(eventId: number): string | null {
  return sessionStorage.getItem(key(eventId));
}

export function setEntryToken(eventId: number, token: string): void {
  sessionStorage.setItem(key(eventId), token);
}

export function clearEntryToken(eventId: number): void {
  sessionStorage.removeItem(key(eventId));
}
