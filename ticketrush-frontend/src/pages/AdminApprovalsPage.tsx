import { useEffect, useState } from "react";
import { approveOrganizer, getPendingOrganizers, rejectOrganizer } from "../api/admin";
import { formatApiError } from "../api/errorMessage";
import type { AdminAccount } from "../api/types";

export function AdminApprovalsPage() {
  const [pending, setPending] = useState<AdminAccount[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  function load() {
    getPendingOrganizers()
      .then(setPending)
      .catch((err) => setError(formatApiError(err)));
  }

  useEffect(load, []);

  async function act(accountId: number, action: "approve" | "reject") {
    setError(null);
    setBusyId(accountId);
    try {
      if (action === "approve") await approveOrganizer(accountId);
      else {
        if (!window.confirm("이 주최자 가입 신청을 거절할까요? (계정이 삭제됩니다)")) return;
        await rejectOrganizer(accountId);
      }
      load();
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="page page-admin">
      <h1>주최자 승인</h1>
      {error && <p className="error">{error}</p>}
      {pending === null && !error && <p>불러오는 중...</p>}
      {pending?.length === 0 && <p>승인 대기 중인 주최자가 없습니다.</p>}
      {pending && pending.length > 0 && (
        <table className="admin-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>이메일</th>
              <th>신청일</th>
              <th>처리</th>
            </tr>
          </thead>
          <tbody>
            {pending.map((a) => (
              <tr key={a.accountId}>
                <td>{a.accountId}</td>
                <td>{a.email}</td>
                <td>{new Date(a.createdAt).toLocaleString()}</td>
                <td className="admin-actions">
                  <button disabled={busyId === a.accountId} onClick={() => act(a.accountId, "approve")}>
                    승인
                  </button>
                  <button
                    disabled={busyId === a.accountId}
                    className="secondary"
                    onClick={() => act(a.accountId, "reject")}
                  >
                    거절
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
