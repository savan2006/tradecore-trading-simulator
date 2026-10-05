"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, formatMoney, type ApiError, type Order, type OrderPage, type TradePage } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";

const PAGE_SIZE = 20;

export default function OrdersPage() {
  const { session } = useAuth();
  const [statusFilter, setStatusFilter] = useState("");
  const [symbolInput, setSymbolInput] = useState("");
  const [symbolFilter, setSymbolFilter] = useState("");
  const [page, setPage] = useState(0);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [orders, setOrders] = useState<OrderPage | null>(null);
  const [trades, setTrades] = useState<TradePage | null>(null);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [tradesLoading, setTradesLoading] = useState(true);
  const [ordersError, setOrdersError] = useState<string | null>(null);
  const [tradesError, setTradesError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<Order | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  const [actionMessage, setActionMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  useEffect(() => {
    if (!session) return;
    const controller = new AbortController();
    setOrdersLoading(true);
    setOrdersError(null);
    api.orders(session.basicCredential, { page, size: PAGE_SIZE, status: statusFilter, symbol: symbolFilter }, controller.signal)
      .then(setOrders)
      .catch((error: unknown) => { if (!controller.signal.aborted) setOrdersError(messageOf(error)); })
      .finally(() => { if (!controller.signal.aborted) setOrdersLoading(false); });
    return () => controller.abort();
  }, [session, page, statusFilter, symbolFilter, refreshVersion]);

  useEffect(() => {
    if (!session) return;
    const controller = new AbortController();
    setTradesLoading(true);
    setTradesError(null);
    api.trades(session.basicCredential, 0, 10, controller.signal)
      .then(setTrades)
      .catch((error: unknown) => { if (!controller.signal.aborted) setTradesError(messageOf(error)); })
      .finally(() => { if (!controller.signal.aborted) setTradesLoading(false); });
    return () => controller.abort();
  }, [session, refreshVersion]);

  useEffect(() => {
    if (!session || !selectedId) {
      setDetail(null);
      setDetailError(null);
      return;
    }
    const controller = new AbortController();
    setDetailLoading(true);
    setDetailError(null);
    api.order(session.basicCredential, selectedId, controller.signal)
      .then(setDetail)
      .catch((error: unknown) => { if (!controller.signal.aborted) setDetailError(messageOf(error)); })
      .finally(() => { if (!controller.signal.aborted) setDetailLoading(false); });
    return () => controller.abort();
  }, [session, selectedId, refreshVersion]);

  const applyFilters = useCallback((event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setPage(0);
    setSymbolFilter(symbolInput.trim());
  }, [symbolInput]);

  async function cancelOrder(orderId: string) {
    if (!session || cancellingId) return;
    setCancellingId(orderId);
    setActionMessage(null);
    try {
      const result = await api.cancelOrder(session.basicCredential, orderId);
      setActionMessage({ kind: "success", text: `Order cancelled. Status: ${result.status}. Reserved funds or quantity released: ${formatMoney(result.releasedFunds)} / ${result.releasedSellQuantity}.` });
      setDetail((current) => current?.orderId === orderId ? { ...current, status: result.status, updatedAt: result.updatedAt } : current);
      setRefreshVersion((value) => value + 1);
    } catch (error) {
      setActionMessage({ kind: "error", text: messageOf(error) });
    } finally {
      setCancellingId(null);
    }
  }

  if (!session) return <div className="content-wrap"><PageHeading eyebrow="Account activity" title="Orders & trades" description="Your order and execution history." /><LoginRequired /></div>;

  const lastPage = Math.max(0, (orders?.totalPages ?? 1) - 1);
  return <div className="content-wrap">
    <PageHeading eyebrow="Account activity" title="Orders & trades" description="Review pending orders, past order states, and completed virtual executions." />
    {actionMessage && <div className={`notice order-action-message ${actionMessage.kind === "error" ? "notice-stale" : "notice-success"}`} role={actionMessage.kind === "error" ? "alert" : "status"}>{actionMessage.text}</div>}

    <section className="panel history-panel">
      <div className="history-heading"><div><p className="eyebrow">Newest first</p><h2>Orders</h2></div><span className="muted">{orders?.totalElements ?? 0} total</span></div>
      <form className="order-filters" onSubmit={applyFilters}>
        <label>Status<select value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value); setPage(0); }}><option value="">All statuses</option><option value="PENDING">Pending</option><option value="FILLED">Filled</option><option value="CANCELLED">Cancelled</option><option value="REJECTED">Rejected</option><option value="PARTIALLY_FILLED">Partially filled</option></select></label>
        <label>Symbol<input value={symbolInput} maxLength={32} placeholder="e.g. TCS" onChange={(event) => setSymbolInput(event.target.value)} /></label>
        <button className="secondary-button" type="submit">Apply filters</button>
        {(statusFilter || symbolFilter) && <button className="text-button" type="button" onClick={() => { setStatusFilter(""); setSymbolInput(""); setSymbolFilter(""); setPage(0); }}>Clear</button>}
      </form>
      {ordersLoading ? <LoadingState label="Loading your orders…" /> : ordersError ? <ErrorState message={ordersError} /> : !orders?.content.length ? <EmptyState message="No orders match these filters." /> : <>
        <div className="table-scroll"><table><thead><tr><th>Symbol</th><th>Side</th><th>Type</th><th>Mode</th><th>Quantity</th><th>Status</th><th>Created</th><th>Details / review</th></tr></thead><tbody>{orders.content.map((order) => <tr key={order.orderId}><td><strong>{order.symbol}</strong><small>{order.exchange}</small></td><td>{order.side}</td><td>{order.orderType}</td><td>{order.tradingMode}</td><td>{order.requestedQuantity}</td><td><StatusBadge status={order.status} /></td><td>{formatDate(order.createdAt)}</td><td><button className="text-button" type="button" onClick={() => setSelectedId(order.orderId)}>{selectedId === order.orderId ? "Selected" : "View"}</button>{order.status === "FILLED" && <> <span aria-hidden="true">·</span> <Link className="text-link" href={`/journal?orderId=${encodeURIComponent(order.orderId)}`}>Review trade</Link></>}</td></tr>)}</tbody></table></div>
        <Pagination page={page} totalPages={orders.totalPages} hasNext={orders.hasNext} onChange={setPage} />
      </>}
    </section>

    {selectedId && <section className="panel history-panel order-detail-panel">
      <div className="history-heading"><div><p className="eyebrow">Order detail</p><h2>{detail ? `${detail.symbol} · ${detail.side}` : "Selected order"}</h2></div><button className="text-button" type="button" onClick={() => setSelectedId(null)}>Close</button></div>
      {detailLoading ? <LoadingState label="Loading order details…" /> : detailError ? <ErrorState message={detailError} /> : detail && <>
        <div className="order-detail-grid">
          <Detail label="Exchange / symbol" value={`${detail.exchange} · ${detail.symbol}`} /><Detail label="Side / type" value={`${detail.side} · ${detail.orderType}`} /><Detail label="Trading mode" value={detail.tradingMode} /><Detail label="State" value={detail.status} /><Detail label="Requested quantity" value={String(detail.requestedQuantity)} /><Detail label="Executed quantity" value={String(detail.executedQuantity)} /><Detail label="Remaining quantity" value={String(detail.remainingQuantity)} /><Detail label="Limit price" value={detail.limitPrice == null ? "—" : formatMoney(detail.limitPrice)} /><Detail label="Created" value={formatDate(detail.createdAt)} /><Detail label="Last updated" value={formatDate(detail.updatedAt)} />
        </div>
        {detail.status === "PENDING" && <button className="danger-button" type="button" disabled={cancellingId === detail.orderId} onClick={() => cancelOrder(detail.orderId)}>{cancellingId === detail.orderId ? "Cancelling…" : "Cancel pending order"}</button>}
        <p className="panel-footnote">The order detail API provides the current state and timestamps; it does not expose an event timeline.</p>
      </>}
    </section>}

    <section className="panel history-panel trades-panel">
      <div className="history-heading"><div><p className="eyebrow">Completed virtual executions</p><h2>Trades</h2></div><span className="muted">{trades?.totalElements ?? 0} total</span></div>
      {tradesLoading ? <LoadingState label="Loading your trades…" /> : tradesError ? <ErrorState message={tradesError} /> : !trades?.content.length ? <EmptyState message="No executions yet. Pending orders are not trades." /> : <>
        <div className="table-scroll"><table><thead><tr><th>Symbol</th><th>Side</th><th>Mode</th><th>Quantity</th><th>Price</th><th>Executed</th><th>Order / review</th></tr></thead><tbody>{trades.content.map((trade) => <tr key={trade.executionId}><td><strong>{trade.symbol}</strong><small>{trade.exchange}</small></td><td>{trade.side}</td><td>{trade.tradingMode}</td><td>{trade.executedQuantity}</td><td>{formatMoney(trade.executionPrice)}</td><td>{formatDate(trade.executedAt)}</td><td><button className="text-button" type="button" onClick={() => setSelectedId(trade.orderId)}>View order</button> <span aria-hidden="true">·</span> <Link className="text-link" href={`/journal?orderId=${encodeURIComponent(trade.orderId)}`}>Review trade</Link></td></tr>)}</tbody></table></div>
        <p className="panel-footnote">Showing the latest {trades.content.length} of {trades.totalElements} executions.</p>
      </>}
    </section>
  </div>;
}

function Pagination({ page, totalPages, hasNext, onChange }: { page: number; totalPages: number; hasNext: boolean; onChange: (page: number) => void }) {
  return <div className="pagination"><button className="secondary-button" type="button" disabled={page <= 0} onClick={() => onChange(page - 1)}>Previous</button><span>Page {page + 1} of {Math.max(1, totalPages)}</span><button className="secondary-button" type="button" disabled={!hasNext} onClick={() => onChange(page + 1)}>Next</button></div>;
}

function Detail({ label, value }: { label: string; value: string }) {
  return <div className="detail-row"><span>{label}</span><strong>{value}</strong></div>;
}

function formatDate(value: string) {
  return new Date(value).toLocaleString();
}

function messageOf(error: unknown) {
  if (error instanceof Error) return error.message;
  return (error as ApiError | null)?.message ?? "The request could not be completed.";
}
