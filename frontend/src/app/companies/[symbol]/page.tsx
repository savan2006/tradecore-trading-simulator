"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, formatMoney, type Quote } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";
import { useAuth } from "@/lib/auth-context";
import type { ApiError, PlacedOrder } from "@/lib/api";

type MarketQuote = Quote & { open: number | null; high: number | null; low: number | null; volume: number | null };
type StreamStatus = "connecting" | "live" | "reconnecting" | "unavailable";

export default function CompanyPage() {
  const { symbol: rawSymbol } = useParams<{ symbol: string }>();
  const symbol = decodeURIComponent(rawSymbol);
  const load = useCallback((credential: string, signal: AbortSignal) => api.companyOverview(credential, symbol, signal), [symbol]);
  const { data, error, loading } = useApiQuery(load, symbol);
  const { session } = useAuth();
  const stream = useMarketQuoteStream(data?.learningProfile.symbol ?? "");

  if (loading) return <div className="content-wrap"><Link href="/markets" className="back-link">← Markets</Link><LoadingState label={`Loading ${symbol} overview…`} /></div>;
  if (error || !data) return <div className="content-wrap"><Link href="/markets" className="back-link">← Markets</Link><ErrorState message={error?.message ?? "Company overview is unavailable."} /></div>;

  const profile = data.learningProfile;
  const quote = stream.quotes[symbol] ?? data.latestQuote as MarketQuote;
  const quoteStatus = quote?.dataStatus ?? data.quoteStatus;
  const stale = quoteStatus === "STALE";
  const unavailable = quoteStatus === "UNAVAILABLE";
  const quoteChange = quote?.lastPrice != null && quote.previousClose != null
    ? { absolute: quote.lastPrice - quote.previousClose, percent: quote.previousClose === 0 ? 0 : ((quote.lastPrice - quote.previousClose) / quote.previousClose) * 100 }
    : data.latestQuoteChange;
  return <div className="content-wrap company-page">
    <Link href="/markets" className="back-link">← Markets</Link>
    <section className="company-hero">
      <div><p className="eyebrow">{profile.exchange} · {profile.symbol} · {profile.sector}</p><h1>{profile.companyName}</h1><p className="company-subtitle">{profile.businessType}</p></div>
      <div className="quote-summary"><StatusBadge status={quoteStatus} /><strong>{formatMoney(quote?.lastPrice)}</strong><span className="muted">Quote refreshes automatically · stream {stream.status}</span>{quoteChange && <span className={quoteChange.absolute >= 0 ? "positive-value" : "negative-value"}>{signed(quoteChange.absolute)} ({signed(quoteChange.percent)}%) vs previous close</span>}</div>
    </section>

    {stream.message && <div className="notice notice-muted" role="status">{stream.message}</div>}

    {(stale || unavailable) && <div className={`notice ${stale ? "notice-stale" : "notice-muted"}`} role="status"><strong>{stale ? "Quote is stale" : "Quote unavailable"}</strong><span>{stale ? "This persisted quote is outside the freshness window. Price changes are not calculated from it." : "No persisted quote is available. The overview does not estimate a current price."}</span></div>}

    <section className="company-layout">
      <div className="company-main">
        <article className="panel"><p className="eyebrow">Business overview</p><h2>What the company does</h2><p className="body-copy">{profile.businessDescription}</p></article>
        <article className="panel chart-panel"><div className="panel-heading"><div><p className="eyebrow">Persisted market history</p><h2>Recent daily prices</h2></div><div className="chart-status"><StatusBadge status={quoteStatus} /><span className="muted">Latest quote status</span></div></div>
          {data.recentDailyCandles.length ? <><PriceChart candles={data.recentDailyCandles} /><div className="chart-legend"><span>{data.recentDailyCandles[0].tradingDate}</span><span>{data.recentDailyCandles.at(-1)?.tradingDate}</span></div>{data.candlePeriodChange && <p className="period-change">Change across displayed candles: <strong>{signed(data.candlePeriodChange.absolute)}</strong> ({signed(data.candlePeriodChange.percent)}%)</p>}</> : <EmptyState message="No persisted daily candles are available." />}
        </article>
        <OrderPanel exchange={profile.exchange} symbol={profile.symbol} credential={session?.basicCredential ?? null} />
        <div className="learning-grid">
          <LearningList title="Business factors" items={profile.majorBusinessFactors} />
          <LearningList title="Common price drivers" items={profile.commonPriceDrivers} />
          <LearningList title="Important risks" items={profile.importantRisks} />
          <LearningList title="What to observe" items={profile.educationalObservations} />
        </div>
      </div>
      <aside className="panel quote-detail"><p className="eyebrow">Quote details</p><h2>Latest persisted quote</h2><div className="detail-row"><span>Last price</span><strong>{formatMoney(quote?.lastPrice)}</strong></div><div className="detail-row"><span>Open / High / Low</span><strong>{formatMoney(quote?.open)} / {formatMoney(quote?.high)} / {formatMoney(quote?.low)}</strong></div><div className="detail-row"><span>Previous close</span><strong>{formatMoney(quote?.previousClose)}</strong></div><div className="detail-row"><span>Volume</span><strong>{quote?.volume == null ? "Unavailable" : quote.volume.toLocaleString("en-IN")}</strong></div><div className="detail-row"><span>Data status</span><StatusBadge status={quoteStatus} /></div><div className="detail-row"><span>Market timestamp</span><strong>{formatDate(quote?.marketTimestamp ?? null)}</strong></div><div className="detail-row"><span>Provider update</span><strong>{formatDate(quote?.providerUpdatedTimestamp ?? null)}</strong></div><div className="detail-row"><span>Quote age</span><strong>{quote?.freshnessAgeSeconds == null ? "—" : `${Math.floor(quote.freshnessAgeSeconds / 60)} min`}</strong></div><p className="fine-print">Displayed prices are persisted backend data. Learning content is educational and is not a trading recommendation.</p></aside>
    </section>
  </div>;
}

