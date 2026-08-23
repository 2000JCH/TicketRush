import { apiFetch } from "./client";
import type {
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
