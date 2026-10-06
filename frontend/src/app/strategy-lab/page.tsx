"use client";

import { type FormEvent, useEffect, useMemo, useState } from "react";
import { api, formatMoney, type ApiError, type Instrument, type StrategyBacktest, type StrategyBacktestRequest } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading } from "@/components/page-states";

type Strategy = StrategyBacktestRequest["strategy"];

export default function StrategyLabPage() {
  const { session } = useAuth();
  const [instruments, setInstruments] = useState<Instrument[]>([]);
  const [loadingInstruments, setLoadingInstruments] = useState(false);
  const [instrumentError, setInstrumentError] = useState<string | null>(null);
  const [symbol, setSymbol] = useState("TCS");
  const [fromDate, setFromDate] = useState(() => dateDaysAgo(180));
  const [toDate, setToDate] = useState(() => dateDaysAgo(1));
  const [strategy, setStrategy] = useState<Strategy>("SIMPLE_MOVING_AVERAGE_CROSSOVER");
  const [startingCapital, setStartingCapital] = useState("100000");
  const [fastPeriod, setFastPeriod] = useState("10");
  const [slowPeriod, setSlowPeriod] = useState("30");
  const [rsiPeriod, setRsiPeriod] = useState("14");
  const [oversold, setOversold] = useState("30");
  const [overbought, setOverbought] = useState("70");
  const [result, setResult] = useState<StrategyBacktest | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  useEffect(() => {
    if (!session) return;
    const controller = new AbortController();
    setLoadingInstruments(true);
    api.instruments(session.basicCredential, controller.signal)
      .then((values) => { setInstruments(values); setSymbol((current) => values.some((item) => item.symbol === current) ? current : values[0]?.symbol ?? ""); })
      .catch((reason: unknown) => { if (!controller.signal.aborted) setInstrumentError(messageOf(reason)); })
      .finally(() => { if (!controller.signal.aborted) setLoadingInstruments(false); });
    return () => controller.abort();
  }, [session]);

  const selectedCompany = useMemo(() => instruments.find((item) => item.symbol === symbol), [instruments, symbol]);

  async function run(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session) return;
    setError(null);
    setResult(null);
    if (!symbol || !fromDate || !toDate || toDate < fromDate || Number(startingCapital) <= 0) {
      setError("Choose a supported company, an ordered date range, and positive starting capital.");
      return;
    }
    const input: StrategyBacktestRequest = {
      symbol, fromDate, toDate, strategy, startingCapital: Number(startingCapital),
      ...(strategy === "SIMPLE_MOVING_AVERAGE_CROSSOVER"
        ? { fastPeriod: Number(fastPeriod), slowPeriod: Number(slowPeriod) }
        : { rsiPeriod: Number(rsiPeriod), oversoldThreshold: Number(oversold), overboughtThreshold: Number(overbought) }),
    };
    setRunning(true);
    try {
      setResult(await api.strategyBacktest(session.basicCredential, input));
    } catch (reason) {
      setError(messageOf(reason));
    } finally {
      setRunning(false);
    }
  }

  return <div className="content-wrap">
    <PageHeading eyebrow="Historical learning tool" title="Strategy Lab" description="Explore how simple rule-based strategies would have behaved on persisted historical candles." />
    {!session ? <LoginRequired /> : <>
      <p className="notice notice-muted strategy-disclaimer">Historical educational simulation only. Results are gross-price calculations, do not include transaction costs, and do not predict or guarantee future performance. This tool does not make recommendations or place trades.</p>
      <form className="panel strategy-form" onSubmit={run}>
        <div className="strategy-form-grid">
          <label>Supported company<select required value={symbol} onChange={(event) => setSymbol(event.target.value)} disabled={loadingInstruments || !instruments.length}>
            {instruments.map((item) => <option key={`${item.exchange}:${item.symbol}`} value={item.symbol}>{item.symbol} · {item.companyName}</option>)}
          </select></label>
          <label>From date<input type="date" required value={fromDate} onChange={(event) => setFromDate(event.target.value)} /></label>
          <label>To date<input type="date" required max={today()} value={toDate} onChange={(event) => setToDate(event.target.value)} /></label>
          <label>Strategy<select value={strategy} onChange={(event) => { setStrategy(event.target.value as Strategy); setResult(null); }}>
            <option value="SIMPLE_MOVING_AVERAGE_CROSSOVER">Simple moving average crossover</option>
            <option value="RSI_MEAN_REVERSION">RSI mean reversion</option>
          </select></label>
          <label>Starting test capital<input type="number" min="1" step="0.01" required value={startingCapital} onChange={(event) => setStartingCapital(event.target.value)} /></label>
          {strategy === "SIMPLE_MOVING_AVERAGE_CROSSOVER" ? <>
            <label>Fast average period<input type="number" min="2" max="50" required value={fastPeriod} onChange={(event) => setFastPeriod(event.target.value)} /></label>
            <label>Slow average period<input type="number" min="3" max="100" required value={slowPeriod} onChange={(event) => setSlowPeriod(event.target.value)} /></label>
          </> : <>
            <label>RSI period<input type="number" min="2" max="100" required value={rsiPeriod} onChange={(event) => setRsiPeriod(event.target.value)} /></label>
            <label>Oversold threshold<input type="number" min="1" max="99" step="0.1" required value={oversold} onChange={(event) => setOversold(event.target.value)} /></label>
            <label>Overbought threshold<input type="number" min="1" max="99" step="0.1" required value={overbought} onChange={(event) => setOverbought(event.target.value)} /></label>
          </>}
        </div>
        {selectedCompany && <p className="metric-note">{selectedCompany.exchange} · {selectedCompany.instrumentType} · historical daily candles</p>}
        {loadingInstruments && <p role="status">Loading supported companies…</p>}
        {instrumentError && <ErrorState message={instrumentError} />}
        <button className="button-primary" type="submit" disabled={running || loadingInstruments || !instruments.length}>{running ? "Running simulation…" : "Run backtest"}</button>
      </form>
      {running && <LoadingState label="Calculating from persisted daily candles…" />}
      {error && <div className="strategy-error" role="alert"><ErrorState message={error} />{/insufficient|no persisted|no candle/i.test(error) && <p>Try a longer period with available historical daily candles.</p>}</div>}
      {!result && !running && !error && <EmptyState message="Choose the company, period, strategy, and test capital to run a historical simulation." />}
      {result && <BacktestResult result={result} />}
    </>}
  </div>;
}

