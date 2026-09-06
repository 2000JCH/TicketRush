import { apiFetch } from "./client";
import type { AccountInfo } from "./types";

export function getMyAccount(): Promise<AccountInfo> {
  return apiFetch<AccountInfo>("/api/v1/accounts/me");
}
