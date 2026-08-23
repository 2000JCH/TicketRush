import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import * as authApi from "../api/auth";

interface AuthContextValue {
  /** 세션 복구(앱 기동 시 /auth/refresh) 시도가 끝났는지. 끝나기 전엔 로그인 여부를 알 수 없다. */
  isReady: boolean;
  isLoggedIn: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isReady, setIsReady] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => {
    authApi.tryRestoreSession().then((restored) => {
      setIsLoggedIn(restored);
      setIsReady(true);
    });
  }, []);

  async function login(email: string, password: string) {
    await authApi.login(email, password);
    setIsLoggedIn(true);
  }

  async function logout() {
    await authApi.logout();
    setIsLoggedIn(false);
  }

  return (
    <AuthContext.Provider value={{ isReady, isLoggedIn, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth는 AuthProvider 안에서만 쓸 수 있습니다.");
  return context;
}
