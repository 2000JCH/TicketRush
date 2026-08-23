import { apiFetch } from "./client";
import type { EventDetail, EventSummary } from "./types";

export function listEvents(): Promise<EventSummary[]> {
  return apiFetch<EventSummary[]>("/api/v1/events");
}

export function getEvent(eventId: number): Promise<EventDetail> {
  return apiFetch<EventDetail>(`/api/v1/events/${eventId}`);
}
