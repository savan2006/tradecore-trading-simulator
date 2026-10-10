"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { api, type AdminRiskLimit, type AdminRiskLimitRequest, type ApiError, type Instrument } from "@/lib/api";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";

const LIMIT_TYPES = ["TRADING_DISABLED", "INSTRUMENT_BLOCKED", "MAX_ORDER_QUANTITY", "MAX_ORDER_AMOUNT", "MAX_ORDER_VALUE"];
type FormState = { scope: "GLOBAL" | "ACCOUNT" | "INSTRUMENT"; limitType: string; accountId: string; symbol: string; configuredValue: string; enabled: boolean; effectiveFrom: string; effectiveUntil: string };
const EMPTY_FORM: FormState = { scope: "ACCOUNT", limitType: "MAX_ORDER_AMOUNT", accountId: "", symbol: "", configuredValue: "10000", enabled: true, effectiveFrom: "", effectiveUntil: "" };

export default function AdminRiskLimitsPage() {
  const { session, adminAccess, refreshAdminAccess } = useAuth();
  const [limits, setLimits] = useState<AdminRiskLimit[]>([]);
  const [instruments, setInstruments] = useState<Instrument[]>([]);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const load = useCallback(async (signal: AbortSignal) => {
    if (!session) return;
    setLoading(true);
    setError(null);
    try {
      const [items, supported] = await Promise.all([
        api.adminRiskLimits(session.basicCredential, signal),
        api.instruments(session.basicCredential, signal),
      ]);
      setLimits(items);
      setInstruments(supported.filter((instrument) => instrument.tradable));
    } catch (reason) {
      if (!signal.aborted) setError(messageOf(reason));
    } finally {
      if (!signal.aborted) setLoading(false);
    }
  }, [session]);

  useEffect(() => {
    if (!session || adminAccess !== "allowed") return;
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [session, adminAccess, reloadKey, load]);

  if (!session) return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Risk limits" /><LoginRequired /></div>;
  if (adminAccess === "checking" || adminAccess === "signed-out") return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Risk limits" /><LoadingState label="Checking administrator access…" /></div>;
  if (adminAccess === "forbidden") return <AccessError title="Administrator access required" detail="Your account cannot configure risk limits." />;
  if (adminAccess === "unauthorized") return <AccessError title="Session expired" detail="Sign in again to manage risk limits." login />;
  if (adminAccess === "unavailable") return <AccessError title="Could not verify administrator access" detail="The administrator access check failed." retry={refreshAdminAccess} />;

  function edit(limit: AdminRiskLimit) {
    setEditingId(limit.id);
    setForm({ scope: limit.scope as FormState["scope"], limitType: limit.limitType, accountId: limit.accountId ?? "", symbol: limit.symbol ?? "", configuredValue: String(limit.configuredValue), enabled: limit.enabled, effectiveFrom: toLocalInput(limit.effectiveFrom), effectiveUntil: toLocalInput(limit.effectiveUntil) });
    setNotice(null);
  }

  function cancelEdit() { setEditingId(null); setForm(EMPTY_FORM); setError(null); }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session) return;
    setSaving(true); setError(null); setNotice(null);
    try {
      const value = Number(form.configuredValue);
      if (!Number.isFinite(value) || value <= 0) throw new Error("Configured value must be a positive number.");
      const request: AdminRiskLimitRequest = {
        scope: form.scope,
        limitType: form.limitType,
        accountId: form.scope === "ACCOUNT" ? form.accountId.trim() : null,
        exchange: form.scope === "INSTRUMENT" ? "NSE" : null,
        symbol: form.scope === "INSTRUMENT" ? form.symbol : null,
        configuredValue: value,
        enabled: form.enabled,
        effectiveFrom: form.effectiveFrom ? new Date(`${form.effectiveFrom}+05:30`).toISOString() : null,
        effectiveUntil: form.effectiveUntil ? new Date(`${form.effectiveUntil}+05:30`).toISOString() : null,
      };
      if (form.scope === "ACCOUNT" && !request.accountId) throw new Error("Enter the trading account UUID.");
      if (form.scope === "INSTRUMENT" && !request.symbol) throw new Error("Select a supported instrument.");
      if (editingId) await api.updateAdminRiskLimit(session.basicCredential, editingId, request);
      else await api.createAdminRiskLimit(session.basicCredential, request);
      setNotice(editingId ? "Risk limit updated." : "Risk limit created.");
      cancelEdit(); setReloadKey((key) => key + 1);
    } catch (reason) { setError(messageOf(reason)); }
    finally { setSaving(false); }
  }

  async function setActive(limit: AdminRiskLimit, active: boolean) {
    if (!session || pendingAction) return;
    setPendingAction(limit.id);
    setError(null); setNotice(null);
    try {
      if (active) await api.activateAdminRiskLimit(session.basicCredential, limit.id);
      else await api.deactivateAdminRiskLimit(session.basicCredential, limit.id);
      setNotice(active ? "Risk limit activated." : "Risk limit deactivated.");
      setReloadKey((key) => key + 1);
    } catch (reason) { setError(messageOf(reason)); }
    finally { setPendingAction(null); }
  }

  return <div className="content-wrap admin-page">
    <PageHeading eyebrow="Operations" title="Risk limits" description="Configure account and instrument restrictions used by the existing order checks." />
    <div className="admin-subnav"><Link className="text-link" href="/admin">Admin overview</Link><span aria-current="page">Risk limits</span><Link className="text-link" href="/admin/audit-logs">Audit logs</Link></div>
    <div className="notice">Only the existing limit types are available. Effective dates are optional; values must be positive.</div>
    {error && <div className="notice notice-error" role="alert">{error}</div>}
    {notice && <div className="notice" role="status">{notice}</div>}
    <section className="panel admin-panel">
      <div className="panel-heading"><div><p className="eyebrow">Configuration</p><h2>{editingId ? "Update risk limit" : "Create risk limit"}</h2></div></div>
      <form className="strategy-form-grid" onSubmit={submit}>
        <label>Scope<select value={form.scope} onChange={(event) => setForm({ ...form, scope: event.target.value as FormState["scope"], accountId: "", symbol: "" })}><option value="ACCOUNT">Trading account</option><option value="INSTRUMENT">Instrument</option><option value="GLOBAL">All accounts</option></select></label>
        {form.scope === "ACCOUNT" && <label>Trading account UUID<input value={form.accountId} onChange={(event) => setForm({ ...form, accountId: event.target.value })} required placeholder="Account UUID" /></label>}
        {form.scope === "INSTRUMENT" && <label>Instrument<select value={form.symbol} onChange={(event) => setForm({ ...form, symbol: event.target.value })} required><option value="">Select instrument</option>{instruments.map((item) => <option key={item.symbol} value={item.symbol}>{item.symbol} · {item.companyName}</option>)}</select></label>}
        <label>Limit type<select value={form.limitType} onChange={(event) => setForm({ ...form, limitType: event.target.value })}>{LIMIT_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}</select></label>
        <label>Configured value<input type="number" min="0.0001" step="0.0001" value={form.configuredValue} onChange={(event) => setForm({ ...form, configuredValue: event.target.value })} required /></label>
        <label>Effective from<input type="datetime-local" value={form.effectiveFrom} onChange={(event) => setForm({ ...form, effectiveFrom: event.target.value })} /></label>
        <label>Effective until<input type="datetime-local" value={form.effectiveUntil} onChange={(event) => setForm({ ...form, effectiveUntil: event.target.value })} /></label>
        <label>Enabled<select value={String(form.enabled)} onChange={(event) => setForm({ ...form, enabled: event.target.value === "true" })}><option value="true">Enabled</option><option value="false">Disabled</option></select></label>
        <div className="form-actions"><button className="button-primary" type="submit" disabled={saving}>{saving ? "Saving…" : editingId ? "Save changes" : "Create limit"}</button>{editingId && <button className="button-quiet" type="button" onClick={cancelEdit}>Cancel</button>}</div>
      </form>
    </section>
    <section className="panel admin-panel">
      <div className="panel-heading"><div><p className="eyebrow">Existing configuration</p><h2>Configured limits</h2></div><span className="muted admin-total">{limits.length} total</span></div>
      {loading ? <LoadingState label="Loading risk limits…" /> : error && limits.length === 0 ? <ErrorState message={error} /> : limits.length === 0 ? <EmptyState message="No risk limits are configured." /> : <div className="table-scroll"><table><thead><tr><th>Scope / target</th><th>Limit</th><th>Value</th><th>Status</th><th>Effective period</th><th>Actions</th></tr></thead><tbody>{limits.map((limit) => <tr key={limit.id}>
        <td><strong>{limit.scope}</strong><small>{limit.accountEmail ?? limit.symbol ?? "All accounts"}</small>{limit.accountId && <small className="audit-target-id">{limit.accountId}</small>}</td>
        <td>{limit.limitType}</td><td>{limit.configuredValue.toLocaleString("en-IN")}</td>
        <td><StatusBadge status={limit.enabled ? limit.effectiveNow ? "ACTIVE" : "ENABLED / SCHEDULED" : "DISABLED"} /></td>
        <td>{formatDate(limit.effectiveFrom)} – {formatDate(limit.effectiveUntil)}</td>
        <td><div className="admin-row-actions"><button className="text-button" disabled={saving || Boolean(pendingAction)} onClick={() => edit(limit)}>Edit</button><button className="text-button" disabled={saving || Boolean(pendingAction)} onClick={() => void setActive(limit, !limit.enabled)}>{pendingAction === limit.id ? "Saving…" : limit.enabled ? "Deactivate" : "Activate"}</button></div></td>
      </tr>)}</tbody></table></div>}
    </section>
  </div>;
}

function AccessError({ title, detail, login, retry }: { title: string; detail: string; login?: boolean; retry?: () => void }) {
  return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Risk limits" /><div className="state-card error-state" role="alert"><strong>{title}</strong><span>{detail}</span>{login && <Link className="text-link" href="/login">Go to sign in</Link>}{retry && <button className="text-button" onClick={retry}>Retry</button>}</div></div>;
}

function toLocalInput(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const ist = new Date(date.getTime() + 330 * 60_000);
  return ist.toISOString().slice(0, 16);
}

function formatDate(value: string | null) {
  if (!value) return "Unbounded";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Invalid date" : date.toLocaleString("en-IN", { timeZone: "Asia/Kolkata" });
}

function messageOf(reason: unknown) {
  if (reason instanceof Error) {
    const status = (reason as ApiError).status;
    if (status === 401) return "Your session expired. Sign in again.";
    if (status === 403) return "Administrator access is required.";
    return reason.message;
  }
  return "The request could not be completed.";
}
