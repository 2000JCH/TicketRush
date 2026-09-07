import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listEvents } from "../api/events";
import { formatApiError } from "../api/errorMessage";
import type { EventSummary } from "../api/types";

export function EventListPage() {
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listEvents()
      .then(setEvents)
      .catch((err) => setError(formatApiError(err)));
  }, []);

  return (
    <div className="page">
      <h1>예매 중인 공연</h1>
      {error && <p className="error">{error}</p>}
      {events === null && !error && <p>불러오는 중...</p>}
      {events?.length === 0 && <p>등록된 공연이 없습니다.</p>}
      <ul className="event-list">
        {events?.map((event) => {
          const openDate = new Date(event.openAt);
          const isOpen = openDate.getTime() <= Date.now();
          return (
            <li key={event.id}>
              <Link to={`/events/${event.id}`} className="event-card">
                <div className={`poster poster-p${event.id % 5}`}>
                  {event.name.trim().charAt(0)}
                </div>
                <div className="event-card-body">
                  <span
                    className={`badge ${isOpen ? "badge-open" : "badge-soon"}`}
                  >
                    {isOpen ? "예매 중" : "오픈 예정"}
                  </span>
                  <strong>{event.name}</strong>
                  <span className="muted">
                    예매 오픈 {openDate.toLocaleString()}
                  </span>
                </div>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
