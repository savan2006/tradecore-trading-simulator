"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { api, type AdminAuditLogPage, type ApiError } from "@/lib/api";
import { EmptyState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";

const PAGE_SIZE = 20;
type Filters = { action: string; actor: string; targetType: string; outcome: string; from: string; to: string };
const EMPTY_FILTERS: Filters = { action: "", actor: "", targetType: "", outcome: "", from: "", to: "" };

export default function AdminAuditLogsPage() {
  const { session, adminAccess, refreshAdminAccess } = useAuth();
  const [data, setData] = useState<AdminAuditLogPage | null>(null);
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<Filters>(EMPTY_FILTERS);
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!session || adminAccess !== "allowed") return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    api.adminAuditLogs(session.basicCredential, { page, size: PAGE_SIZE, ...filters }, controller.signal)
      .then(setData)
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(messageOf(reason)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [session, adminAccess, page, filters]);

  if (!session) return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Audit logs" /><LoginRequired /></div>;
  if (adminAccess === "checking" || adminAccess === "signed-out") {
    return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Audit logs" /><LoadingState label="Checking administrator access…" /></div>;
  }
  if (adminAccess === "forbidden") return <AccessError title="Administrator access required" detail="Your account does not have access to audit logs." />;
  if (adminAccess === "unauthorized") return <AccessError title="Session expired" detail="Sign in again to view administrator audit logs." login />;
  if (adminAccess === "unavailable") return <AccessError title="Could not verify administrator access" detail="The admin overview request failed." retry={refreshAdminAccess} />;

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setFilters({ ...draft, action: draft.action.trim(), actor: draft.actor.trim(), targetType: draft.targetType.trim() });
  }

  return <div className="content-wrap admin-page">
    <PageHeading eyebrow="Operations" title="Audit logs" description="Read-only record of important TradeCore actions, newest first." />
    <div className="admin-subnav"><Link className="text-link" href="/admin">Admin overview</Link><span aria-current="page">Audit logs</span></div>
    <section className="panel admin-panel">
      <div className="panel-heading"><div><p className="eyebrow">Audit trail</p><h2>Recorded actions</h2></div>{data && <span className="muted admin-total">{data.totalElements.toLocaleString("en-IN")} matching</span>}</div>
      <form className="admin-order-filters audit-filter-form" onSubmit={applyFilters}>
        <label>Action<input value={draft.action} maxLength={64} onChange={(event) => setDraft({ ...draft, action: event.target.value })} placeholder="Any action" /></label>
        <label>Actor<input value={draft.actor} maxLength={320} onChange={(event) => setDraft({ ...draft, actor: event.target.value })} placeholder="Email or display name" /></label>
        <label>Target type<input value={draft.targetType} maxLength={48} onChange={(event) => setDraft({ ...draft, targetType: event.target.value })} placeholder="Any target" /></label>
        <label>Outcome<select value={draft.outcome} onChange={(event) => setDraft({ ...draft, outcome: event.target.value })}><option value="">All outcomes</option><option value="SUCCESS">Success</option><option value="FAILURE">Failure</option><option value="UNKNOWN">Unknown</option></select></label>
        <label>From<input type="date" value={draft.from} onChange={(event) => setDraft({ ...draft, from: event.target.value })} /></label>
        <label>To<input type="date" value={draft.to} onChange={(event) => setDraft({ ...draft, to: event.target.value })} /></label>
        <button className="button-primary" type="submit">Apply filters</button>
      </form>
      {loading ? <LoadingState label="Loading audit logs…" /> : error ? <div className="notice notice-error" role="alert"><strong>Could not load audit logs.</strong> {error}{(error.includes("sign in") || error.includes("Administrator access")) && <span> Check your session and administrator access.</span>}</div> : !data || data.content.length === 0 ? <EmptyState message="No audit entries match these filters." /> : <>
        <div className="table-scroll"><table><thead><tr><th>Time</th><th>Action</th><th>Actor</th><th>Target</th><th>Outcome</th></tr></thead><tbody>
          {data.content.map((entry) => <tr key={entry.id}><td>{formatDate(entry.occurredAt)}</td><td><strong>{entry.action}</strong></td><td>{entry.actorEmail ?? "System"}</td><td><span>{entry.targetType}</span>{entry.targetId && <small className="audit-target-id">{entry.targetId}</small>}</td><td><StatusBadge status={entry.outcome} /></td></tr>)}
        </tbody></table></div>
        <div className="admin-pagination"><span>Page {page + 1}{data.totalPages > 0 ? ` of ${data.totalPages}` : ""}</span><div><button className="button-quiet" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button><button className="button-quiet" disabled={!data.hasNext} onClick={() => setPage(page + 1)}>Next</button></div></div>
      </>}
    </section>
  </div>;
}

function AccessError({ title, detail, login, retry }: { title: string; detail: string; login?: boolean; retry?: () => void }) {
  return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Audit logs" /><div className="state-card error-state" role="alert"><strong>{title}</strong><span>{detail}</span>{login && <Link className="text-link" href="/login">Go to sign in</Link>}{retry && <button className="text-button" onClick={retry}>Retry</button>}</div></div>;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Not available" : date.toLocaleString();
}

function messageOf(error: unknown) {
  if (error instanceof Error) {
    const status = (error as ApiError).status;
    if (status === 401) return "Your session is no longer authorized. Sign in again.";
    if (status === 403) return "Administrator access is required for this data.";
    return error.message;
  }
  return "The request could not be completed.";
}
