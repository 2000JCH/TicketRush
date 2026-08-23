// Access Token은 메모리에만 둔다(localStorage에 두지 않는 것: XSS로 탈취되면 만료 전까지
// 계속 악용될 수 있기 때문). 새로고침하면 사라지므로 AuthContext가 기동 시 /auth/refresh로
// 다시 받아온다. api/client.ts처럼 React 밖(훅이 아닌 일반 함수)에서도 읽을 수 있어야 해서
// React state가 아니라 모듈 전역 변수로 둔다.
let currentAccessToken: string | null = null;

export function getAccessToken(): string | null {
  return currentAccessToken;
}

export function setAccessToken(token: string | null): void {
  currentAccessToken = token;
}
