import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { signup } from "../api/auth";
import { formatApiError } from "../api/errorMessage";
import type { Role, SignupResponse } from "../api/types";

export function SignupPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<Role>("BUYER");
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SignupResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const response = await signup(email, password, role);
      setResult(response);
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (result) {
    return (
      <div className="page page-narrow">
        <h1>가입 완료</h1>
        {result.role === "ORGANIZER" ? (
          <p>
            주최자 계정은 관리자 승인이 필요합니다. 승인 전까지는 로그인할 수 없습니다.
            (현재 상태: {result.status})
          </p>
        ) : (
          <p>가입이 완료되었습니다. 로그인해주세요.</p>
        )}
        <Link to="/login">로그인하러 가기</Link>
      </div>
    );
  }

  return (
    <div className="page page-narrow">
      <h1>회원가입</h1>
      <form onSubmit={handleSubmit} className="form">
        <label>
          이메일
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </label>
        <label>
          비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={8}
          />
        </label>
        <fieldset>
          <legend>가입 유형</legend>
          <label className="radio">
            <input
              type="radio"
              name="role"
              value="BUYER"
              checked={role === "BUYER"}
              onChange={() => setRole("BUYER")}
            />
            구매자(BUYER)
          </label>
          <label className="radio">
            <input
              type="radio"
              name="role"
              value="ORGANIZER"
              checked={role === "ORGANIZER"}
              onChange={() => setRole("ORGANIZER")}
            />
            주최자(ORGANIZER) — 관리자 승인 필요
          </label>
        </fieldset>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={submitting}>
          가입하기
        </button>
      </form>
      <p>
        이미 계정이 있으신가요? <Link to="/login">로그인</Link>
      </p>
      <button type="button" className="link-button" onClick={() => navigate(-1)}>
        뒤로가기
      </button>
    </div>
  );
}
