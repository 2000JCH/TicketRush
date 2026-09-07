import { Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { LoginPage } from "./pages/LoginPage";
import { SignupPage } from "./pages/SignupPage";
import { EventListPage } from "./pages/EventListPage";
import { EventDetailPage } from "./pages/EventDetailPage";
import { QueuePage } from "./pages/QueuePage";
import { SeatHoldPage } from "./pages/SeatHoldPage";
import { ReservationsPage } from "./pages/ReservationsPage";
import { AccountPage } from "./pages/AccountPage";
import { AdminApprovalsPage } from "./pages/AdminApprovalsPage";
import { AdminMembersPage } from "./pages/AdminMembersPage";
import { AdminEventStatsPage } from "./pages/AdminEventStatsPage";
import { OrganizerEventCreatePage } from "./pages/OrganizerEventCreatePage";

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        {/* 공연 목록·상세는 로그인 없이 볼 수 있다(실제 예매 사이트처럼). 예매 진입 시점에 로그인을 요구한다. */}
        <Route path="/" element={<EventListPage />} />
        <Route path="/events/:eventId" element={<EventDetailPage />} />
        <Route
          path="/events/:eventId/queue"
          element={
            <ProtectedRoute>
              <QueuePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/events/:eventId/seats"
          element={
            <ProtectedRoute>
              <SeatHoldPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/reservations"
          element={
            <ProtectedRoute>
              <ReservationsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/account"
          element={
            <ProtectedRoute>
              <AccountPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/organizer/events/new"
          element={
            <ProtectedRoute organizerOnly>
              <OrganizerEventCreatePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/approvals"
          element={
            <ProtectedRoute adminOnly>
              <AdminApprovalsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/members"
          element={
            <ProtectedRoute adminOnly>
              <AdminMembersPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/events"
          element={
            <ProtectedRoute adminOnly>
              <AdminEventStatsPage />
            </ProtectedRoute>
          }
        />
      </Route>
    </Routes>
  );
}

export default App;
