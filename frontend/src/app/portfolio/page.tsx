"use client";

import { useCallback } from "react";
import { api, formatMoney } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";

export default function PortfolioPage() {
  const load = useCallback(api.portfolio, []);
  const { data, error, loading } = useApiQuery(load);

  return <div className="content-wrap">
    <PageHeading eyebrow="Virtual account" title="Portfolio" description="Review your virtual balances, holdings, and quote-based portfolio valuation." />
    {loading ? <LoadingState label="Loading your portfolio…" /> : error ? <ErrorState message={error.message} /> : !data ? <EmptyState message="Portfolio data is unavailable." /> : <>
      <div className="panel portfolio-status">
        <span>Portfolio valuation</span>
        <StatusBadge status={data.valuationStatus} />
      </div>

      {data.valuationStatus !== "LIVE" && <div className="portfolio-valuation-note" role="status">
        {data.valuationStatus === "STALE"
          ? "One or more holdings use stale quotes. Displayed market values and unrealized P&L may be out of date."
          : "One or more holdings do not have a usable quote. Current market value and unrealized P&L may be incomplete."}
      </div>}

      <section className="metric-grid portfolio-metrics" aria-label="Portfolio summary">
        <Metric label="Available balance" value={formatMoney(data.availableBalance, data.currency)} />
        <Metric label="Reserved balance" value={formatMoney(data.reservedBalance, data.currency)} />
        <Metric label="Current portfolio value" value={formatMoney(data.currentMarketValue, data.currency)} />
        <Metric label="Realized P&L" value={formatMoney(data.realizedPnl, data.currency)} />
        <Metric label="Unrealized P&L" value={formatMoney(data.unrealizedPnl, data.currency)} />
        <Metric label="Total P&L" value={formatMoney(data.totalPnl, data.currency)} />
        <Metric label="Position count" value={String(data.positionCount)} />
      </section>

      <section className="panel table-panel">
        <div className="panel-heading">
          <div><p className="eyebrow">Holdings</p><h2>{data.positionCount} positions</h2></div>
          <span className="muted">Quote status is shown for each position</span>
        </div>
        {data.positions.length === 0 ? <EmptyState message="Your portfolio has no open positions yet. Positions will appear here after an order is executed." /> : <div className="table-scroll">
          <table>
            <thead><tr><th>Symbol</th><th>Trading mode</th><th>Quantity</th><th>Reserved quantity</th><th>Sellable quantity</th><th>Average cost</th><th>Current price</th><th>Market value</th><th>Unrealized P&amp;L</th><th>Realized P&amp;L</th><th>Quote status</th></tr></thead>
            <tbody>{data.positions.map((position) => <tr key={`${position.symbol}-${position.tradingMode}`}>
              <td><strong>{position.symbol}</strong><small>{position.exchange}</small></td>
              <td>{position.tradingMode}</td>
              <td>{position.quantity}</td>
              <td>{position.reservedQuantity}</td>
              <td>{position.sellableQuantity}</td>
              <td>{formatMoney(position.averageCost, data.currency)}</td>
              <td>{formatMoney(position.currentPrice, data.currency)}</td>
              <td>{formatMoney(position.marketValue, data.currency)}</td>
              <td>{formatMoney(position.unrealizedPnl, data.currency)}</td>
              <td>{formatMoney(position.realizedPnl, data.currency)}</td>
              <td><StatusBadge status={position.valuationStatus} /></td>
            </tr>)}</tbody>
          </table>
        </div>}
      </section>
    </>}
  </div>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <article className="metric-card"><span className="metric-label">{label}</span><strong>{value}</strong></article>;
}
