"use client";

import { useCallback } from "react";
import { api, formatMoney, type Performance, type PerformancePoint } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";
import { useApiQuery } from "@/lib/use-api-query";

export default function PerformancePage() {
  const { session } = useAuth();
  const load = useCallback((credential: string, signal: AbortSignal) => api.performanceMe(credential, signal), []);
  const { data, error, loading } = useApiQuery(load);

  return <div className="content-wrap">
    <PageHeading eyebrow="Trading review" title="Performance" description="Review your simulated trading activity and realized results." />
    {!session ? <LoginRequired /> : loading ? <LoadingState label="Loading performance…" /> : error ? <ErrorState message={error.message} /> : !data ? <ErrorState message="Performance information is unavailable." /> : <PerformanceContent data={data} />}
  </div>;
}

function PerformanceContent({ data }: { data: Performance }) {
  return <>
    <section className="metric-grid" aria-label="Performance summary">
      <Metric label="Total P&L" value={data.totalPnl == null || data.valuationStatus === "UNAVAILABLE" ? "Unavailable" : formatMoney(data.totalPnl)} note={data.totalPnl == null || data.valuationStatus === "UNAVAILABLE" ? `Valuation ${data.valuationStatus.toLowerCase()}` : "Realized + current unrealized"} valueClass={data.totalPnl == null || data.valuationStatus === "UNAVAILABLE" ? "" : data.totalPnl >= 0 ? "positive-value" : "negative-value"} />
      <Metric label="Total orders" value={String(data.totalOrders)} note={`${data.filledOrders} filled · ${data.cancelledOrders} cancelled`} />
      <Metric label="Executions" value={String(data.totalExecutions)} note="Completed simulated fills" />
      <Metric label="Open positions" value={String(data.currentOpenPositions)} note="Across delivery and intraday" />
    </section>

    <div className="performance-grid">
      <section className="panel performance-panel">
        <div className="panel-heading"><div><p className="eyebrow">Valuation</p><h2>P&L overview</h2></div><StatusBadge status={data.valuationStatus} /></div>
        <dl className="performance-values">
          <Value label="Realized P&L" value={formatMoney(data.realizedPnl)} />
          <Value label="Unrealized P&L" value={data.unrealizedPnl == null ? "Unavailable" : formatMoney(data.unrealizedPnl)} />
          <Value label="Current position value" value={data.currentPortfolioValue == null ? "Unavailable" : formatMoney(data.currentPortfolioValue)} />
          <Value label="Profitable / losing closed positions" value={`${data.profitableClosedPositions} / ${data.losingClosedPositions}`} />
        </dl>
        {data.valuationStatus === "STALE" && <p className="notice notice-stale" role="status">Unrealized and total P&L are unavailable because one or more position quotes are stale.</p>}
        {data.valuationStatus === "UNAVAILABLE" && <p className="notice notice-muted" role="status">Current valuation is unavailable because a position quote is missing or invalid. Realized P&L remains available.</p>}
        {data.bestRealizedPosition && <Value label="Best realized position" value={`${data.bestRealizedPosition.symbol} · ${data.bestRealizedPosition.tradingMode} · ${formatMoney(data.bestRealizedPosition.realizedPnl)}`} />}
        {data.worstRealizedPosition && <Value label="Lowest realized position" value={`${data.worstRealizedPosition.symbol} · ${data.worstRealizedPosition.tradingMode} · ${formatMoney(data.worstRealizedPosition.realizedPnl)}`} />}
      </section>

      <section className="panel performance-panel">
        <div className="panel-heading"><div><p className="eyebrow">Activity mix</p><h2>Order breakdown</h2></div></div>
        <dl className="performance-values">
          <Value label="BUY orders" value={String(data.buyOrders)} />
          <Value label="SELL orders" value={String(data.sellOrders)} />
          <Value label="DELIVERY orders" value={String(data.deliveryOrders)} />
          <Value label="INTRADAY orders" value={String(data.intradayOrders)} />
        </dl>
      </section>
    </div>

    <section className="panel performance-chart-panel">
      <div className="panel-heading"><div><p className="eyebrow">Closed positions</p><h2>Recent realized performance</h2></div><span className="muted">Up to 30 position records</span></div>
      {!data.recentPerformance.length ? <EmptyState message="Closed position performance will appear here after you close a position." /> : <PerformanceChart points={data.recentPerformance} />}
      <p className="panel-footnote">Each point is the realized P&L recorded on a closed instrument and trading-mode position. It is not a daily account-equity curve.</p>
    </section>
  </>;
}

function Metric({ label, value, note, valueClass = "" }: { label: string; value: string; note: string; valueClass?: string }) {
  return <article className="metric-card"><span className="metric-label">{label}</span><strong className={valueClass}>{value}</strong><span className="metric-note">{note}</span></article>;
}

function Value({ label, value }: { label: string; value: string }) {
  return <div className="performance-value"><dt>{label}</dt><dd>{value}</dd></div>;
}

function PerformanceChart({ points }: { points: PerformancePoint[] }) {
  const width = 720, height = 220, pad = 24;
  const values = points.map((point) => point.realizedPnl);
  const min = Math.min(0, ...values), max = Math.max(0, ...values);
  const span = max - min || 1;
  const xStep = points.length > 1 ? (width - pad * 2) / (points.length - 1) : 0;
  const y = (value: number) => pad + (max - value) / span * (height - pad * 2);
  const line = points.map((point, index) => `${index === 0 ? "M" : "L"} ${pad + index * xStep} ${y(point.realizedPnl)}`).join(" ");
  return <div className="chart-wrap" role="img" aria-label={`Recent closed-position realized performance: ${points.map((point) => `${point.symbol} ${formatMoney(point.realizedPnl)}`).join(", ")}`}>
    <svg viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <line className="chart-axis" x1={pad} x2={width - pad} y1={y(0)} y2={y(0)} />
      {points.length > 1 && <path className="chart-line" d={line} />}
      {points.map((point, index) => <g key={`${point.symbol}-${point.tradingMode}-${point.closedAt}`}>
        <circle className="chart-point" cx={pad + index * xStep} cy={y(point.realizedPnl)} r="5"><title>{`${point.symbol} · ${point.tradingMode} · ${formatMoney(point.realizedPnl)} · ${formatDate(point.closedAt)}`}</title></circle>
      </g>)}
    </svg>
    <div className="chart-legend"><span>{formatDate(points[0].closedAt)} · {points[0].symbol}</span><span>{formatDate(points.at(-1)!.closedAt)} · {points.at(-1)!.symbol}</span></div>
  </div>;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Date unavailable" : date.toLocaleDateString("en-IN", { timeZone: "Asia/Kolkata" });
}
