import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getEvent } from "../api/events";
import { formatApiError } from "../api/errorMessage";
import { getEntryToken } from "../lib/entryTokenStore";
import type { EventDetail } from "../api/types";

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const numericEventId = Number(eventId);
  const navigate = useNavigate();

  const [event, setEvent] = useState<EventDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getEvent(numericEventId)
      .then(setEvent)
      .catch((err) => setError(formatApiError(err)));
  }, [numericEventId]);

  const alreadyPassedQueue = Boolean(getEntryToken(numericEventId));

  return (
    <div className="page">
      {error && <p className="error">{error}</p>}
      {event && (
        <>
          <h1>{event.name}</h1>
          <p className="muted">오픈: {new Date(event.openAt).toLocaleString()}</p>

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
                      : `${section.rowCount * section.seatsPerRow}석 (좌석별 조회)`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {alreadyPassedQueue ? (
            <button onClick={() => navigate(`/events/${numericEventId}/seats`)}>
              좌석 선택하러 가기 (이미 대기열 통과함)
            </button>
          ) : (
            <button onClick={() => navigate(`/events/${numericEventId}/queue`)}>
              예매하기 (대기열 입장)
            </button>
          )}
        </>
      )}
      <p>
        <Link to="/">← 목록으로</Link>
      </p>
    </div>
  );
}
