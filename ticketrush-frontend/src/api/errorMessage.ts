import { ApiError } from "./client";

/** api-design.md 에러 코드 표 기준. 백엔드 message를 그대로 써도 되지만, 화면 문구는
 * 프론트가 정한다고 문서에 명시돼 있어(1번 회원가입 섹션) 몇 개는 더 친절하게 다듬었다. */
const MESSAGES: Record<string, string> = {
  ACCOUNT_PENDING: "관리자 승인 대기 중인 계정입니다. 승인 후 로그인해주세요.",
  ENTRY_TOKEN_EXPIRED: "입장 토큰이 만료되었습니다. 대기열에 다시 입장해주세요.",
  ENTRY_TOKEN_REQUIRED: "대기열을 통과해야 이용할 수 있습니다.",
  SEAT_ALREADY_HELD: "다른 사용자가 이미 선택한 좌석입니다.",
  STANDING_SOLD_OUT: "매진되었습니다.",
  SERVICE_TEMPORARILY_UNAVAILABLE: "일시적으로 이용이 어렵습니다. 잠시 후 다시 시도해주세요.",
  ACTIVE_RESERVATION_EXISTS: "이미 진행 중인 예매가 있습니다.",
  QUANTITY_LIMIT_EXCEEDED: "1인당 최대 2매까지 구매할 수 있습니다.",
  EMAIL_ALREADY_EXISTS: "이미 가입된 이메일입니다.",
};

export function formatApiError(error: unknown): string {
  if (error instanceof ApiError) {
    return MESSAGES[error.code] ?? error.message;
  }
  return "알 수 없는 오류가 발생했습니다.";
}
