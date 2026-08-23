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
      <h1>이벤트 목록</h1>
      {error && <p className="error">{error}</p>}
      {events === null && !error && <p>불러오는 중...</p>}
      {events?.length === 0 && <p>등록된 이벤트가 없습니다.</p>}
      <ul className="event-list">
        {events?.map((event) => (
          <li key={event.id}>
            <Link to={`/events/${event.id}`}>
              <strong>{event.name}</strong>
              <span className="muted"> — 오픈 {new Date(event.openAt).toLocaleString()}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
