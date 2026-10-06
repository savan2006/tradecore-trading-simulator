"use client";

import { type FormEvent, useEffect, useMemo, useState } from "react";
import { api, type ApiError, type CompanyComparison, type Instrument } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading } from "@/components/page-states";

const SERIES_COLORS = ["#287a68", "#bd7130", "#5476aa", "#9b5a83"];

export default function ComparePage() {
  const { session } = useAuth();
  const [instruments, setInstruments] = useState<Instrument[]>([]);
  const [selected, setSelected] = useState<string[]>(["TCS", "INFY"]);
  const [candidate, setCandidate] = useState("");
  const [from, setFrom] = useState(() => daysAgo(180));
  const [to, setTo] = useState(() => daysAgo(1));
  const [loadingCompanies, setLoadingCompanies] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<CompanyComparison | null>(null);

  useEffect(() => {
    if (!session) return;
    const controller = new AbortController();
    setLoadingCompanies(true);
    api.instruments(session.basicCredential, controller.signal)
      .then((rows) => {
        setInstruments(rows);
        setSelected((current) => current.filter((symbol) => rows.some((row) => row.symbol === symbol)).slice(0, 4));
      })
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(messageOf(reason)); })
      .finally(() => { if (!controller.signal.aborted) setLoadingCompanies(false); });
    return () => controller.abort();
  }, [session]);

  const choices = useMemo(() => instruments.filter((row) => !selected.includes(row.symbol)), [instruments, selected]);

  function addCompany(symbol: string) {
    if (!symbol || selected.includes(symbol) || selected.length >= 4) return;
    setSelected((current) => [...current, symbol]);
    setCandidate("");
    setResult(null);
  }

  async function compare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session) return;
    setError(null);
    setResult(null);
    if (selected.length < 2 || selected.length > 4) {
      setError("Select between two and four supported companies.");
      return;
    }
    if (!from || !to || to < from || to > today()) {
      setError("Choose a valid date range ending today or earlier.");
      return;
    }
    setRunning(true);
    try {
      setResult(await api.compareCompanies(session.basicCredential, selected, from, to));
    } catch (reason) {
      setError(messageOf(reason));
    } finally {
      setRunning(false);
    }
  }

  return <div className="content-wrap">
    <PageHeading eyebrow="Historical learning" title="Company comparison" description="Compare persisted daily price history and selected business profile details across supported NSE companies." />
    {!session ? <LoginRequired /> : <>
      <p className="notice notice-muted comparison-disclaimer">Historical data is descriptive and may be incomplete. Indexed prices and historical metrics are educational context only; they do not predict future performance or recommend any investment action.</p>
      <form className="panel strategy-form" onSubmit={compare}>
        <div className="comparison-controls">
          <div className="comparison-company-select">
            <span className="comparison-label">Companies ({selected.length}/4)</span>
            <div className="comparison-chips">{selected.map((symbol) => {
              const instrument = instruments.find((row) => row.symbol === symbol);
              return <span className="comparison-chip" key={symbol}>{symbol}{instrument ? ` · ${instrument.companyName}` : ""}
                <button type="button" aria-label={`Remove ${symbol}`} onClick={() => { setSelected((current) => current.filter((item) => item !== symbol)); setResult(null); }}>×</button>
              </span>;
            })}</div>
            <label>Add supported company<select value={candidate} onChange={(event) => addCompany(event.target.value)} disabled={loadingCompanies || selected.length >= 4 || !choices.length}>
              <option value="">Select a company</option>
              {choices.map((item) => <option key={item.symbol} value={item.symbol}>{item.symbol} · {item.companyName}</option>)}
            </select></label>
            {loadingCompanies && <p role="status">Loading supported companies…</p>}
          </div>
          <label>From date<input type="date" required max={today()} value={from} onChange={(event) => setFrom(event.target.value)} /></label>
          <label>To date<input type="date" required max={today()} value={to} onChange={(event) => setTo(event.target.value)} /></label>
        </div>
        {error && <div className="strategy-error" role="alert"><ErrorState message={error} /></div>}
        <button className="button-primary" type="submit" disabled={running || loadingCompanies || selected.length < 2}>{running ? "Comparing…" : "Compare companies"}</button>
      </form>
      {running && <LoadingState label="Loading persisted daily candles…" />}
      {!result && !running && !error && <EmptyState message="Select two to four supported companies and a date range to compare their history." />}
      {result && <ComparisonResult result={result} />}
    </>}
  </div>;
}

