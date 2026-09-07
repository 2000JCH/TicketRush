import { Link, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function Layout() {
  const { isReady, isLoggedIn, role, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate("/login");
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <Link to="/" className="brand">
          TicketRush
        </Link>
        {isReady && (
          <nav>
            {isLoggedIn ? (
              <>
                {role === "ADMIN" ? (
                  <>
                    <Link to="/admin/approvals">주최자 승인</Link>
                    <Link to="/admin/members">회원 관리</Link>
                    <Link to="/admin/events">콘서트 현황</Link>
                  </>
                ) : (
                  <Link to="/reservations">내 예약</Link>
                )}
                <Link to="/account">내 정보</Link>
                <button onClick={handleLogout} className="link-button">
                  로그아웃
                </button>
              </>
            ) : (
              <>
                <Link to="/login">로그인</Link>
                <Link to="/signup">회원가입</Link>
              </>
            )}
          </nav>
        )}
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