function OrderPanel({ exchange, symbol, credential }: { exchange: string; symbol: string; credential: string | null }) {
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [orderType, setOrderType] = useState<"MARKET" | "LIMIT">("MARKET");
  const [tradingMode, setTradingMode] = useState<"DELIVERY" | "INTRADAY">("DELIVERY");
  const [quantity, setQuantity] = useState("1");
  const [limitPrice, setLimitPrice] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [placed, setPlaced] = useState<PlacedOrder | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!credential) return;
    setSending(true);
    setError(null);
    setPlaced(null);
    try {
      const body = {
        exchange,
        symbol,
        side,
        orderType,
        tradingMode,
        quantity: Number(quantity),
        ...(orderType === "LIMIT" ? { limitPrice: Number(limitPrice) } : {}),
      };
      setPlaced(await api.placeOrder(credential, body));
    } catch (cause) {
      const requestError = cause as ApiError;
      setError(requestError.message || "The order could not be placed.");
    } finally {
      setSending(false);
    }
  }

  return <section className="panel order-panel" aria-labelledby="order-panel-title">
    <p className="eyebrow">Virtual trading</p><h2 id="order-panel-title">Place an order</h2>
    {!credential ? <p className="muted">Sign in to place a paper order. <Link href="/login">Go to login</Link></p> : <>
      <form className="order-form" onSubmit={submit}>
        <label>Side<select value={side} onChange={(event) => setSide(event.target.value as "BUY" | "SELL")}><option value="BUY">BUY</option><option value="SELL">SELL</option></select></label>
        <label>Order type<select value={orderType} onChange={(event) => setOrderType(event.target.value as "MARKET" | "LIMIT")}><option value="MARKET">MARKET</option><option value="LIMIT">LIMIT</option></select></label>
        <label>Mode<select value={tradingMode} onChange={(event) => setTradingMode(event.target.value as "DELIVERY" | "INTRADAY")}><option value="DELIVERY">DELIVERY</option><option value="INTRADAY">INTRADAY</option></select></label>
        <label>Quantity<input type="number" min="1" step="1" required value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label>
        {orderType === "LIMIT" && <label>Limit price (INR)<input type="number" min="0.000001" step="0.000001" required value={limitPrice} onChange={(event) => setLimitPrice(event.target.value)} /></label>}
        <button type="submit" className="primary-button" disabled={sending}>{sending ? "Sending…" : "Place paper order"}</button>
      </form>
      {error && <p className="notice notice-stale order-message" role="alert">{error}</p>}
      {placed && <div className="notice notice-success order-message" role="status"><strong>Order placed · {placed.status}</strong><span>{placed.side} {placed.requestedQuantity} {placed.symbol} · {placed.orderType} · {placed.tradingMode}. It remains pending and has not been executed.</span><Link href="/orders">View orders</Link></div>}
    </>}
  </section>;
}

function LearningList({ title, items }: { title: string; items: string[] }) {
  return <article className="panel learning-panel"><p className="eyebrow">Learning notes</p><h2>{title}</h2><ul>{items.map((item) => <li key={item}>{item}</li>)}</ul></article>;
}

