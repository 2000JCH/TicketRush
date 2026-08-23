import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isReady, isLoggedIn } = useAuth();

  if (!isReady) return <div className="page">로딩 중...</div>;
  if (!isLoggedIn) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