function ComparisonResult({ result }: { result: CompanyComparison }) {
  return <section className="comparison-results" aria-label="Company comparison results">
    <section className="panel performance-chart-panel">
      <div className="panel-heading"><div><p className="eyebrow">Indexed daily close</p><h2>Price history comparison</h2></div><span className="muted">Each company begins at 100 on its first available date</span></div>
      {result.indexedSeries.some((line) => line.points.length > 0)
        ? <IndexedChart result={result} />
        : <EmptyState message="No persisted daily candles are available for these companies in the selected period." />}
      <div className="comparison-legend">{result.indexedSeries.map((line, index) => <span key={line.symbol}><i style={{ backgroundColor: SERIES_COLORS[index] }} />{line.symbol}</span>)}</div>
    </section>
    <section className="panel table-panel comparison-table-panel">
      <div className="panel-heading"><div><p className="eyebrow">Historical metrics</p><h2>Selected period: {result.from} to {result.to}</h2></div></div>
      <div className="table-scroll"><table><thead><tr><th>Company / business</th><th>Start close</th><th>End close</th><th>Absolute return</th><th>Return</th><th>Annual volatility</th><th>Max drawdown</th><th>Trading days</th><th>Latest candle</th></tr></thead><tbody>
        {result.companies.map((company) => <tr key={company.symbol}>
          <td><strong>{company.symbol} · {company.companyName}</strong><small>{company.sector} · {company.businessType}</small><small>{company.businessDescription}</small>{company.dataNote && <small className="comparison-data-note">{company.dataNote}</small>}</td>
          <td>{formatPrice(company.startClose)}</td><td>{formatPrice(company.endClose)}</td>
          <td>{formatPrice(company.absoluteReturn)}</td><td>{formatPercent(company.percentageReturn)}</td>
          <td>{formatPercent(company.annualizedVolatilityPercent)}</td><td>{formatPercent(company.maximumDrawdownPercent)}</td>
          <td>{company.numberOfTradingDays}</td><td>{company.latestAvailableCandleDate ?? "—"}</td>
        </tr>)}
      </tbody></table></div>
      <p className="panel-footnote">Annualized volatility uses the sample standard deviation of observed daily close-to-close returns and 252 trading days. Maximum drawdown is shown as a positive peak-to-trough percentage. Missing candles are not interpolated.</p>
    </section>
  </section>;
}

function IndexedChart({ result }: { result: CompanyComparison }) {
  const width = 900, height = 280, padX = 46, padY = 24;
  const allPoints = result.indexedSeries.flatMap((line) => line.points);
  const values = allPoints.map((point) => point.indexedValue);
  const minValue = Math.min(100, ...values), maxValue = Math.max(100, ...values);
  const valueSpan = maxValue - minValue || 1;
  const startDay = dayNumber(result.from), endDay = dayNumber(result.to);
  const daySpan = endDay - startDay || 1;
  const x = (date: string) => padX + (dayNumber(date) - startDay) / daySpan * (width - padX * 2);
  const y = (value: number) => padY + (maxValue - value) / valueSpan * (height - padY * 2);
  return <div className="chart-wrap comparison-chart" role="img" aria-label={`Indexed price comparison for ${result.companies.map((row) => row.symbol).join(", ")}`}>
    <svg viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <line className="chart-axis" x1={padX} x2={width - padX} y1={y(100)} y2={y(100)} />
      {result.indexedSeries.map((line, index) => line.points.length > 1 && <path key={line.symbol} d={line.points.map((point, pointIndex) => `${pointIndex ? "L" : "M"} ${x(point.date)} ${y(point.indexedValue)}`).join(" ")} fill="none" stroke={SERIES_COLORS[index]} strokeWidth="2.5" strokeLinejoin="round" />)}
    </svg>
    <div className="chart-legend"><span>{result.from} · index 100 baseline</span><span>{result.to}</span></div>
  </div>;
}

function formatPrice(value: number | null) {
  return value == null ? "—" : value.toLocaleString("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

function formatPercent(value: number | null) {
  return value == null ? "—" : `${value.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;
}

function dayNumber(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  return Date.UTC(year, month - 1, day) / 86_400_000;
}

function daysAgo(days: number) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" })
    .format(new Date(Date.now() - days * 86_400_000));
}

function today() { return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" }).format(new Date()); }

function messageOf(reason: unknown) {
  if (reason instanceof Error && (reason as ApiError).status === 401) return "Your session has expired. Please sign in again.";
  return reason instanceof Error ? reason.message : "The comparison could not be completed.";
}