function BacktestResult({ result }: { result: StrategyBacktest }) {
  return <section className="strategy-results" aria-label="Backtest results">
    <div className="metric-grid strategy-metrics">
      <Metric label="Ending capital" value={formatMoney(result.endingCapital)} />
      <Metric label="Total return" value={`${formatPercent(result.totalReturnPercent)}`} />
      <Metric label="Realized P&L" value={formatMoney(result.realizedPnl)} />
      <Metric label="Completed trades" value={String(result.numberOfTrades)} />
      <Metric label="Win rate" value={`${formatPercent(result.winRatePercent)} · ${result.winningTrades}W / ${result.losingTrades}L`} />
      <Metric label="Maximum drawdown" value={formatPercent(result.maximumDrawdownPercent)} />
      <Metric label="Buy-and-hold return" value={formatPercent(result.buyAndHoldReturnPercent)} />
    </div>
    <section className="panel performance-chart-panel">
      <div className="panel-heading"><div><p className="eyebrow">Test capital over time</p><h2>Equity curve</h2></div><span className="muted">{result.symbol} · {result.fromDate} to {result.toDate}</span></div>
      {result.equityCurve.length ? <EquityChart points={result.equityCurve} /> : <EmptyState message="No equity points are available for this period." />}
    </section>
    <section className="panel table-panel strategy-trades">
      <div className="panel-heading"><div><p className="eyebrow">Long-only simulation</p><h2>Simulated trades</h2></div></div>
      {!result.simulatedTrades.length ? <EmptyState message="No strategy entries were generated in this period." /> : <div className="table-scroll"><table><thead><tr><th>Status</th><th>Entry date</th><th>Entry price</th><th>Exit date</th><th>Exit price</th><th>Quantity</th><th>Invested</th><th>Realized result</th></tr></thead><tbody>
        {result.simulatedTrades.map((trade, index) => <tr key={`${trade.entryDate}-${index}`}><td>{trade.status === "OPEN" ? "Open at period end" : "Closed"}</td><td>{trade.entryDate}</td><td>{formatMoney(trade.entryPrice)}</td><td>{trade.exitDate ?? "—"}</td><td>{trade.exitPrice == null ? "—" : formatMoney(trade.exitPrice)}</td><td>{trade.quantity.toLocaleString("en-IN")}</td><td>{formatMoney(trade.investedAmount)}</td><td>{trade.realizedResult == null ? "Unrealized" : formatMoney(trade.realizedResult)}</td></tr>)}
      </tbody></table></div>}
      <p className="panel-footnote">{result.executionConvention} {result.costTreatment}</p>
    </section>
  </section>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <article className="metric-card"><span className="metric-label">{label}</span><strong>{value}</strong></article>;
}

function EquityChart({ points }: { points: StrategyBacktest["equityCurve"] }) {
  const width = 760, height = 230, pad = 28;
  const values = points.map((point) => point.equity);
  const min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  const xStep = points.length > 1 ? (width - pad * 2) / (points.length - 1) : 0;
  const y = (value: number) => pad + (max - value) / span * (height - pad * 2);
  const path = points.map((point, index) => `${index === 0 ? "M" : "L"} ${pad + index * xStep} ${y(point.equity)}`).join(" ");
  return <div className="chart-wrap strategy-equity-chart" role="img" aria-label={`Educational backtest equity curve from ${formatMoney(values[0])} to ${formatMoney(values.at(-1))}`}>
    <svg viewBox={`0 0 ${width} ${height}`} aria-hidden="true"><line className="chart-axis" x1={pad} x2={width - pad} y1={height - pad} y2={height - pad} />{points.length > 1 && <path className="chart-line" d={path} />}<circle className="chart-point" cx={pad + (points.length - 1) * xStep} cy={y(values.at(-1)!)} r="5" /></svg>
    <div className="chart-legend"><span>{points[0].date} · {formatMoney(values[0])}</span><span>{points.at(-1)!.date} · {formatMoney(values.at(-1)!)}</span></div>
  </div>;
}

function formatPercent(value: number) {
  return `${value.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;
}

function dateDaysAgo(days: number) {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

function messageOf(reason: unknown) {
  if (reason instanceof Error && (reason as ApiError).status === 401) return "Your session has expired. Please sign in again.";
  return reason instanceof Error ? reason.message : "The request could not be completed.";
}
