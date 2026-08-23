import { apiFetch } from "./client";
import type {
  SeatHoldRequestSeated,
  SeatHoldRequestStanding,
  SeatHoldResponse,
  SeatStatusItem,
} from "./types";

export function getSeats(
  eventId: number,
  sectionId: number,
  entryToken: string
): Promise<SeatStatusItem[]> {
  return apiFetch<SeatStatusItem[]>(
    `/api/v1/events/${eventId}/seats?sectionId=${sectionId}`,
    { entryToken }
  );
}

export function holdSeats(
  eventId: number,
  body: SeatHoldRequestSeated | SeatHoldRequestStanding,
  entryToken: string
): Promise<SeatHoldResponse> {
  return apiFetch<SeatHoldResponse>(`/api/v1/events/${eventId}/seats/holds`, {
    method: "POST",
    body,
    entryToken,
  });
}

export function releaseHold(eventId: number, entryToken: string): Promise<void> {
  return apiFetch<void>(`/api/v1/events/${eventId}/seats/holds`, {
    method: "DELETE",
    entryToken,
  });
}
