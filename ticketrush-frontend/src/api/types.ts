// api-design.md 스키마와 1:1로 맞춘 타입. 백엔드 응답 필드명을 그대로 따른다.

export type Role = "BUYER" | "ORGANIZER" | "ADMIN";
export type AccountStatus = "PENDING" | "ACTIVE";

export interface SignupResponse {
  accountId: number;
  role: Role;
  status: AccountStatus;
}

export interface LoginResponse {
  accessToken: string;
}

export type SectionType = "SEATED" | "STANDING";

export interface EventSummary {
  id: number;
  name: string;
  openAt: string;
}

export interface SeatedSection {
  id: number;
  name: string;
  type: "SEATED";
  price: number;
  rowCount: number;
  seatsPerRow: number;
}

export interface StandingSection {
  id: number;
  name: string;
  type: "STANDING";
  price: number;
  remainingQuantity: number;
}

export type EventSection = SeatedSection | StandingSection;

export interface EventDetail {
  id: number;
  name: string;
  openAt: string;
  sections: EventSection[];
}

export interface QueueStatusResponse {
  rank: number;
  entryToken: string | null;
}

export type SeatStatus = "AVAILABLE" | "HELD";

export interface SeatStatusItem {
  seatId: number;
  rowNo: number;
  seatNo: number;
  status: SeatStatus;
}

export interface SeatHoldRequestSeated {
  sectionId: number;
  seatIds: number[];
}

export interface SeatHoldRequestStanding {
  sectionId: number;
  quantity: number;
}

export interface SeatHoldResponse {
  status: "SEAT_HELD";
  holdExpiresAt: string;
}

export interface ReservationRequestSeated {
  eventId: number;
  sectionId: number;
  seatIds: number[];
  idempotencyKey: string;
}

export interface ReservationRequestStanding {
  eventId: number;
  sectionId: number;
  quantity: number;
  idempotencyKey: string;
}

export interface ReservationResponse {
  reservationId: number;
  status: "PAYMENT_REQUESTED";
  pgPaymentId: string;
  amount: number;
  orderName: string;
}

/** 프론트가 PortOne 브라우저 SDK를 호출할 때 필요한 공개 식별자 (GET /api/v1/payments/config). */
export interface PaymentConfig {
  storeId: string;
  cardChannelKey: string;
  easyPayChannelKey: string;
}

export type ReservationStatus =
  | "PAYMENT_REQUESTED"
  | "PAYMENT_CONFIRMED"
  | "PAYMENT_FAILED"
  | "SEAT_RELEASED";

export interface ReservationSeatInfo {
  sectionName: string;
  rowNo: number;
  seatNo: number;
}

export interface ReservationDetail {
  reservationId: number;
  eventId: number;
  eventName: string;
  status: ReservationStatus;
  quantity: number;
  amount: number;
  seats: ReservationSeatInfo[];
  requestedAt: string;
  confirmedAt: string | null;
}

export type AccountRole = "BUYER" | "ORGANIZER" | "ADMIN";

export interface AccountInfo {
  accountId: number;
  email: string;
  role: AccountRole;
  status: "PENDING" | "ACTIVE";
  createdAt: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
}
