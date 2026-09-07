import { Fragment, useCallback, useEffect, useState } from "react";
import {
  getMemberReservations,
  getMembers,
  reactivateAccount,
  suspendAccount,
} from "../api/admin";
import { formatApiError } from "../api/errorMessage";
import type { AdminAccount, PagedResponse, ReservationDetail, Role, AccountStatus } from "../api/types";

const ROLE_LABEL: Record<Role, string> = {
  BUYER: "일반",
  ORGANIZER: "주최자",
  ADMIN: "관리자",
};
const STATUS_LABEL: Record<AccountStatus, string> = {
  ACTIVE: "정상",
  PENDING: "승인 대기",
  SUSPENDED: "정지됨",
};
const RESERVATION_STATUS_LABEL: Record<string, string> = {
  PAYMENT_REQUESTED: "결제 처리 중",
  PAYMENT_CONFIRMED: "결제 완료",
  PAYMENT_FAILED: "결제 실패",
  SEAT_RELEASED: "취소/반납",
};

export function AdminMembersPage() {
  const [data, setData] = useState<PagedResponse<AdminAccount> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [emailInput, setEmailInput] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role | "">("");
  const [status, setStatus] = useState<AccountStatus | "">("");
  const [page, setPage] = useState(0);
  const [busyId, setBusyId] = useState<number | null>(null);

  const [openId, setOpenId] = useState<number | null>(null);
  const [reservations, setReservations] = useState<ReservationDetail[] | null>(null);

  const load = useCallback(() => {
    setError(null);
    getMembers({
      email: email || undefined,
      role: role || undefined,
      status: status || undefined,
      page,
      size: 20,
    })
      .then(setData)
      .catch((err) => setError(formatApiError(err)));
  }, [email, role, status, page]);

  useEffect(load, [load]);

  async function toggleReservations(accountId: number) {
    if (openId === accountId) {
      setOpenId(null);
      setReservations(null);
      return;
    }
    setOpenId(accountId);
    setReservations(null);
    try {
      setReservations(await getMemberReservations(accountId));
    } catch (err) {
      setError(formatApiError(err));
    }
  }

  async function toggleSuspend(a: AdminAccount) {
    const suspend = a.status !== "SUSPENDED";
    if (suspend && !window.confirm(`${a.email} 계정을 정지할까요?`)) return;
    setBusyId(a.accountId);
    setError(null);
    try {
      if (suspend) await suspendAccount(a.accountId);
      else await reactivateAccount(a.accountId);
      load();
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="page page-admin">
      <h1>회원 관리</h1>
      {error && <p className="error">{error}</p>}

      <form
        className="admin-filters"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setEmail(emailInput.trim());
        }}
      >
        <input
          placeholder="이메일 검색"
          value={emailInput}
          onChange={(e) => setEmailInput(e.target.value)}
        />
        <select
          value={role}
          onChange={(e) => {
            setPage(0);
            setRole(e.target.value as Role | "");
          }}
        >
          <option value="">전체 역할</option>
          <option value="BUYER">일반</option>
          <option value="ORGANIZER">주최자</option>
          <option value="ADMIN">관리자</option>
        </select>
        <select
          value={status}
          onChange={(e) => {
            setPage(0);
            setStatus(e.target.value as AccountStatus | "");
          }}
        >
          <option value="">전체 상태</option>
          <option value="ACTIVE">정상</option>
          <option value="PENDING">승인 대기</option>
          <option value="SUSPENDED">정지됨</option>
        </select>
        <button type="submit">검색</button>
      </form>

      {data === null && !error && <p>불러오는 중...</p>}
      {data && (
        <>
          <p className="muted">총 {data.totalElements.toLocaleString()}명</p>
          <table className="admin-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>이메일</th>
                <th>역할</th>
                <th>상태</th>
                <th>가입일</th>
                <th>예매 내역</th>
                <th>계정</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((a) => (
                <Fragment key={a.accountId}>
                  <tr className={a.status === "SUSPENDED" ? "row-suspended" : ""}>
                    <td>{a.accountId}</td>
                    <td>{a.email}</td>
                    <td>{ROLE_LABEL[a.role]}</td>
                    <td>{STATUS_LABEL[a.status]}</td>
                    <td>{new Date(a.createdAt).toLocaleDateString()}</td>
                    <td>
                      <button className="link-button" onClick={() => toggleReservations(a.accountId)}>
                        {openId === a.accountId ? "닫기" : "보기"}
                      </button>
                    </td>
                    <td>
                      {a.role !== "ADMIN" && (
                        <button
                          className="secondary"
                          disabled={busyId === a.accountId}
                          onClick={() => toggleSuspend(a)}
                        >
                          {a.status === "SUSPENDED" ? "정지 해제" : "정지"}
                        </button>
                      )}
                    </td>
                  </tr>
                  {openId === a.accountId && (
                    <tr>
                      <td colSpan={7} className="admin-detail-cell">
                        {reservations === null && <span className="muted">불러오는 중...</span>}
                        {reservations?.length === 0 && <span className="muted">예매 내역 없음</span>}
                        {reservations && reservations.length > 0 && (
                          <ul className="admin-reservation-list">
                            {reservations.map((r) => (
                              <li key={r.reservationId}>
                                <strong>{r.eventName}</strong> · {r.quantity}매 ·{" "}
                                {r.amount.toLocaleString()}원 ·{" "}
                                {RESERVATION_STATUS_LABEL[r.status] ?? r.status}
                                {r.seats.length > 0 && (
                                  <span className="muted">
                                    {" "}
                                    ({r.seats.map((s) => `${s.sectionName} ${s.rowNo}-${s.seatNo}`).join(", ")})
                                  </span>
                                )}
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>

          <div className="admin-pagination">
            <button disabled={data.page <= 0} onClick={() => setPage((p) => p - 1)}>
              이전
            </button>
            <span>
              {data.page + 1} / {Math.max(data.totalPages, 1)}
            </span>
            <button
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              다음
            </button>
          </div>
        </>
      )}
    </div>
  );
}
