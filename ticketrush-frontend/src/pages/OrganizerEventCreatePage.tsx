import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { createEvent } from "../api/events";
import { formatApiError } from "../api/errorMessage";
import type { SectionInput, SectionType } from "../api/types";

/** datetime-local 기본값: 지금부터 1시간 뒤 (백엔드 @Future 통과용). */
function defaultOpenAt(): string {
  const d = new Date(Date.now() + 60 * 60 * 1000);
  d.setSeconds(0, 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(
    d.getHours()
  )}:${pad(d.getMinutes())}`;
}

type SectionForm = {
  name: string;
  type: SectionType;
  price: string;
  rowCount: string;
  seatsPerRow: string;
  totalQuantity: string;
};

const emptySection = (): SectionForm => ({
  name: "",
  type: "SEATED",
  price: "",
  rowCount: "",
  seatsPerRow: "",
  totalQuantity: "",
});

export function OrganizerEventCreatePage() {
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [openAt, setOpenAt] = useState(defaultOpenAt);
  const [sections, setSections] = useState<SectionForm[]>([emptySection()]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function updateSection(i: number, patch: Partial<SectionForm>) {
    setSections((cur) => cur.map((s, idx) => (idx === i ? { ...s, ...patch } : s)));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    const payloadSections: SectionInput[] = sections.map((s) => {
      const base = { name: s.name.trim(), type: s.type, price: Number(s.price) };
      return s.type === "SEATED"
        ? {
            ...base,
            rowCount: Number(s.rowCount),
            seatsPerRow: Number(s.seatsPerRow),
          }
        : { ...base, totalQuantity: Number(s.totalQuantity) };
    });

    setSubmitting(true);
    try {
      const created = await createEvent({
        name: name.trim(),
        openAt: `${openAt}:00`,
        sections: payloadSections,
      });
      navigate(`/events/${created.id}`);
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page">
      <h1>공연 등록</h1>
      <p className="muted">
        등록하면 승인 없이 바로 공연 목록에 노출되고 예매가 시작됩니다.
      </p>

      <form onSubmit={handleSubmit} className="form organizer-form">
        <label>
          공연명
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          예매 오픈 일시
          <input
            type="datetime-local"
            value={openAt}
            onChange={(e) => setOpenAt(e.target.value)}
            required
          />
        </label>

        <h2 className="section-heading">좌석 구역</h2>
        {sections.map((s, i) => (
          <fieldset key={i} className="organizer-section">
            <legend>구역 {i + 1}</legend>
            <label>
              구역명
              <input
                value={s.name}
                onChange={(e) => updateSection(i, { name: e.target.value })}
                required
              />
            </label>
            <label>
              유형
              <select
                value={s.type}
                onChange={(e) =>
                  updateSection(i, { type: e.target.value as SectionType })
                }
              >
                <option value="SEATED">지정석</option>
                <option value="STANDING">스탠딩</option>
              </select>
            </label>
            <label>
              가격 (원)
              <input
                type="number"
                min={0}
                value={s.price}
                onChange={(e) => updateSection(i, { price: e.target.value })}
                required
              />
            </label>
            {s.type === "SEATED" ? (
              <div className="organizer-row">
                <label>
                  행 수
                  <input
                    type="number"
                    min={1}
                    value={s.rowCount}
                    onChange={(e) =>
                      updateSection(i, { rowCount: e.target.value })
                    }
                    required
                  />
                </label>
                <label>
                  행당 좌석 수
                  <input
                    type="number"
                    min={1}
                    value={s.seatsPerRow}
                    onChange={(e) =>
                      updateSection(i, { seatsPerRow: e.target.value })
                    }
                    required
                  />
                </label>
              </div>
            ) : (
              <label>
                총 수량
                <input
                  type="number"
                  min={1}
                  value={s.totalQuantity}
                  onChange={(e) =>
                    updateSection(i, { totalQuantity: e.target.value })
                  }
                  required
                />
              </label>
            )}
            {sections.length > 1 && (
              <button
                type="button"
                className="secondary"
                onClick={() =>
                  setSections((cur) => cur.filter((_, idx) => idx !== i))
                }
              >
                구역 삭제
              </button>
            )}
          </fieldset>
        ))}

        <button
          type="button"
          className="secondary"
          onClick={() => setSections((cur) => [...cur, emptySection()])}
        >
          + 구역 추가
        </button>

        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? "등록 중..." : "등록하기"}
        </button>
      </form>
    </div>
  );
}
