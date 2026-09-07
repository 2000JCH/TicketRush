import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getMyAccount } from "../api/account";
import { formatApiError } from "../api/errorMessage";
import type { AccountInfo, AccountStatus, Role } from "../api/types";

const ROLE_LABEL: Record<Role, string> = {
  BUYER: "일반 회원",
  ORGANIZER: "주최자",
  ADMIN: "관리자",
};

const STATUS_LABEL: Record<AccountStatus, string> = {
  ACTIVE: "정상",
  PENDING: "승인 대기",
  SUSPENDED: "정지됨",
};

export function AccountPage() {
  const [account, setAccount] = useState<AccountInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getMyAccount()
      .then(setAccount)
      .catch((err) => setError(formatApiError(err)));
  }, []);

  return (
    <div className="page page-narrow">
      <h1>내 정보</h1>
      {error && <p className="error">{error}</p>}
      {!account && !error && <p>불러오는 중...</p>}
      {account && (
        <dl className="info-list">
          <dt>이메일</dt>
          <dd>{account.email}</dd>
          <dt>회원 구분</dt>
          <dd>{ROLE_LABEL[account.role]}</dd>
          <dt>계정 상태</dt>
          <dd>{STATUS_LABEL[account.status]}</dd>
          <dt>가입일</dt>
          <dd>{new Date(account.createdAt).toLocaleDateString()}</dd>
        </dl>
      )}
      <p>
        <Link to="/">← 이벤트 목록으로</Link>
      </p>
    </div>
  );
}