function PriceChart({ candles }: { candles: { tradingDate: string; close: number }[] }) {
  const values = candles.map((candle) => candle.close);
  const minimum = Math.min(...values);
  const maximum = Math.max(...values);
  const span = maximum - minimum || 1;
  const points = values.map((value, index) => {
    const x = values.length === 1 ? 300 : 20 + (index / (values.length - 1)) * 560;
    const y = 170 - ((value - minimum) / span) * 140;
    return `${x},${y}`;
  }).join(" ");
  return <div className="chart-wrap"><svg viewBox="0 0 600 190" role="img" aria-label={`Daily closing prices from ${candles[0].tradingDate} to ${candles.at(-1)?.tradingDate}`}>
    <line x1="20" y1="170" x2="580" y2="170" className="chart-axis" /><line x1="20" y1="30" x2="20" y2="170" className="chart-axis" />
    <polyline points={points} className="chart-line" />
    {candles.map((candle, index) => {
      const x = values.length === 1 ? 300 : 20 + (index / (values.length - 1)) * 560;
      const y = 170 - ((candle.close - minimum) / span) * 140;
      return <circle key={candle.tradingDate} cx={x} cy={y} r="3.5" className="chart-point"><title>{candle.tradingDate}: {formatMoney(candle.close)}</title></circle>;
    })}
    <text x="22" y="18" className="chart-label">{formatMoney(maximum)}</text><text x="22" y="187" className="chart-label">{formatMoney(minimum)}</text>
  </svg></div>;
}

function useMarketQuoteStream(symbol: string) {
  const [quote, setQuote] = useState<MarketQuote | null>(null);
  const [status, setStatus] = useState<StreamStatus>(symbol ? "connecting" : "unavailable");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    setQuote(null);
    if (!symbol) { setStatus("unavailable"); return; }
    let stopped = false;
    let retryCount = 0;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let socket: WebSocket | null = null;
    const send = (action: "subscribe" | "unsubscribe") => {
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ action, symbol }));
    };
    const retry = (reason: string) => {
      setMessage(reason);
      if (stopped) return;
      if (retryCount < 1) {
        retryCount += 1;
        setStatus("reconnecting");
        retryTimer = setTimeout(connect, 700);
      } else setStatus("unavailable");
    };
    const connect = () => {
      if (stopped) return;
      setStatus(retryCount ? "reconnecting" : "connecting");
      try { socket = new WebSocket(marketQuoteWebSocketUrl()); }
      catch { retry("Could not connect to the market quote stream."); return; }
      socket.onopen = () => {
        if (stopped) return;
        setStatus("live"); setMessage(null); send("subscribe");
      };
      socket.onmessage = (event) => {
        try {
          const payload: unknown = JSON.parse(String(event.data));
          if (!payload || typeof payload !== "object") throw new Error();
          const item = payload as { type?: unknown; quote?: unknown; message?: unknown };
          if (item.type === "quote") {
            if (!isMarketQuote(item.quote) || item.quote.symbol !== symbol) throw new Error();
            setQuote(item.quote);
          } else if (item.type === "error") setMessage(typeof item.message === "string" ? item.message : "The quote stream returned an error.");
          else if (item.type !== "subscribed" && item.type !== "unsubscribed") throw new Error();
        } catch { setMessage("A malformed market quote update was ignored."); }
      };
      socket.onerror = () => setMessage("The market quote connection encountered an error.");
      socket.onclose = () => { if (!stopped) retry("Market quote connection closed."); };
    };
    connect();
    return () => {
      stopped = true;
      if (retryTimer) clearTimeout(retryTimer);
      if (socket) {
        if (socket.readyState === WebSocket.OPEN) send("unsubscribe");
        if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) socket.close(1000, "Page changed");
      }
    };
  }, [symbol]);
  return { quote: quote ? { [quote.symbol]: quote } : {}, quotes: quote ? { [quote.symbol]: quote } : {}, status, message };
}

function isMarketQuote(value: unknown): value is MarketQuote {
  if (!value || typeof value !== "object") return false;
  const quote = value as Record<string, unknown>;
  const nullableNumber = (field: string) => quote[field] === null || typeof quote[field] === "number";
  const nullableString = (field: string) => quote[field] === null || typeof quote[field] === "string";
  return typeof quote.symbol === "string" && typeof quote.exchange === "string"
    && ["LIVE", "STALE", "UNAVAILABLE"].includes(String(quote.dataStatus))
    && ["lastPrice", "open", "high", "low", "previousClose", "volume", "freshnessAgeSeconds"].every(nullableNumber)
    && ["marketTimestamp", "providerUpdatedTimestamp"].every(nullableString);
}

function marketQuoteWebSocketUrl() {
  const base = process.env.NEXT_PUBLIC_API_BASE_URL || `${window.location.protocol}//${window.location.hostname}:8080`;
  const url = new URL("/ws/market-quotes", base);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function signed(value: number) {
  return `${value > 0 ? "+" : ""}${value.toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString() : "—";
}
