"use client";

import Link from "next/link";
import { useCallback } from "react";
import { formatMoney, loadDashboard } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";

export default function DashboardPage() {
  const load = useCallback(loadDashboard, []);
  const { data, error, loading } = useApiQuery(load);

  if (loading) return <div className="content-wrap"><PageHeading eyebrow="Overview" title="Dashboard" /><LoadingState /></div>;
  if (error || !data) return <div className="content-wrap"><PageHeading eyebrow="Overview" title="Dashboard" /><ErrorState message={error?.message ?? "Dashboard data is unavailable."} /></div>;

  return (
    <div className="content-wrap">
      <PageHeading eyebrow="Overview" title="Dashboard" description="A practical snapshot of your virtual account and supported market universe." />
      <section className="metric-grid" aria-label="Account summary">
        <article className="metric-card"><span className="metric-label">Supported companies</span><strong>{data.instruments?.length ?? "—"}</strong><span className="metric-note">Approved NSE instruments</span></article>
        <article className="metric-card"><span className="metric-label">Available balance</span><strong>{data.portfolio ? formatMoney(data.portfolio.availableBalance, data.portfolio.currency) : "—"}</strong><span className="metric-note">Virtual account balance</span></article>
        <article className="metric-card"><span className="metric-label">Portfolio value</span><strong>{data.portfolio ? formatMoney(data.portfolio.currentMarketValue, data.portfolio.currency) : "—"}</strong><span className="metric-note">{data.portfolio?.valuationStatus ?? "Account data unavailable"}</span></article>
        <article className="metric-card"><span className="metric-label">Open positions</span><strong>{data.portfolio?.positionCount ?? "—"}</strong><span className="metric-note">Across trading modes</span></article>
      </section>

      <section className="dashboard-grid">
        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Persisted quotes</p><h2>Market overview</h2></div><Link className="text-link" href="/markets">Browse companies</Link></div>
          {data.marketQuotes ? <div className="quote-list">{data.marketQuotes.map((quote) => <Link className="quote-row" href={`/companies/${encodeURIComponent(quote.symbol)}`} key={quote.symbol}>
            <span><strong>{quote.symbol}</strong><small>{quote.exchange}</small></span><span className="quote-value">{quote.lastPrice == null ? "—" : formatMoney(quote.lastPrice)}</span><StatusBadge status={quote.dataStatus} />
          </Link>)}</div> : <EmptyState message="Quote overview is currently unavailable." />}
        </article>
        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Account activity</p><h2>Recent orders</h2></div><Link className="text-link" href="/orders">View all</Link></div>
          {data.orders ? data.orders.content.length ? <div className="compact-list">{data.orders.content.map((order) => <div className="compact-row" key={order.orderId}><span><strong>{order.symbol}</strong><small>{order.side} · {order.orderType} · {order.tradingMode}</small></span><StatusBadge status={order.status} /></div>)}</div> : <EmptyState message="No orders yet." /> : <EmptyState message="Order history is unavailable." />}
        </article>
        <article className="panel dashboard-watchlists">
          <div className="panel-heading"><div><p className="eyebrow">Saved research</p><h2>Watchlists</h2></div><Link className="text-link" href="/watchlists">View watchlists</Link></div>
          {data.watchlists ? data.watchlists.length ? <div className="compact-list">{data.watchlists.slice(0, 3).map((list) => <div className="compact-row" key={list.id}><span><strong>{list.name}</strong><small>{list.items.length} companies</small></span><span className="muted">{list.items.slice(0, 3).map((item) => item.symbol).join(" · ") || "Empty"}</span></div>)}</div> : <EmptyState message="Create a watchlist to keep company research together." /> : <EmptyState message="Watchlists are unavailable." />}
        </article>
      </section>
    </div>
  );
}
