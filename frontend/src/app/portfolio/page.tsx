"use client";

import { useCallback } from "react";
import { api, formatMoney } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";

export default function PortfolioPage() {
  const load = useCallback(api.portfolio, []);
  const { data, error, loading } = useApiQuery(load);
  return <div className="content-wrap">
    <PageHeading eyebrow="Virtual account" title="Portfolio" description="Values are calculated from your account, positions, and persisted quotes." />
    {loading ? <LoadingState /> : error ? <ErrorState message={error.message} /> : !data ? <EmptyState message="Portfolio is unavailable." /> : <>
      <div className="panel portfolio-status"><span>Valuation status</span><StatusBadge status={data.valuationStatus} /></div>
      <section className="metric-grid portfolio-metrics">
        <Metric label="Available balance" value={formatMoney(data.availableBalance, data.currency)} />
        <Metric label="Reserved balance" value={formatMoney(data.reservedBalance, data.currency)} />
        <Metric label="Position cost" value={formatMoney(data.investedCost, data.currency)} />
        <Metric label="Current market value" value={formatMoney(data.currentMarketValue, data.currency)} />
        <Metric label="Realized P&L" value={formatMoney(data.realizedPnl, data.currency)} />
        <Metric label="Unrealized P&L" value={formatMoney(data.unrealizedPnl, data.currency)} />
      </section>
      <section className="panel table-panel"><div className="panel-heading"><div><p className="eyebrow">Holdings</p><h2>{data.positionCount} positions</h2></div><strong>Total P&amp;L {formatMoney(data.totalPnl, data.currency)}</strong></div>
        {data.positions?.length ? <div className="table-scroll"><table><thead><tr><th>Company</th><th>Mode</th><th>Quantity</th><th>Sellable</th><th>Average cost</th><th>Price</th><th>Market value</th><th>Status</th></tr></thead><tbody>{data.positions.map((position) => <tr key={`${position.symbol}-${position.tradingMode}`}><td><strong>{position.symbol}</strong><small>{position.exchange}</small></td><td>{position.tradingMode}</td><td>{position.quantity}</td><td>{position.sellableQuantity}</td><td>{formatMoney(position.averageCost, data.currency)}</td><td>{formatMoney(position.currentPrice, data.currency)}</td><td>{formatMoney(position.marketValue, data.currency)}</td><td><StatusBadge status={position.valuationStatus} /></td></tr>)}</tbody></table></div> : <EmptyState message="Your portfolio has no open positions." />}
      </section>
    </>}
  </div>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <article className="metric-card"><span className="metric-label">{label}</span><strong>{value}</strong></article>;
}
