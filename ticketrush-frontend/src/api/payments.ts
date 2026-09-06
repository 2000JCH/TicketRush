import { apiFetch } from "./client";
import type { PaymentConfig } from "./types";

export function getPaymentConfig(): Promise<PaymentConfig> {
  return apiFetch<PaymentConfig>("/api/v1/payments/config");
}
