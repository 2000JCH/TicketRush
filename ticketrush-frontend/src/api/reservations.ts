import { apiFetch } from "./client";
import type {
  ReservationDetail,
  ReservationRequestSeated,
  ReservationRequestStanding,
  ReservationResponse,
} from "./types";

export function requestPayment(
  body: ReservationRequestSeated | ReservationRequestStanding,
  entryToken: string
): Promise<ReservationResponse> {
  return apiFetch<ReservationResponse>("/api/v1/reservations", {
    method: "POST",
    body,
    entryToken,
  });
}

export function getMyReservations(): Promise<ReservationDetail[]> {
  return apiFetch<ReservationDetail[]>("/api/v1/reservations/me");
}

export function getReservation(reservationId: number): Promise<ReservationDetail> {
  return apiFetch<ReservationDetail>(`/api/v1/reservations/${reservationId}`);
}

export function cancelReservation(reservationId: number): Promise<ReservationDetail> {
  return apiFetch<ReservationDetail>(`/api/v1/reservations/${reservationId}/cancel`, {
    method: "POST",
  });
}
