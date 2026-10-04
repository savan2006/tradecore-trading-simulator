"use client";

import Link from "next/link";
import { useCallback } from "react";
import { api } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading } from "@/components/page-states";

export default function MarketsPage() {
  const load = useCallback(api.learningProfiles, []);
  const { data, error, loading } = useApiQuery(load);
  return <div className="content-wrap">
    <PageHeading eyebrow="Learning universe" title="Markets" description="Explore the supported companies and their business context. Market data comes from persisted backend records." />
    {loading ? <LoadingState label="Loading supported companies…" /> : error ? <ErrorState message={error.message} /> : !data?.length ? <EmptyState message="No learning profiles are available yet." /> : <>
      <div className="section-summary">{data.length} supported companies</div>
      <section className="company-grid">{data.map((company) => <Link className="company-card" href={`/companies/${encodeURIComponent(company.symbol)}`} key={company.symbol}>
        <span className="company-symbol">{company.symbol}</span><span className="company-sector">{company.sector}</span><strong>{company.companyName}</strong><span className="muted">{company.businessType}</span><span className="text-link">Open company overview →</span>
      </Link>)}</section>
    </>}
  </div>;
}
