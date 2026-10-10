"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import {
  api,
  type AdminMarketStatus,
  type AdminOrderPage,
  type AdminUserPage,
  type ApiError,
} from "@/lib/api";
import { EmptyState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";

const PAGE_SIZE = 20;
const ORDER_STATES = ["CREATED", "VALIDATING", "ACCEPTED", "PENDING", "PARTIALLY_FILLED", "FILLED", "CANCELLED", "REJECTED", "FAILED"];

export default function AdminPage() {
  const { session, adminAccess, adminOverview, refreshAdminAccess } = useAuth();
  const [users, setUsers] = useState<AdminUserPage | null>(null);
  const [usersLoading, setUsersLoading] = useState(true);
  const [usersError, setUsersError] = useState<string | null>(null);
  const [userPage, setUserPage] = useState(0);
  const [userSearch, setUserSearch] = useState("");
  const [appliedUserSearch, setAppliedUserSearch] = useState("");

  const [orders, setOrders] = useState<AdminOrderPage | null>(null);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [ordersError, setOrdersError] = useState<string | null>(null);
  const [orderPage, setOrderPage] = useState(0);
  const [orderFilters, setOrderFilters] = useState({ status: "", symbol: "", tradingMode: "", from: "", to: "" });
  const [draftOrderFilters, setDraftOrderFilters] = useState(orderFilters);

  const [marketStatus, setMarketStatus] = useState<AdminMarketStatus | null>(null);
  const [marketLoading, setMarketLoading] = useState(true);
  const [marketError, setMarketError] = useState<string | null>(null);

  useEffect(() => {
    if (!session || adminAccess !== "allowed") return;
    const controller = new AbortController();
    setUsersLoading(true);
    setUsersError(null);
    api.adminUsers(session.basicCredential, { page: userPage, size: PAGE_SIZE, search: appliedUserSearch }, controller.signal)
      .then(setUsers)
      .catch((error: unknown) => { if (!controller.signal.aborted) setUsersError(messageOf(error)); })
      .finally(() => { if (!controller.signal.aborted) setUsersLoading(false); });
    return () => controller.abort();
  }, [session, adminAccess, userPage, appliedUserSearch]);

  useEffect(() => {
    if (!session || adminAccess !== "allowed") return;
    const controller = new AbortController();
    setOrdersLoading(true);
    setOrdersError(null);
    api.adminOrders(session.basicCredential, { page: orderPage, size: PAGE_SIZE, ...orderFilters }, controller.signal)
      .then(setOrders)
      .catch((error: unknown) => { if (!controller.signal.aborted) setOrdersError(messageOf(error)); })
      .finally(() => { if (!controller.signal.aborted) setOrdersLoading(false); });
    return () => controller.abort();
  }, [session, adminAccess, orderPage, orderFilters]);

  useEffect(() => {
    if (!session || adminAccess !== "allowed") return;
    const controller = new AbortController();
    setMarketLoading(true);
    setMarketError(null);
    api.adminMarketStatus(session.basicCredential, controller.signal)
      .then(setMarketStatus)
      .catch((error: unknown) => { if (!controller.signal.aborted) setMarketError(messageOf(error)); })
      .finally(() => { if (!controller.signal.aborted) setMarketLoading(false); });
    return () => controller.abort();
  }, [session, adminAccess]);

  if (!session) return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Admin" /><LoginRequired /></div>;
  if (adminAccess === "checking" || adminAccess === "signed-out") {
    return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Admin" /><LoadingState label="Checking administrator access…" /></div>;
  }
  if (adminAccess === "forbidden") {
    return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Admin" /><div className="state-card error-state" role="alert"><strong>Administrator access required</strong><span>Your account does not have access to operational controls.</span></div></div>;
  }
  if (adminAccess === "unauthorized") {
    return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Admin" /><div className="state-card error-state" role="alert"><strong>Session expired</strong><span>Sign in again to check administrator access.</span><Link className="text-link" href="/login">Go to sign in</Link></div></div>;
  }
  if (adminAccess === "unavailable") {
    return <div className="content-wrap"><PageHeading eyebrow="Operations" title="Admin" /><div className="state-card error-state" role="alert"><strong>Could not verify administrator access</strong><span>The admin overview request failed. {" "}<button className="text-button" onClick={refreshAdminAccess}>Retry</button></span></div></div>;
  }

  const applyUserSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setUserPage(0);
    setAppliedUserSearch(userSearch.trim());
  };
  const applyOrderFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setOrderPage(0);
    setOrderFilters({ ...draftOrderFilters, symbol: draftOrderFilters.symbol.trim().toUpperCase() });
  };

  return <div className="content-wrap admin-page">
    <PageHeading eyebrow="Operations" title="Admin overview" description="Read-only service, user, and order monitoring." />

    {!adminOverview ? <div className="notice notice-error" role="alert">Admin overview is unavailable.</div> : <>
      <section className="metric-grid admin-metrics" aria-label="Operational overview">
        <Metric label="Users" value={adminOverview.totalUsers} />
        <Metric label="Active trading accounts" value={adminOverview.activeTradingAccounts} />
        <Metric label="Pending orders" value={adminOverview.pendingOrders} />
        <Metric label="Filled orders" value={adminOverview.filledOrders} />
        <Metric label="Cancelled orders" value={adminOverview.cancelledOrders} />
        <Metric label="Open positions" value={adminOverview.openPositions} />
        <Metric label="Unread notifications" value={adminOverview.unreadNotifications} />
        <Metric label="Supported instruments" value={adminOverview.supportedInstruments} />
      </section>
      <section className="panel admin-refresh-summary">
        <div><span className="metric-label">Latest market-data refresh</span><strong><StatusBadge status={adminOverview.latestMarketDataRefreshStatus} /></strong></div>
        <div><span className="metric-label">Last successful quote refresh</span><strong>{formatDate(adminOverview.latestMarketDataRefreshAt)}</strong></div>
      </section>
    </>}

    <section className="panel admin-panel">
      <div className="panel-heading"><div><p className="eyebrow">Accounts</p><h2>Users</h2></div>{users && <span className="muted admin-total">{users.totalElements} total</span>}</div>
      <form className="admin-filter-form" onSubmit={applyUserSearch}>
        <label>Search email or display name<input value={userSearch} maxLength={120} onChange={(event) => setUserSearch(event.target.value)} placeholder="Search users" /></label>
        <button className="button-primary" type="submit">Search</button>
      </form>
      {usersLoading ? <LoadingState label="Loading users…" /> : usersError ? <PanelError message={usersError} /> : !users || users.content.length === 0 ? <EmptyState message={appliedUserSearch ? "No users match this search." : "No users are available."} /> : <>
        <div className="table-scroll"><table><thead><tr><th>Email</th><th>Display name</th><th>Role</th><th>Status</th><th>Created</th></tr></thead><tbody>
          {users.content.map((user) => <tr key={user.id}><td>{user.email}</td><td>{user.displayName}</td><td><StatusBadge status={user.role} /></td><td><StatusBadge status={user.status} /></td><td>{formatDate(user.createdAt)}</td></tr>)}
        </tbody></table></div>
        <Pagination page={users.page} totalPages={users.totalPages} hasNext={users.hasNext} onChange={setUserPage} />
      </>}
    </section>

    <section className="panel admin-panel">
      <div className="panel-heading"><div><p className="eyebrow">Activity</p><h2>Orders</h2></div>{orders && <span className="muted admin-total">{orders.totalElements} matching</span>}</div>
      <form className="admin-order-filters" onSubmit={applyOrderFilters}>
        <label>Status<select value={draftOrderFilters.status} onChange={(event) => setDraftOrderFilters({ ...draftOrderFilters, status: event.target.value })}><option value="">All statuses</option>{ORDER_STATES.map((status) => <option key={status} value={status}>{status}</option>)}</select></label>
        <label>Symbol<input value={draftOrderFilters.symbol} maxLength={32} onChange={(event) => setDraftOrderFilters({ ...draftOrderFilters, symbol: event.target.value })} placeholder="Any symbol" /></label>
        <label>Trading mode<select value={draftOrderFilters.tradingMode} onChange={(event) => setDraftOrderFilters({ ...draftOrderFilters, tradingMode: event.target.value })}><option value="">All modes</option><option value="DELIVERY">Delivery</option><option value="INTRADAY">Intraday</option></select></label>
        <label>From<input type="date" value={draftOrderFilters.from} onChange={(event) => setDraftOrderFilters({ ...draftOrderFilters, from: event.target.value })} /></label>
        <label>To<input type="date" value={draftOrderFilters.to} onChange={(event) => setDraftOrderFilters({ ...draftOrderFilters, to: event.target.value })} /></label>
        <button className="button-primary" type="submit">Apply filters</button>
      </form>
      {ordersLoading ? <LoadingState label="Loading orders…" /> : ordersError ? <PanelError message={ordersError} /> : !orders || orders.content.length === 0 ? <EmptyState message="No orders match these filters." /> : <>
        <div className="table-scroll"><table><thead><tr><th>Symbol</th><th>Side</th><th>Type</th><th>Mode</th><th>Requested</th><th>Executed</th><th>Remaining</th><th>Status</th><th>Created</th></tr></thead><tbody>
          {orders.content.map((order) => <tr key={order.orderId}><td><strong>{order.symbol}</strong><small>{order.exchange}</small></td><td>{order.side}</td><td>{order.orderType}</td><td>{order.tradingMode}</td><td>{order.requestedQuantity}</td><td>{order.executedQuantity}</td><td>{order.remainingQuantity}</td><td><StatusBadge status={order.status} /></td><td>{formatDate(order.createdAt)}</td></tr>)}
        </tbody></table></div>
        <Pagination page={orders.page} totalPages={orders.totalPages} hasNext={orders.hasNext} onChange={setOrderPage} />
      </>}
    </section>

    <section className="panel admin-panel">
      <div className="panel-heading"><div><p className="eyebrow">Schedulers</p><h2>Market-data status</h2></div></div>
      {marketLoading ? <LoadingState label="Loading scheduler status…" /> : marketError ? <PanelError message={marketError} /> : !marketStatus ? <EmptyState message="Scheduler status is unavailable." /> : <>
        <div className="table-scroll"><table><thead><tr><th>Job</th><th>Outcome</th><th>Started</th><th>Finished</th><th>Duration</th><th>Processed</th><th>Updated</th><th>Skipped</th><th>Failed</th><th>Last error</th></tr></thead><tbody>
          {marketStatus.jobs.map((job) => <tr key={job.job}><td>{job.job.replaceAll("_", " ")}</td><td><StatusBadge status={job.outcome} /></td><td>{formatDate(job.startedAt)}</td><td>{formatDate(job.finishedAt)}</td><td>{job.durationMs == null ? "—" : `${job.durationMs} ms`}</td><td>{job.processed}</td><td>{job.updated}</td><td>{job.skipped}</td><td>{job.failed}</td><td>{job.lastError ?? "—"}</td></tr>)}
        </tbody></table></div>
        <div className="admin-status-card"><h3>Persisted quotes</h3><StatusBadge status={marketStatus.latestPersistedQuoteAt ? "AVAILABLE" : "UNAVAILABLE"} /><p>Latest persisted quote: {formatDate(marketStatus.latestPersistedQuoteAt)}</p></div>
      </>}
    </section>
  </div>;
}

function Metric({ label, value }: { label: string; value: number }) {
  return <article className="metric-card"><span className="metric-label">{label}</span><strong>{value.toLocaleString("en-IN")}</strong></article>;
}

function PanelError({ message }: { message: string }) {
  return <div className="notice notice-error" role="alert">Could not load this section. {message} Check administrator access and backend availability.</div>;
}

function Pagination({ page, totalPages, hasNext, onChange }: { page: number; totalPages: number; hasNext: boolean; onChange: (page: number) => void }) {
  return <div className="admin-pagination"><span>Page {page + 1}{totalPages > 0 ? ` of ${totalPages}` : ""}</span><div><button className="button-quiet" disabled={page === 0} onClick={() => onChange(page - 1)}>Previous</button><button className="button-quiet" disabled={!hasNext} onClick={() => onChange(page + 1)}>Next</button></div></div>;
}

function formatDate(value: string | null) {
  if (!value) return "Not available";
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
