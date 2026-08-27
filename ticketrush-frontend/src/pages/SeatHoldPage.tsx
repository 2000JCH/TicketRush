import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getEvent } from "../api/events";
import { getSeats, holdSeats, releaseHold } from "../api/seats";
import { getReservation, requestPayment } from "../api/reservations";
import { ApiError } from "../api/client";
import { formatApiError } from "../api/errorMessage";
import { clearEntryToken, getEntryToken } from "../lib/entryTokenStore";
import type {
  EventDetail,
  EventSection,
  ReservationDetail,
  ReservationResponse,
  SeatHoldResponse,
  SeatStatusItem,
} from "../api/types";

const MAX_QUANTITY = 2;
const POLL_INTERVAL_MS = 2000;

const RESULT_LABEL: Record<string, string> = {
  PAYMENT_REQUESTED: "결제 처리 중 — 잠시 후 다시 확인해주세요.",
  PAYMENT_CONFIRMED: "결제가 완료됐습니다!",
  PAYMENT_FAILED: "결제가 실패했습니다.",
  SEAT_RELEASED: "좌석이 반납되었습니다.",
};

export function SeatHoldPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const numericEventId = Number(eventId);
  const navigate = useNavigate();
  const entryToken = getEntryToken(numericEventId);

  const [event, setEvent] = useState<EventDetail | null>(null);
  const [section, setSection] = useState<EventSection | null>(null);
  const [seats, setSeats] = useState<SeatStatusItem[] | null>(null);
  const [selectedSeatIds, setSelectedSeatIds] = useState<number[]>([]);
  const [standingQuantity, setStandingQuantity] = useState(1);
  const [hold, setHold] = useState<SeatHoldResponse | null>(null);
  const [reservation, setReservation] = useState<ReservationResponse | null>(null);
  const [result, setResult] = useState<ReservationDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!entryToken) {
      navigate(`/events/${numericEventId}`, { replace: true });
      return;
    }
    getEvent(numericEventId)
      .then(setEvent)
      .catch((err) => setError(formatApiError(err)));
  }, [numericEventId, entryToken, navigate]);

  // 결제 요청 직후 PG 웹훅(또는 카오스 테스트 등)으로 상태가 바뀔 때까지 결과 화면에서 폴링한다.
  useEffect(() => {
    if (!reservation || result) return;
    let cancelled = false;

    async function poll() {
      try {
        const detail = await getReservation(reservation!.reservationId);
        if (cancelled) return;
        if (detail.status !== "PAYMENT_REQUESTED") {
          setResult(detail);
        } else {
          setTimeout(poll, POLL_INTERVAL_MS);
        }
      } catch {
        if (!cancelled) setTimeout(poll, POLL_INTERVAL_MS);
      }
    }

    const timer = setTimeout(poll, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [reservation, result]);

  function handleExpiredToken() {
    clearEntryToken(numericEventId);
    navigate(`/events/${numericEventId}`, { replace: true });
  }

  async function selectSection(target: EventSection) {
    setError(null);
    setSection(target);
    setSelectedSeatIds([]);
    setSeats(null);
    if (target.type !== "SEATED" || !entryToken) return;
    try {
      const items = await getSeats(numericEventId, target.id, entryToken);
      setSeats(items);
    } catch (err) {
      if (err instanceof ApiError && err.code === "ENTRY_TOKEN_EXPIRED") {
        handleExpiredToken();
        return;
      }
      setError(formatApiError(err));
    }
  }

  function toggleSeat(seatId: number, status: SeatStatusItem["status"]) {
    if (status !== "AVAILABLE") return;
    setSelectedSeatIds((current) => {
      if (current.includes(seatId)) return current.filter((id) => id !== seatId);
      if (current.length >= MAX_QUANTITY) return current;
      return [...current, seatId];
    });
  }

  async function handleHold() {
    if (!section || !entryToken) return;
    setError(null);
    setBusy(true);
    try {
      const response =
        section.type === "SEATED"
          ? await holdSeats(
              numericEventId,
              { sectionId: section.id, seatIds: selectedSeatIds },
              entryToken
            )
          : await holdSeats(
              numericEventId,
              { sectionId: section.id, quantity: standingQuantity },
              entryToken
            );
      setHold(response);
    } catch (err) {
      if (err instanceof ApiError && err.code === "ENTRY_TOKEN_EXPIRED") {
        handleExpiredToken();
        return;
      }
      setError(formatApiError(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleReleaseHold() {
    if (!entryToken) return;
    setBusy(true);
    setError(null);
    try {
      await releaseHold(numericEventId, entryToken);
      setHold(null);
      setSelectedSeatIds([]);
      if (section?.type === "SEATED") await selectSection(section);
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleRequestPayment() {
    if (!section || !entryToken) return;
    setError(null);
    setBusy(true);
    try {
      const idempotencyKey = crypto.randomUUID();
      const response =
        section.type === "SEATED"
          ? await requestPayment(
              {
                eventId: numericEventId,
                sectionId: section.id,
                seatIds: selectedSeatIds,
                idempotencyKey,
              },
              entryToken
            )
          : await requestPayment(
              {
                eventId: numericEventId,
                sectionId: section.id,
                quantity: standingQuantity,
                idempotencyKey,
              },
              entryToken
            );
      setReservation(response);
    } catch (err) {
      if (err instanceof ApiError && err.code === "ENTRY_TOKEN_EXPIRED") {
        handleExpiredToken();
        return;
      }
      setError(formatApiError(err));
    } finally {
      setBusy(false);
    }
  }

  if (reservation) {
    const status = result?.status ?? "PAYMENT_REQUESTED";
    return (
      <div className="page page-narrow">
        <h1>결제 요청 완료</h1>
        <p>예약 번호: {reservation.reservationId}</p>
        <p>{RESULT_LABEL[status]}</p>
        {!result && <p className="muted">결제 결과를 자동으로 확인하는 중...</p>}
        {result && <p className="muted">{result.amount.toLocaleString()}원 · {result.quantity}매</p>}
        <p>
          <Link to="/reservations">내 예약 보기</Link>
          {" · "}
          <Link to="/">이벤트 목록으로</Link>
        </p>
      </div>
    );
  }

  return (
    <div className="page">
      <h1>좌석 선택</h1>
      {error && <p className="error">{error}</p>}
      {!event && !error && <p>불러오는 중...</p>}

      {event && !hold && (
        <>
          <h2>구역 선택</h2>
          <ul className="section-list">
            {event.sections.map((s) => (
              <li key={s.id}>
                <button
                  className={section?.id === s.id ? "selected" : ""}
                  onClick={() => selectSection(s)}
                >
                  {s.name} ({s.type === "SEATED" ? "지정석" : "스탠딩"}) —{" "}
                  {s.price.toLocaleString()}원
                </button>
              </li>
            ))}
          </ul>

          {section?.type === "SEATED" && (
            <>
              <h2>좌석 선택 (최대 {MAX_QUANTITY}석)</h2>
              {!seats && <p>좌석 정보를 불러오는 중...</p>}
              <div className="seat-grid">
                {seats?.map((seat) => (
                  <button
                    key={seat.seatId}
                    disabled={seat.status !== "AVAILABLE" && !selectedSeatIds.includes(seat.seatId)}
                    className={`seat ${seat.status.toLowerCase()} ${
                      selectedSeatIds.includes(seat.seatId) ? "selected" : ""
                    }`}
                    onClick={() => toggleSeat(seat.seatId, seat.status)}
                  >
                    {seat.rowNo}-{seat.seatNo}
                  </button>
                ))}
              </div>
              <button
                disabled={selectedSeatIds.length === 0 || busy}
                onClick={handleHold}
              >
                선택한 좌석 홀드하기 ({selectedSeatIds.length}석)
              </button>
            </>
          )}

          {section?.type === "STANDING" && (
            <>
              <h2>수량 선택</h2>
              <label>
                매수
                <select
                  value={standingQuantity}
                  onChange={(e) => setStandingQuantity(Number(e.target.value))}
                >
                  <option value={1}>1매</option>
                  <option value={2}>2매</option>
                </select>
              </label>
              <button disabled={busy} onClick={handleHold}>
                홀드하기
              </button>
            </>
          )}
        </>
      )}

      {hold && (
        <div className="hold-panel">
          <h2>홀드 완료</h2>
          <p>홀드 만료 시각: {new Date(hold.holdExpiresAt).toLocaleString()}</p>
          <button disabled={busy} onClick={handleRequestPayment}>
            결제 요청하기
          </button>
          <button disabled={busy} onClick={handleReleaseHold} className="secondary">
            홀드 취소하고 다시 선택
          </button>
        </div>
      )}

      <p>
        <Link to={`/events/${numericEventId}`}>← 이벤트 상세로</Link>
      </p>
    </div>
  );
}
