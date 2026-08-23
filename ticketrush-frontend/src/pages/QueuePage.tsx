import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { enterQueue, getQueueStatus } from "../api/queue";
import { formatApiError } from "../api/errorMessage";
import { getEntryToken, setEntryToken } from "../lib/entryTokenStore";

const POLL_INTERVAL_MS = 2000;

export function QueuePage() {
  const { eventId } = useParams<{ eventId: string }>();
  const numericEventId = Number(eventId);
  const navigate = useNavigate();

  const [rank, setRank] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const intervalRef = useRef<number | null>(null);

  useEffect(() => {
    const existingToken = getEntryToken(numericEventId);
    if (existingToken) {
      navigate(`/events/${numericEventId}/seats`, { replace: true });
      return;
    }

    let cancelled = false;

    function handleAdmitted(token: string) {
      setEntryToken(numericEventId, token);
      if (intervalRef.current) window.clearInterval(intervalRef.current);
      navigate(`/events/${numericEventId}/seats`, { replace: true });
    }

    enterQueue(numericEventId)
      .then((status) => {
        if (cancelled) return;
        if (status.entryToken) {
          handleAdmitted(status.entryToken);
          return;
        }
        setRank(status.rank);

        intervalRef.current = window.setInterval(async () => {
          try {
            const polled = await getQueueStatus(numericEventId);
            if (cancelled) return;
            if (polled.entryToken) {
              handleAdmitted(polled.entryToken);
            } else {
              setRank(polled.rank);
            }
          } catch (err) {
            if (!cancelled) setError(formatApiError(err));
          }
        }, POLL_INTERVAL_MS);
      })
      .catch((err) => {
        if (!cancelled) setError(formatApiError(err));
      });

    return () => {
      cancelled = true;
      if (intervalRef.current) window.clearInterval(intervalRef.current);
    };
  }, [numericEventId, navigate]);

  return (
    <div className="page page-narrow">
      <h1>대기열</h1>
      {error && <p className="error">{error}</p>}
      {rank === null && !error && <p>대기열에 진입하는 중...</p>}
      {rank !== null && (
        <div className="queue-status">
          <p className="queue-rank">내 순번: {rank.toLocaleString()}번</p>
          <p className="muted">차례가 되면 자동으로 좌석 선택 화면으로 이동합니다.</p>
        </div>
      )}
    </div>
  );
}
