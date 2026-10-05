"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, formatMoney, type ApiError, type LearningProfile, type PriceAlert, type Watchlist } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";

export default function WatchlistsPage() {
  const { session } = useAuth();
  const [watchlists, setWatchlists] = useState<Watchlist[] | null>(null);
  const [alerts, setAlerts] = useState<PriceAlert[] | null>(null);
  const [companies, setCompanies] = useState<LearningProfile[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState<{ watchlists?: string; alerts?: string; companies?: string }>({});
  const [refreshKey, setRefreshKey] = useState(0);
  const [pending, setPending] = useState<string | null>(null);
  const pendingRef = useRef(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionMessage, setActionMessage] = useState<string | null>(null);

  const [newListName, setNewListName] = useState("");
  const [addSymbols, setAddSymbols] = useState<Record<string, string>>({});
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingName, setEditingName] = useState("");
  const [alertWatchlistId, setAlertWatchlistId] = useState("");
  const [alertInstrumentId, setAlertInstrumentId] = useState("");
  const [alertCondition, setAlertCondition] = useState<"ABOVE" | "BELOW">("ABOVE");
  const [alertTarget, setAlertTarget] = useState("");

  useEffect(() => {
    if (!session) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setErrors({});
    Promise.allSettled([
      api.watchlists(session.basicCredential, controller.signal),
      api.priceAlerts(session.basicCredential, controller.signal),
      api.learningProfiles(session.basicCredential, controller.signal),
    ]).then(([listsResult, alertsResult, companiesResult]) => {
      if (controller.signal.aborted) return;
      const nextErrors: typeof errors = {};
      if (listsResult.status === "fulfilled") setWatchlists(listsResult.value);
      else nextErrors.watchlists = messageOf(listsResult.reason);
      if (alertsResult.status === "fulfilled") setAlerts(alertsResult.value);
      else nextErrors.alerts = messageOf(alertsResult.reason);
      if (companiesResult.status === "fulfilled") setCompanies(companiesResult.value);
      else nextErrors.companies = messageOf(companiesResult.reason);
      setErrors(nextErrors);
      setLoading(false);
    });
    return () => controller.abort();
  }, [session, refreshKey]);

  const allSymbols = useMemo(() => companies ?? [], [companies]);
  const selectedAlertList = watchlists?.find((list) => list.id === alertWatchlistId);
  const alertSymbols = useMemo(() => selectedAlertList?.items.map((item) => {
    const profile = allSymbols.find((company) => company.symbol === item.symbol && company.exchange === item.exchange);
    return profile ? { id: profile.instrumentId, symbol: item.symbol, exchange: item.exchange } : null;
  }).filter((item): item is { id: string; symbol: string; exchange: string } => item !== null) ?? [], [allSymbols, selectedAlertList]);

  useEffect(() => {
    if (!watchlists?.length) {
      setAlertWatchlistId("");
      setAlertInstrumentId("");
      return;
    }
    if (!watchlists.some((list) => list.id === alertWatchlistId)) setAlertWatchlistId(watchlists[0].id);
  }, [watchlists, alertWatchlistId]);

  useEffect(() => {
    if (!alertSymbols.some((symbol) => symbol.id === alertInstrumentId)) setAlertInstrumentId(alertSymbols[0]?.id ?? "");
  }, [alertSymbols, alertInstrumentId]);

  const refresh = useCallback(() => setRefreshKey((value) => value + 1), []);

  async function runAction(key: string, success: string, action: () => Promise<unknown>) {
    if (!session || pendingRef.current) return false;
    pendingRef.current = true;
    setPending(key);
    setActionError(null);
    setActionMessage(null);
    try {
      await action();
      setActionMessage(success);
      refresh();
      return true;
    } catch (error) {
      setActionError(messageOf(error));
      return false;
    } finally {
      pendingRef.current = false;
      setPending(null);
    }
  }

  function createWatchlist(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = newListName.trim();
    if (!name || !session) return;
    void runAction("create-list", "Watchlist created.", async () => {
      await api.createWatchlist(session.basicCredential, name);
      setNewListName("");
    });
  }

  function renameWatchlist(event: React.FormEvent<HTMLFormElement>, id: string) {
    event.preventDefault();
    const name = editingName.trim();
    if (!name || !session) return;
    void runAction(`rename-${id}`, "Watchlist renamed.", () => api.renameWatchlist(session.basicCredential, id, name));
    setEditingId(null);
  }

  function addSymbol(event: React.FormEvent<HTMLFormElement>, list: Watchlist) {
    event.preventDefault();
    const symbol = allSymbols.find((company) => company.instrumentId === addSymbols[list.id]);
    if (!symbol || !session) return;
    void runAction(`add-${list.id}`, `${symbol.symbol} added to ${list.name}.`, () =>
      api.addWatchlistItem(session.basicCredential, list.id, symbol.exchange, symbol.symbol));
  }

  function createAlert(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const targetPrice = Number(alertTarget);
    if (!session || !alertWatchlistId || !alertInstrumentId || !Number.isFinite(targetPrice) || targetPrice <= 0) return;
    void runAction("create-alert", "Price alert created.", () => api.createPriceAlert(session.basicCredential, {
      watchlistId: alertWatchlistId,
      instrumentId: alertInstrumentId,
      condition: alertCondition,
      targetPrice,
    })).then((created) => { if (created) setAlertTarget(""); });
  }

  if (!session) return <div className="content-wrap"><PageHeading eyebrow="Saved research" title="Watchlists & alerts" /><LoginRequired /></div>;

  return <div className="content-wrap">
    <PageHeading eyebrow="Saved research" title="Watchlists & alerts" description="Organize supported companies and set price levels using persisted backend quotes." />
    {(actionError || actionMessage) && <div className={`notice ${actionError ? "notice-error" : "notice-muted"}`} role={actionError ? "alert" : "status"}>{actionError ?? actionMessage}</div>}

    <section className="panel watchlist-create-panel">
      <div className="panel-heading"><div><p className="eyebrow">Saved research</p><h2>Create a watchlist</h2></div></div>
      <form className="watchlist-form" onSubmit={createWatchlist}>
        <label>Watchlist name<input value={newListName} onChange={(event) => setNewListName(event.target.value)} maxLength={80} required placeholder="e.g. Long-term learning" /></label>
        <button className="button-primary" type="submit" disabled={Boolean(pending) || !newListName.trim()}>{pending === "create-list" ? "Creating…" : "Create watchlist"}</button>
      </form>
    </section>

    <section className="watchlist-stack">
      <div className="section-heading"><div><p className="eyebrow">Your lists</p><h2>Watchlists</h2></div>{watchlists && <span className="muted">{watchlists.length} total</span>}</div>
      {loading && !watchlists ? <LoadingState label="Loading watchlists…" /> : errors.watchlists && !watchlists ? <ErrorState message={errors.watchlists} /> : !watchlists?.length ? <EmptyState message="No watchlists yet. Create one above to start saving companies." /> : watchlists.map((list) => <article className="panel" key={list.id}>
        <div className="panel-heading watchlist-panel-heading">
          <div><p className="eyebrow">Watchlist</p>{editingId === list.id ? <form className="rename-form" onSubmit={(event) => renameWatchlist(event, list.id)}><input aria-label="New watchlist name" value={editingName} onChange={(event) => setEditingName(event.target.value)} maxLength={80} required autoFocus /><button className="secondary-button" type="submit" disabled={Boolean(pending) || !editingName.trim()}>{pending === `rename-${list.id}` ? "Saving…" : "Save"}</button><button className="text-button" type="button" onClick={() => setEditingId(null)}>Cancel</button></form> : <h2>{list.name}</h2>}</div>
          <div className="watchlist-actions"><span className="muted">{list.items.length} companies</span><button className="text-button" type="button" disabled={Boolean(pending)} onClick={() => { setEditingId(list.id); setEditingName(list.name); }}>Rename</button><button className="danger-link" type="button" disabled={Boolean(pending)} onClick={() => void runAction(`delete-${list.id}`, "Watchlist deleted.", () => api.deleteWatchlist(session.basicCredential, list.id))}>{pending === `delete-${list.id}` ? "Deleting…" : "Delete"}</button></div>
        </div>
        <form className="watchlist-form add-symbol-form" onSubmit={(event) => addSymbol(event, list)}>
          <label>Add a supported company<select value={addSymbols[list.id] ?? ""} onChange={(event) => setAddSymbols((current) => ({ ...current, [list.id]: event.target.value }))} disabled={!allSymbols.length || Boolean(pending)} required>
            <option value="">Select a company</option>{allSymbols.map((company) => <option key={company.instrumentId} value={company.instrumentId}>{company.symbol} · {company.companyName}</option>)}
          </select></label>
          <button className="secondary-button" type="submit" disabled={!addSymbols[list.id] || Boolean(pending) || Boolean(errors.companies)}>{pending === `add-${list.id}` ? "Adding…" : "Add company"}</button>
        </form>
        {errors.companies && <p className="form-error" role="alert">Supported companies could not be loaded. {errors.companies}</p>}
        {loading && !watchlists ? <LoadingState /> : list.items.length ? <div className="table-scroll"><table><thead><tr><th>Company</th><th>Last price</th><th>Quote freshness</th><th>Status</th><th>Actions</th></tr></thead><tbody>{list.items.map((item) => {
          const quote = item.quote;
          const quoteStatus = quote?.dataStatus ?? "UNAVAILABLE";
          return <tr key={item.id}>
            <td><Link className="text-link" href={`/companies/${encodeURIComponent(item.symbol)}`}>{item.symbol}</Link><small>{item.companyName} · {item.exchange}</small></td>
            <td>{formatMoney(quote?.lastPrice)}</td>
            <td>{formatFreshness(quote?.freshnessAgeSeconds)}</td>
            <td><StatusBadge status={quoteStatus} />{quoteStatus !== "LIVE" && <small>{quoteStatus === "STALE" ? "Persisted quote may be out of date" : "No usable quote available"}</small>}</td>
            <td><button className="danger-link" type="button" disabled={Boolean(pending)} onClick={() => void runAction(`remove-${list.id}-${item.symbol}`, `${item.symbol} removed from ${list.name}.`, () => api.removeWatchlistItem(session.basicCredential, list.id, item.symbol))}>{pending === `remove-${list.id}-${item.symbol}` ? "Removing…" : "Remove"}</button></td>
          </tr>;
        })}</tbody></table></div> : <EmptyState message="This watchlist is empty. Add a supported company above." />}
      </article>)}
      {errors.watchlists && watchlists && <p className="form-error" role="alert">Watchlists could not be refreshed. {errors.watchlists}</p>}
    </section>

    <section className="panel alerts-panel">
      <div className="panel-heading"><div><p className="eyebrow">Price notifications</p><h2>Price alerts</h2></div>{alerts && <span className="muted">{alerts.length} total</span>}</div>
      <p className="muted alert-explainer">Alerts are evaluated against persisted quotes. They may trigger after the next available quote update.</p>
      <form className="alert-form" onSubmit={createAlert}>
        <label>Watchlist<select value={alertWatchlistId} onChange={(event) => setAlertWatchlistId(event.target.value)} disabled={!watchlists?.length || Boolean(pending)} required><option value="">Select a watchlist</option>{watchlists?.map((list) => <option key={list.id} value={list.id}>{list.name}</option>)}</select></label>
        <label>Company<select value={alertInstrumentId} onChange={(event) => setAlertInstrumentId(event.target.value)} disabled={!alertSymbols.length || Boolean(pending)} required><option value="">Select a company in this watchlist</option>{alertSymbols.map((item) => <option key={item.id} value={item.id}>{item.symbol} · {item.exchange}</option>)}</select></label>
        <label>Condition<select value={alertCondition} onChange={(event) => setAlertCondition(event.target.value as "ABOVE" | "BELOW")} disabled={Boolean(pending)}><option value="ABOVE">Rises above</option><option value="BELOW">Falls below</option></select></label>
        <label>Target price<input type="number" min="0.01" step="0.01" value={alertTarget} onChange={(event) => setAlertTarget(event.target.value)} required placeholder="e.g. 1250.00" disabled={Boolean(pending)} /></label>
        <button className="button-primary" type="submit" disabled={Boolean(pending) || !alertWatchlistId || !alertInstrumentId || !alertTarget || Number(alertTarget) <= 0}>{pending === "create-alert" ? "Creating…" : "Create alert"}</button>
      </form>
      {errors.companies && <p className="form-error" role="alert">Company identifiers could not be loaded for alerts. {errors.companies}</p>}
      {loading && !alerts ? <LoadingState label="Loading price alerts…" /> : errors.alerts && !alerts ? <ErrorState message={errors.alerts} /> : !alerts?.length ? <EmptyState message="No price alerts yet. Add a company to a watchlist to create one." /> : <div className="alert-list">{alerts.map((alert) => <div className="alert-row" key={alert.id}>
        <div><strong>{alert.symbol}</strong><small>{alert.exchange} · {watchlists?.find((list) => list.id === alert.watchlistId)?.name ?? "Watchlist"}</small></div>
        <span>{alert.condition === "ABOVE" ? "Above" : "Below"} {formatMoney(alert.targetPrice)}</span>
        <span>{alert.active ? <StatusBadge status="ACTIVE" /> : <StatusBadge status={alert.triggeredAt ? "TRIGGERED" : "INACTIVE"} />}</span>
        <button className="danger-link" type="button" disabled={Boolean(pending)} onClick={() => void runAction(`delete-alert-${alert.id}`, "Price alert deleted.", () => api.deletePriceAlert(session.basicCredential, alert.id))}>{pending === `delete-alert-${alert.id}` ? "Deleting…" : "Delete"}</button>
      </div>)}</div>}
      {errors.alerts && alerts && <p className="form-error" role="alert">Alerts could not be refreshed. {errors.alerts}</p>}
    </section>
  </div>;
}

function formatFreshness(seconds: number | null | undefined) {
  if (seconds == null) return "Freshness unavailable";
  if (seconds < 60) return `${Math.max(0, Math.floor(seconds))} sec ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours} hr${hours === 1 ? "" : "s"} ago`;
}

function messageOf(error: unknown) {
  const apiError = error as ApiError | null;
  if (apiError?.status === 401) return "Your session has expired. Please sign in again.";
  if (error instanceof Error) return error.message;
  return "The request could not be completed.";
}
