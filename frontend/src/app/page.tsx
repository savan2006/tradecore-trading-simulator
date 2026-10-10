"use client";

import Link from "next/link";
import { useCallback } from "react";
import { formatMoney, loadDashboard } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";

export default function DashboardPage() {
  const load = useCallback(loadDashboard, []);
  const { data, error, loading } = useApiQuery(load);

  if (loading) return <div className="content-wrap"><PageHeading eyebrow="Overview" title="Dashboard" /><LoadingState label="Loading your account and learning dashboard…" /></div>;
  if (error || !data) return <div className="content-wrap"><PageHeading eyebrow="Overview" title="Dashboard" /><ErrorState message={error?.message ?? "Dashboard data is unavailable."} /></div>;

  const portfolio = data.portfolio;
  const quickLinks = [
    ["Markets", "/markets", "Explore supported companies"],
    ["Portfolio", "/portfolio", "Review positions and P&L"],
    ["Orders", "/orders", "Track orders and executions"],
    ["Watchlists", "/watchlists", "Continue saved research"],
  ] as const;

  return (
    <div className="content-wrap">
      <PageHeading eyebrow="Your learning account" title="Dashboard" description="Track your virtual capital, portfolio, and company research in one place." />

      <section className="dashboard-actions" aria-label="Quick actions">
        {quickLinks.map(([label, href, description]) => <Link className="dashboard-action" href={href} key={href}><strong>{label}</strong><span>{description}</span></Link>)}
      </section>

      <section className="metric-grid" aria-label="Account summary">
        <Metric label="Available balance" value={portfolio ? formatMoney(portfolio.availableBalance, portfolio.currency) : "Unavailable"} note={data.errors.portfolio} />
        <Metric label="Reserved balance" value={portfolio ? formatMoney(portfolio.reservedBalance, portfolio.currency) : "Unavailable"} note={data.errors.portfolio} />
        <Metric label="Total portfolio P&L" value={portfolio && portfolio.valuationStatus !== "UNAVAILABLE" ? formatMoney(portfolio.totalPnl, portfolio.currency) : "Unavailable"} note={portfolio?.valuationStatus ?? data.errors.portfolio} />
        <Metric label="Portfolio market value" value={portfolio && portfolio.valuationStatus !== "UNAVAILABLE" ? formatMoney(portfolio.currentMarketValue, portfolio.currency) : "Unavailable"} note={portfolio?.valuationStatus ?? data.errors.portfolio} />
        <Metric label="Open positions" value={portfolio ? String(portfolio.positionCount) : "Unavailable"} note={data.errors.portfolio} />
        <Metric label="Unread notifications" value={data.unreadNotifications ? String(data.unreadNotifications.unreadCount) : "Unavailable"} note={data.errors.unreadNotifications} />
        <Metric label="Supported companies" value={data.instruments ? String(data.instruments.length) : "Unavailable"} note={data.errors.instruments ?? "Approved NSE instruments"} />
      </section>

      <section className="dashboard-grid">
        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Account activity</p><h2>Recent orders</h2></div><Link className="text-link" href="/orders">View all</Link></div>
          {data.orders ? data.orders.content.length ? <div className="compact-list">{data.orders.content.map((order) => <div className="compact-row" key={order.orderId}><span><strong>{order.symbol}</strong><small>{order.side} · {order.orderType} · {order.tradingMode}</small></span><StatusBadge status={order.status} /></div>)}</div> : <EmptyState message="No orders yet. Visit Markets to place a virtual order." /> : <SectionError message={data.errors.orders} label="Order history is unavailable." />}
        </article>

        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Saved research</p><h2>Watchlists</h2></div><Link className="text-link" href="/watchlists">View watchlists</Link></div>
          {data.watchlists ? data.watchlists.length ? <div className="compact-list">{data.watchlists.slice(0, 4).map((list) => <div className="compact-row" key={list.id}><span><strong>{list.name}</strong><small>{list.items.length} companies</small></span><span className="muted">{list.items.slice(0, 3).map((item) => item.symbol).join(" · ") || "Empty"}</span></div>)}</div> : <EmptyState message="No watchlists yet. Create one to keep company research together." /> : <SectionError message={data.errors.watchlists} label="Watchlists are unavailable." />}
        </article>

        <article className="panel dashboard-watchlists">
          <div className="panel-heading"><div><p className="eyebrow">Persisted quotes</p><h2>Market snapshot</h2></div><Link className="text-link" href="/markets">Browse markets</Link></div>
          {data.marketQuotes ? data.marketQuotes.length ? <div className="quote-list">{data.marketQuotes.map((quote) => <Link className="quote-row" href={`/companies/${encodeURIComponent(quote.symbol)}`} key={quote.symbol}>
            <span><strong>{quote.symbol}</strong><small>{quote.exchange}</small></span><span className="quote-value">{formatMoney(quote.dataStatus === "UNAVAILABLE" ? null : quote.lastPrice)}</span><StatusBadge status={quote.dataStatus} />
          </Link>)}</div> : <EmptyState message="No market quotes are available." /> : <SectionError message={data.errors.marketQuotes} label="Market quotes are unavailable." />}
          {data.marketQuotes?.some((quote) => quote.dataStatus !== "LIVE") && <p className="panel-footnote">Some market quotes are stale or unavailable. Check each status before using a price for learning.</p>}
        </article>
      </section>
    </div>
  );
}

function Metric({ label, value, note }: { label: string; value: string; note?: string }) {
  return <article className="metric-card"><span className="metric-label">{label}</span><strong>{value}</strong>{note && <span className="metric-note">{note}</span>}</article>;
}

function SectionError({ message, label }: { message?: string; label: string }) {
  return <div className="empty-state" role={message ? "alert" : undefined}>{message ? `${label} ${message}` : label}</div>;
}
