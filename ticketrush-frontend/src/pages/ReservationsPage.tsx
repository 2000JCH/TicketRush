import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { cancelReservation, getMyReservations } from "../api/reservations";
import { formatApiError } from "../api/errorMessage";
import type { ReservationDetail, ReservationStatus } from "../api/types";

const STATUS_LABEL: Record<ReservationStatus, string> = {
  PAYMENT_REQUESTED: "결제 처리 중",
  PAYMENT_CONFIRMED: "결제 완료",
  PAYMENT_FAILED: "결제 실패",
  SEAT_RELEASED: "취소/반납됨",
};

export function ReservationsPage() {
  const [reservations, setReservations] = useState<ReservationDetail[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cancellingId, setCancellingId] = useState<number | null>(null);

  function load() {
    getMyReservations()
      .then(setReservations)
      .catch((err) => setError(formatApiError(err)));
  }

  useEffect(load, []);

  async function handleCancel(reservationId: number) {
    if (!window.confirm("이 예약을 취소할까요?")) return;
    setError(null);
    setCancellingId(reservationId);
    try {
      await cancelReservation(reservationId);
      load();
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setCancellingId(null);
    }
  }

  return (
    <div className="page">
      <h1>내 예약</h1>
      {error && <p className="error">{error}</p>}
      {reservations === null && !error && <p>불러오는 중...</p>}
      {reservations?.length === 0 && <p>예약 내역이 없습니다.</p>}
      <ul className="reservation-list">
        {reservations?.map((r) => (
          <li key={r.reservationId} className="reservation-item">
            <div>
              <strong>예약 번호 {r.reservationId}</strong>
              <span className="muted"> — {STATUS_LABEL[r.status]}</span>
            </div>
            <div className="muted">
              {r.quantity}매 · {r.amount.toLocaleString()}원 · 요청 시각{" "}
              {new Date(r.requestedAt).toLocaleString()}
            </div>
            {r.status === "PAYMENT_CONFIRMED" && (
              <button
                disabled={cancellingId === r.reservationId}
                onClick={() => handleCancel(r.reservationId)}
                className="secondary"
              >
                {cancellingId === r.reservationId ? "취소 중..." : "예약 취소"}
              </button>
            )}
          </li>
        ))}
      </ul>
      <p>
        <Link to="/">← 이벤트 목록으로</Link>
      </p>
    </div>
  );
}
