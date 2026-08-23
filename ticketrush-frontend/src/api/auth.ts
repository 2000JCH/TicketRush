import { apiFetch } from "./client";
import { setAccessToken } from "./tokenStore";
import type { LoginResponse, Role, SignupResponse } from "./types";

export function signup(email: string, password: string, role: Role): Promise<SignupResponse> {
  return apiFetch<SignupResponse>("/api/v1/auth/signup", {
    method: "POST",
    body: { email, password, role },
    skipAuth: true,
  });
}

export async function login(email: string, password: string): Promise<void> {
  const { accessToken } = await apiFetch<LoginResponse>("/api/v1/auth/login", {
    method: "POST",
    body: { email, password },
    skipAuth: true,
  });
  setAccessToken(accessToken);
}

/** 새로고침 직후처럼 Access Token이 없을 때, httpOnly Refresh Token 쿠키로 조용히 재로그인 상태를 복구한다. */
export async function tryRestoreSession(): Promise<boolean> {
  try {
    const { accessToken } = await apiFetch<LoginResponse>("/api/v1/auth/refresh", {
      method: "POST",
      skipAuth: true,
    });
    setAccessToken(accessToken);
    return true;
  } catch {
    setAccessToken(null);
    return false;
  }
}

export async function logout(): Promise<void> {
  try {
    await apiFetch<void>("/api/v1/auth/logout", { method: "POST" });
  } finally {
    setAccessToken(null);
  }
}
