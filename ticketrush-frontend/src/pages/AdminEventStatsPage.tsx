import { useEffect, useState } from "react";
import { getEventStats } from "../api/admin";
import { formatApiError } from "../api/errorMessage";
import type { AdminEventStats } from "../api/types";

export function AdminEventStatsPage() {
  const [stats, setStats] = useState<AdminEventStats[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getEventStats()
      .then(setStats)
      .catch((err) => setError(formatApiError(err)));
  }, []);

  const totalAmount = stats?.reduce((sum, s) => sum + s.confirmedAmount, 0) ?? 0;

  return (
    <div className="page page-admin">
      <h1>콘서트 현황</h1>
      {error && <p className="error">{error}</p>}
      {stats === null && !error && <p>불러오는 중...</p>}
      {stats && (
        <>
          <p className="muted">전체 확정 매출 합계 {totalAmount.toLocaleString()}원</p>
          <table className="admin-table">
            <thead>
              <tr>
                <th>콘서트</th>
                <th>오픈</th>
                <th>판매 좌석</th>
                <th>잔여</th>
                <th>확정 예약</th>
                <th>매출</th>
              </tr>
            </thead>
            <tbody>
              {stats.map((s) => {
                const ratio = s.capacity > 0 ? Math.round((s.sold / s.capacity) * 100) : 0;
                return (
                  <tr key={s.eventId}>
                    <td>{s.eventName}</td>
                    <td>{new Date(s.openAt).toLocaleDateString()}</td>
                    <td>
                      <div className="occupancy">
                        <div className="occupancy-bar">
                          <div className="occupancy-fill" style={{ width: `${ratio}%` }} />
                        </div>
                        <span>
                          {s.sold.toLocaleString()} / {s.capacity.toLocaleString()}
                        </span>
                      </div>
                    </td>
                    <td>{s.remaining.toLocaleString()}석 남음</td>
                    <td>{s.confirmedReservations.toLocaleString()}건</td>
                    <td>{s.confirmedAmount.toLocaleString()}원</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
