import { getAccessToken, setAccessToken } from "./tokenStore";
import type { ApiErrorBody, LoginResponse } from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const REFRESH_PATH = "/api/v1/auth/refresh";

export class ApiError extends Error {
  code: string;
  status: number;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.code = body.code;
    this.status = status;
  }
}

interface ApiFetchOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  entryToken?: string;
  /** /auth/signup, /auth/login처럼 아직 토큰이 없는 요청에 쓴다. */
  skipAuth?: boolean;
}

function buildHeaders(options: ApiFetchOptions): HeadersInit {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (!options.skipAuth) {
    const token = getAccessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  if (options.entryToken) headers["X-Entry-Token"] = options.entryToken;
  return headers;
}

function rawFetch(path: string, options: ApiFetchOptions): Promise<Response> {
  return fetch(`${BASE_URL}${path}`, {
    method: options.method ?? "GET",
    headers: buildHeaders(options),
    // Refresh Token이 httpOnly Cookie라 브라우저가 자동으로 실어 보내게 해야 한다(api-design.md 공통 규칙).
    credentials: "include",
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
}

/** Access Token 만료(INVALID_TOKEN)를 감지하면 한 번만 재발급을 시도하고 원요청을 재시도한다. */
async function tryRefresh(): Promise<boolean> {
  try {
    const response = await rawFetch(REFRESH_PATH, { method: "POST", skipAuth: true });
    if (!response.ok) {
      setAccessToken(null);
      return false;
    }
    const data = (await response.json()) as LoginResponse;
    setAccessToken(data.accessToken);
    return true;
  } catch {
    setAccessToken(null);
    return false;
  }
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  let response = await rawFetch(path, options);

  if (response.status === 401 && !options.skipAuth && path !== REFRESH_PATH) {
    const body = await response.clone().json().catch(() => null) as ApiErrorBody | null;
    if (body?.code === "INVALID_TOKEN" && (await tryRefresh())) {
      response = await rawFetch(path, options);
    }
  }

  if (response.status === 204) return undefined as T;

  const data = await response.json().catch(() => null);

  if (!response.ok) {
    throw new ApiError(response.status, data ?? { code: "UNKNOWN", message: "알 수 없는 오류가 발생했습니다." });
  }

  return data as T;
}
