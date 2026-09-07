import { apiFetch } from "./client";
import type {
  AdminAccount,
  AdminEventStats,
  PagedResponse,
  ReservationDetail,
  Role,
  AccountStatus,
} from "./types";

export function getPendingOrganizers(): Promise<AdminAccount[]> {
  return apiFetch<AdminAccount[]>("/api/v1/admin/accounts/pending");
}

export function approveOrganizer(accountId: number): Promise<AdminAccount> {
  return apiFetch<AdminAccount>(`/api/v1/admin/accounts/${accountId}/approve`, { method: "PATCH" });
}

export function rejectOrganizer(accountId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/accounts/${accountId}`, { method: "DELETE" });
}

interface MemberQuery {
  role?: Role;
  status?: AccountStatus;
  email?: string;
  page?: number;
  size?: number;
}

export function getMembers(q: MemberQuery = {}): Promise<PagedResponse<AdminAccount>> {
  const params = new URLSearchParams();
  if (q.role) params.set("role", q.role);
  if (q.status) params.set("status", q.status);
  if (q.email) params.set("email", q.email);
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return apiFetch<PagedResponse<AdminAccount>>(`/api/v1/admin/accounts?${params.toString()}`);
}

export function getMemberReservations(accountId: number): Promise<ReservationDetail[]> {
  return apiFetch<ReservationDetail[]>(`/api/v1/admin/accounts/${accountId}/reservations`);
}

export function suspendAccount(accountId: number): Promise<AdminAccount> {
  return apiFetch<AdminAccount>(`/api/v1/admin/accounts/${accountId}/suspend`, { method: "PATCH" });
}

export function reactivateAccount(accountId: number): Promise<AdminAccount> {
  return apiFetch<AdminAccount>(`/api/v1/admin/accounts/${accountId}/reactivate`, { method: "PATCH" });
}

export function getEventStats(): Promise<AdminEventStats[]> {
  return apiFetch<AdminEventStats[]>("/api/v1/admin/events/stats");
}
