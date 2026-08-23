import { Link, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function Layout() {
  const { isReady, isLoggedIn, logout } = useAuth();
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
              <button onClick={handleLogout} className="link-button">
                로그아웃
              </button>
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
