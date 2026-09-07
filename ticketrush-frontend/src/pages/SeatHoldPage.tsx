import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getEvent } from "../api/events";
import { getSeats, holdSeats, releaseHold } from "../api/seats";
import { getReservation, requestPayment } from "../api/reservations";
import { getPaymentConfig } from "../api/payments";
import { VirtualizedSeatGrid } from "../components/VirtualizedSeatGrid";
import { ApiError } from "../api/client";
import { formatApiError } from "../api/errorMessage";
import { clearEntryToken, getEntryToken } from "../lib/entryTokenStore";
import { randomId } from "../lib/randomId";
import * as PortOne from "@portone/browser-sdk/v2";
import type {
  EventDetail,
  EventSection,
  PaymentConfig,
  ReservationDetail,
  ReservationResponse,
  SeatHoldResponse,
  SeatStatusItem,
} from "../api/types";

type PayMethod = "CARD" | "KAKAOPAY";

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
  const [payMethod, setPayMethod] = useState<PayMethod>("CARD");
  const [paymentConfig, setPaymentConfig] = useState<PaymentConfig | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  // 홀드 중이면 1초마다 현재 시각을 갱신해 "자동 취소까지 남은 시간"을 실시간으로 보여준다.
  useEffect(() => {
    if (!hold) return;
    const timer = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [hold]);

  useEffect(() => {
    if (!entryToken) {
      navigate(`/events/${numericEventId}`, { replace: true });
      return;
    }
    getEvent(numericEventId)
      .then(setEvent)
      .catch((err) => setError(formatApiError(err)));
    // 결제창 호출에 필요한 storeId/channelKey. 설정이 없으면(로컬 미설정) 결제창 없이 요청만 한다.
    getPaymentConfig()
      .then(setPaymentConfig)
      .catch(() => setPaymentConfig(null));
  }, [numericEventId, entryToken, navigate]);

  // PortOne 리디렉션 방식(주로 모바일)으로 결제 후 이 페이지로 복귀했을 때의 처리.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const paymentId = params.get("paymentId");
    if (!paymentId) return;
    window.history.replaceState({}, "", window.location.pathname);
    const code = params.get("code");
    if (code) {
      setError(`결제가 취소되었거나 실패했습니다: ${params.get("message") ?? code}`);
      return;
    }
    const rid = Number(paymentId.replace("TICKETRUSH-", ""));
    if (Number.isFinite(rid)) {
      setReservation({
        reservationId: rid,
        status: "PAYMENT_REQUESTED",
        pgPaymentId: paymentId,
        amount: 0,
        orderName: "",
      });
    }
  }, []);

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

  // 홀드만 잡고 결제 요청 전에 페이지를 벗어나면(로고/이벤트 상세로/뒤로가기 등) 좌석이
  // TTL까지 잠긴 채로 남는다 — unmount 시 자동으로 홀드를 해제한다(사용자 확인, 2026-09-06).
  const holdRef = useRef<SeatHoldResponse | null>(null);
  const reservationRef = useRef<ReservationResponse | null>(null);
  useEffect(() => {
    holdRef.current = hold;
  }, [hold]);
  useEffect(() => {
    reservationRef.current = reservation;
  }, [reservation]);
  useEffect(() => {
    return () => {
      if (holdRef.current && !reservationRef.current && entryToken) {
        void releaseHold(numericEventId, entryToken).catch(() => {});
      }
    };
  }, [numericEventId, entryToken]);

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

  async function openPortOneCheckout(response: ReservationResponse) {
    if (!paymentConfig?.storeId) return true; // 설정 없음 — 결제창 없이 요청만 (로컬)
    const channelKey =
      payMethod === "KAKAOPAY"
        ? paymentConfig.easyPayChannelKey
        : paymentConfig.cardChannelKey;
    const portoneResponse = await PortOne.requestPayment({
      storeId: paymentConfig.storeId,
      channelKey,
      paymentId: response.pgPaymentId,
      orderName: response.orderName,
      totalAmount: response.amount,
      currency: "KRW",
      payMethod: payMethod === "KAKAOPAY" ? "EASY_PAY" : "CARD",
      ...(payMethod === "KAKAOPAY" ? { easyPayProvider: "KAKAOPAY" } : {}),
      redirectUrl: `${window.location.origin}/events/${numericEventId}/seats`,
    });
    // 리디렉션 방식이면 여기 도달 전에 페이지를 떠난다. 프로미스로 돌아온 경우만 여기서 처리.
    if (portoneResponse?.code != null) {
      setError(
        `결제가 취소되었거나 실패했습니다: ${portoneResponse.message ?? portoneResponse.code}`
      );
      return false;
    }
    return true;
  }

  async function handleRequestPayment() {
    if (!section || !entryToken) return;
    setError(null);
    setBusy(true);
    try {
      const idempotencyKey = randomId();
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

      // 서버에 PAYMENT_REQUESTED 예약이 생긴 뒤 PG 결제창을 띄운다. 결제 완료/실패는 웹훅으로
      // 서버 상태가 바뀌고, 아래 결과 화면이 GET /reservations/{id} 폴링으로 그걸 반영한다.
      const ok = await openPortOneCheckout(response);
      if (!ok) return;
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

  const heldSeatLabels =
    hold && section?.type === "SEATED" && seats
      ? seats
          .filter((s) => selectedSeatIds.includes(s.seatId))
          .map((s) => `${s.rowNo}-${s.seatNo}`)
      : [];

  const holdMsLeft = hold ? new Date(hold.holdExpiresAt).getTime() - nowMs : 0;
  const holdCountdown =
    holdMsLeft > 0
      ? `${Math.floor(holdMsLeft / 60000)}분 ${String(
          Math.floor((holdMsLeft % 60000) / 1000)
        ).padStart(2, "0")}초 후 자동 취소`
      : "홀드 시간이 만료되었습니다";

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

      {event && (
        <div className="seat-hold-layout">
          <div className="seat-hold-main">
            {!hold && (
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
                    {seats && (
                      <VirtualizedSeatGrid
                        seats={seats}
                        selectedSeatIds={selectedSeatIds}
                        onToggle={toggleSeat}
                      />
                    )}
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
                  </>
                )}
              </>
            )}

            {hold && (
              <div className="held-seats">
                <p className="muted">
                  홀드한 좌석은 오른쪽 패널에서 결제를 진행하거나 취소할 수 있습니다.
                </p>
                <div className="held-seats-list">
                  {section?.type === "SEATED" ? (
                    heldSeatLabels.map((label) => (
                      <span key={label} className="held-seat-chip">
                        {label}
                      </span>
                    ))
                  ) : (
                    <span className="held-seat-chip">스탠딩 {standingQuantity}매</span>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* 좌석이 많으면 그리드가 길어져 아래쪽 버튼까지 스크롤해야 하는 문제(사용자 지적,
              2026-09-05) — 액션 버튼을 오른쪽 사이드바로 옮기고 sticky로 고정해 스크롤 중에도
              항상 보이게 한다. */}
          <aside className="seat-hold-sidebar">
            {!hold && section?.type === "SEATED" && (
              <>
                <p>선택한 좌석: {selectedSeatIds.length} / {MAX_QUANTITY}석</p>
                <button disabled={selectedSeatIds.length === 0 || busy} onClick={handleHold}>
                  선택한 좌석 홀드하기 ({selectedSeatIds.length}석)
                </button>
              </>
            )}
            {!hold && section?.type === "STANDING" && (
              <button disabled={busy} onClick={handleHold}>
                홀드하기
              </button>
            )}

            {hold && (
              <div className="hold-panel">
                <h2>홀드 완료</h2>
                <p className={holdMsLeft > 0 ? "hold-countdown" : "hold-countdown expired"}>
                  {holdCountdown}
                </p>
                <p className="muted">만료 시각 {new Date(hold.holdExpiresAt).toLocaleTimeString()}</p>
                {paymentConfig?.storeId && (
                  <div className="pay-method">
                    <label>
                      <input
                        type="radio"
                        name="payMethod"
                        checked={payMethod === "CARD"}
                        onChange={() => setPayMethod("CARD")}
                      />
                      카드
                    </label>
                    <label>
                      <input
                        type="radio"
                        name="payMethod"
                        checked={payMethod === "KAKAOPAY"}
                        onChange={() => setPayMethod("KAKAOPAY")}
                      />
                      카카오페이
                    </label>
                  </div>
                )}
                <button disabled={busy} onClick={handleRequestPayment}>
                  {paymentConfig?.storeId ? "결제하기" : "결제 요청하기"}
                </button>
                <button disabled={busy} onClick={handleReleaseHold} className="secondary">
                  홀드 취소하고 다시 선택
                </button>
              </div>
            )}

            <p>
              <Link to={`/events/${numericEventId}`}>← 이벤트 상세로</Link>
            </p>
          </aside>
        </div>
      )}
    </div>
  );
}
