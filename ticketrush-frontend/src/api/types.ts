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
}

export type ReservationStatus =
  | "PAYMENT_REQUESTED"
  | "PAYMENT_CONFIRMED"
  | "PAYMENT_FAILED"
  | "SEAT_RELEASED";

export interface ReservationDetail {
  reservationId: number;
  eventId: number;
  status: ReservationStatus;
  quantity: number;
  amount: number;
  requestedAt: string;
  confirmedAt: string | null;
}

export interface ApiErrorBody {
  code: string;
  message: string;
}
