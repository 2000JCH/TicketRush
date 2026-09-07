import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getEvent } from "../api/events";
import { formatApiError } from "../api/errorMessage";
import { getEntryToken } from "../lib/entryTokenStore";
import { useAuth } from "../context/AuthContext";
import type { EventDetail } from "../api/types";

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const numericEventId = Number(eventId);
  const navigate = useNavigate();
  const { isLoggedIn, isReady } = useAuth();

  const [event, setEvent] = useState<EventDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showLoginPrompt, setShowLoginPrompt] = useState(false);

  useEffect(() => {
    getEvent(numericEventId)
      .then(setEvent)
      .catch((err) => setError(formatApiError(err)));
  }, [numericEventId]);

  const alreadyPassedQueue = Boolean(getEntryToken(numericEventId));
  const openDate = event ? new Date(event.openAt) : null;
  const isOpen = openDate ? openDate.getTime() <= Date.now() : false;

  function startBooking() {
    if (!isLoggedIn) {
      setShowLoginPrompt(true);
      return;
    }
    navigate(
      alreadyPassedQueue
        ? `/events/${numericEventId}/seats`
        : `/events/${numericEventId}/queue`
    );
  }

  return (
    <div className="page">
      {error && <p className="error">{error}</p>}
      {event && openDate && (
        <>
          <div className="event-hero">
            <div className={`poster poster-p${event.id % 5}`}>
              {event.name.trim().charAt(0)}
            </div>
            <div className="event-hero-body">
              <span
                className={`badge ${isOpen ? "badge-open" : "badge-soon"}`}
              >
                {isOpen ? "예매 중" : "오픈 예정"}
              </span>
              <h1>{event.name}</h1>
              <p className="muted">
                {isOpen ? "예매 오픈: " : "예매 오픈 예정: "}
                {openDate.toLocaleString()}
              </p>
            </div>
          </div>

          <h2 className="section-heading">좌석 등급 · 가격</h2>
          <table className="section-table">
            <thead>
              <tr>
                <th>구역</th>
                <th>유형</th>
                <th>가격</th>
                <th>잔여</th>
              </tr>
            </thead>
            <tbody>
              {event.sections.map((section) => (
                <tr key={section.id}>
                  <td>{section.name}</td>
                  <td>{section.type === "SEATED" ? "지정석" : "스탠딩"}</td>
                  <td>{section.price.toLocaleString()}원</td>
                  <td>
                    {section.type === "STANDING"
                      ? `${section.remainingQuantity.toLocaleString()}석`
                      : `${(section.rowCount * section.seatsPerRow).toLocaleString()}석 (좌석별 조회)`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <button onClick={startBooking} disabled={!isReady}>
            {alreadyPassedQueue ? "좌석 선택하러 가기" : "예매하기"}
          </button>
          {alreadyPassedQueue && (
            <p className="muted">이미 대기열을 통과했습니다.</p>
          )}
        </>
      )}
      <p style={{ marginTop: "2rem" }}>
        <Link to="/">← 공연 목록으로</Link>
      </p>

      {showLoginPrompt && (
        <div
          className="modal-backdrop"
          onClick={() => setShowLoginPrompt(false)}
        >
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>로그인이 필요해요</h2>
            <p>
              좌석 선택과 예매는 로그인한 회원만 이용할 수 있습니다. 공연 정보는
              로그인 없이 계속 둘러보실 수 있어요.
            </p>
            <div className="modal-actions">
              <button
                onClick={() =>
                  navigate("/login", {
                    state: { from: `/events/${numericEventId}` },
                  })
                }
              >
                로그인하러 가기
              </button>
              <button
                className="secondary"
                onClick={() => setShowLoginPrompt(false)}
              >
                계속 둘러보기
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
