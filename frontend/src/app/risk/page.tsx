"use client";

import { useCallback } from "react";
import { useAuth } from "@/lib/auth-context";
import { api, type RiskLimit } from "@/lib/api";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";
import { useApiQuery } from "@/lib/use-api-query";

export default function RiskPage() {
  const { session } = useAuth();
  const load = useCallback(api.riskMe, []);
  const { data, error, loading } = useApiQuery(load);

  return <div className="content-wrap">
    <PageHeading eyebrow="Trading controls" title="Risk limits" description="Review active limits that apply to your virtual trading account." />
    {!session ? <LoginRequired /> : loading ? <LoadingState label="Loading risk limits…" /> : error ? <ErrorState message={error.message} /> : !data ? <ErrorState message="Risk limit information is unavailable." /> : data.length === 0 ? <EmptyState message="No active risk limits are configured for your account." /> : <>
      <div className="notice notice-muted risk-note" role="status">These are configured limits. Current usage and remaining amounts are shown only when the backend can provide them safely; per-order limit usage is not persisted.</div>
      <section className="risk-limit-grid" aria-label="Active risk limits">
        {data.map((limit) => <RiskLimitCard key={`${limit.scope}-${limit.limitType}-${limit.symbol ?? "all"}`} limit={limit} />)}
      </section>
    </>}
  </div>;
}

function RiskLimitCard({ limit }: { limit: RiskLimit }) {
  return <article className="panel risk-limit-card">
    <div className="risk-limit-heading"><div><p className="eyebrow">{limit.scope}{limit.symbol ? ` · ${limit.exchange} ${limit.symbol}` : ""}</p><h2>{labelFor(limit.limitType)}</h2></div><StatusBadge status="ACTIVE" /></div>
    <dl className="risk-limit-values">
      <div><dt>Limit type</dt><dd>{limit.limitType}</dd></div>
      <div><dt>Configured value</dt><dd>{formatConfiguredValue(limit)}</dd></div>
      <div><dt>Current usage</dt><dd>{limit.currentUsage == null ? "Unavailable" : limit.currentUsage.toLocaleString("en-IN")}</dd></div>
      <div><dt>Remaining value</dt><dd>{limit.remainingValue == null ? "Unavailable" : limit.remainingValue.toLocaleString("en-IN")}</dd></div>
      <div><dt>Effective from</dt><dd>{formatDate(limit.effectiveFrom)}</dd></div>
      <div><dt>Effective until</dt><dd>{formatDate(limit.effectiveUntil)}</dd></div>
    </dl>
  </article>;
}

function labelFor(type: string) {
  switch (type) {
    case "MAX_ORDER_QUANTITY": return "Maximum order quantity";
    case "MAX_ORDER_AMOUNT":
    case "MAX_ORDER_VALUE": return "Maximum order value";
    case "TRADING_DISABLED": return "Trading restriction";
    case "INSTRUMENT_BLOCKED": return "Instrument restriction";
    default: return type;
  }
}

function formatConfiguredValue(limit: RiskLimit) {
  if (limit.limitType === "MAX_ORDER_AMOUNT" || limit.limitType === "MAX_ORDER_VALUE") {
    return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(limit.configuredValue);
  }
  return limit.configuredValue.toLocaleString("en-IN");
}

function formatDate(value: string | null) {
  if (!value) return "Not set";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unavailable" : date.toLocaleString("en-IN", { timeZone: "Asia/Kolkata" });
}
