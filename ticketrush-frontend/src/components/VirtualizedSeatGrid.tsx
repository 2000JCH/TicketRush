import { useMemo, useState } from "react";
import type { SeatStatusItem } from "../api/types";

/** index.css의 .seat-row 높이와 반드시 맞춰야 한다(어긋나면 스크롤 중 줄이 겹치거나 벌어진다). */
const ROW_HEIGHT = 56;
const CONTAINER_HEIGHT = 600;
const BUFFER_ROWS = 4;
/** 한 줄에 보여줄 좌석 수 — 컨테이너 너비로 계산하지 않고 고정값으로 끊는다(사용자 요청, 2026-09-05). */
const SEATS_PER_ROW = 10;

interface Props {
  seats: SeatStatusItem[];
  selectedSeatIds: number[];
  onToggle: (seatId: number, status: SeatStatusItem["status"]) => void;
}

/**
 * 좌석이 많은 구역(수천~수만 석)에서 전부 DOM으로 그리면 브라우저가 멈춘다(사용자 실측,
 * 2026-09-05 — 15,000석 구역에서 마우스도 안 움직일 정도로 렉). 스크롤 위치 기준으로 실제
 * 보이는 줄만 그리고 나머지는 그리지 않는 가상화(virtualization)로 해결한다.
 *
 * 줄(row) 단위를 좌석의 실제 `rowNo`가 아니라 10개씩 고정으로 끊은 "화면 줄"로 잡는다
 * (2026-09-05, 사용자 피드백) — `rowNo` 그대로 한 줄씩 그리면 한 행이 100석 같은 경우 가로
 * 스크롤이 생겨버려서, 세로 스크롤에만 가상화를 적용하고 가로는 고정 10개 단위로 끊는다.
 */
export function VirtualizedSeatGrid({ seats, selectedSeatIds, onToggle }: Props) {
  const [scrollTop, setScrollTop] = useState(0);

  const rows = useMemo(() => {
    const grouped: SeatStatusItem[][] = [];
    for (let i = 0; i < seats.length; i += SEATS_PER_ROW) {
      grouped.push(seats.slice(i, i + SEATS_PER_ROW));
    }
    return grouped;
  }, [seats]);

  const visibleRowCount = Math.ceil(CONTAINER_HEIGHT / ROW_HEIGHT);
  const startRow = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - BUFFER_ROWS);
  const endRow = Math.min(rows.length, startRow + visibleRowCount + BUFFER_ROWS * 2);

  return (
    <div
      className="seat-grid-viewport"
      style={{ height: Math.min(CONTAINER_HEIGHT, rows.length * ROW_HEIGHT) || undefined }}
      onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}
    >
      <div className="seat-grid-spacer" style={{ height: rows.length * ROW_HEIGHT }}>
        {rows.slice(startRow, endRow).map((row, i) => (
          <div key={startRow + i} className="seat-row" style={{ top: (startRow + i) * ROW_HEIGHT }}>
            {row.map((seat) => (
              <button
                key={seat.seatId}
                disabled={seat.status !== "AVAILABLE" && !selectedSeatIds.includes(seat.seatId)}
                className={`seat ${seat.status.toLowerCase()} ${
                  selectedSeatIds.includes(seat.seatId) ? "selected" : ""
                }`}
                onClick={() => onToggle(seat.seatId, seat.status)}
              >
                {seat.rowNo}-{seat.seatNo}
              </button>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
