import { apiFetch } from "./client";
import type { EventCreateRequest, EventDetail, EventSummary } from "./types";

export function listEvents(): Promise<EventSummary[]> {
  return apiFetch<EventSummary[]>("/api/v1/events");
}

export function getEvent(eventId: number): Promise<EventDetail> {
  return apiFetch<EventDetail>(`/api/v1/events/${eventId}`);
}

/** 주최자 전용 — ORGANIZER 토큰으로 공연 등록. */
export function createEvent(body: EventCreateRequest): Promise<EventDetail> {
  return apiFetch<EventDetail>("/api/v1/events", { method: "POST", body });
}
