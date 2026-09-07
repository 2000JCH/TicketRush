import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function ProtectedRoute({
  children,
  adminOnly = false,
  organizerOnly = false,
}: {
  children: ReactNode;
  adminOnly?: boolean;
  organizerOnly?: boolean;
}) {
  const { isReady, isLoggedIn, role } = useAuth();

  if (!isReady) return <div className="page">로딩 중...</div>;
  if (!isLoggedIn) return <Navigate to="/login" replace />;
  if (adminOnly && role !== "ADMIN") return <Navigate to="/" replace />;
  if (organizerOnly && role !== "ORGANIZER") return <Navigate to="/" replace />;
  return <>{children}</>;
}
