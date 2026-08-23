import { apiFetch } from "./client";
import type { QueueStatusResponse } from "./types";

export function enterQueue(eventId: number): Promise<QueueStatusResponse> {
  return apiFetch<QueueStatusResponse>(`/api/v1/events/${eventId}/queue/entries`, {
    method: "POST",
  });
}

export function getQueueStatus(eventId: number): Promise<QueueStatusResponse> {
  return apiFetch<QueueStatusResponse>(`/api/v1/events/${eventId}/queue/entries/me`);
}
