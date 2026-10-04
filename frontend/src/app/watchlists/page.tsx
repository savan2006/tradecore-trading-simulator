"use client";

import Link from "next/link";
import { useCallback } from "react";
import { api, formatMoney } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";

export default function WatchlistsPage() {
  const load = useCallback(api.watchlists, []);
  const { data, error, loading } = useApiQuery(load);
  return <div className="content-wrap">
    <PageHeading eyebrow="Saved research" title="Watchlists" description="Your saved company lists with persisted quote status where available." />
    {loading ? <LoadingState /> : error ? <ErrorState message={error.message} /> : !data?.length ? <EmptyState message="No watchlists yet." /> : <section className="watchlist-stack">{data.map((list) => <article className="panel" key={list.id}><div className="panel-heading"><div><p className="eyebrow">Watchlist</p><h2>{list.name}</h2></div><span className="muted">{list.items.length} companies</span></div>{list.items.length ? <div className="table-scroll"><table><thead><tr><th>Company</th><th>Last price</th><th>Quote status</th></tr></thead><tbody>{list.items.map((item) => <tr key={item.id}><td><Link className="text-link" href={`/companies/${encodeURIComponent(item.symbol)}`}>{item.symbol}</Link><small>{item.companyName}</small></td><td>{formatMoney(item.quote?.lastPrice)}</td><td><StatusBadge status={item.quote?.dataStatus ?? "UNAVAILABLE"} /></td></tr>)}</tbody></table></div> : <EmptyState message="This watchlist is empty." />}</article>)}</section>}
  </div>;
}
