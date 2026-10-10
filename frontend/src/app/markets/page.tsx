"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api, formatMoney, marketQuoteWebSocketUrl, type MarketScreenerRow, type Quote } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";

type MarketQuote = Quote & { open: number | null; high: number | null; low: number | null; volume: number | null };
type StreamStatus = "connecting" | "live" | "reconnecting" | "unavailable";
type ScreenData = { rows: MarketScreenerRow[] };
const sortOptions = ["DEFAULT", "TOP_GAINERS", "TOP_LOSERS", "HIGHEST_VOLUME", "HIGHEST_VOLATILITY", "NEAR_52_WEEK_HIGH", "NEAR_52_WEEK_LOW"];

export default function MarketsPage() {
  const [search, setSearch] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");
  const [sector, setSector] = useState("");
  const [sort, setSort] = useState("DEFAULT");
  useEffect(() => {
    const timer = setTimeout(() => setAppliedSearch(search.trim()), 250);
    return () => clearTimeout(timer);
  }, [search]);
  const load = useCallback(async (credential: string, signal: AbortSignal): Promise<ScreenData> => ({
    rows: await api.marketScreener(credential, { search: appliedSearch, sector, sort, limit: 80 }, signal),
  }), [appliedSearch, sector, sort]);
  const { data, error, loading } = useApiQuery(load);
  const symbols = useMemo(() => data?.rows.map((row) => row.symbol) ?? [], [data]);
  const stream = useMarketQuoteStream(symbols);
  const sectors = [...new Set(data?.rows.map((item) => item.sector).filter((value): value is string => Boolean(value)) ?? [])].sort();
  return <div className="content-wrap">
    <PageHeading eyebrow="Persisted market data" title="Markets" description="Explore the supported NSE universe using saved quotes and daily candles. Quote updates continue over the existing live stream." />
    <section className="panel" aria-label="Market screener controls"><div className="form-grid">
      <label>Search symbol or company<input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="e.g. TCS" maxLength={160} /></label>
      <label>Sector<select value={sector} onChange={(event) => setSector(event.target.value)}><option value="">All sectors</option>{sectors.map((value) => <option key={value}>{value}</option>)}</select></label>
      <label>View<select value={sort} onChange={(event) => setSort(event.target.value)}>{sortOptions.map((value) => <option key={value} value={value}>{value.replaceAll("_", " ").toLowerCase()}</option>)}</select></label>
    </div><div className="section-summary"><span>Results are limited to the approved 80-company universe.</span><span className="market-stream-status"><StatusBadge status={stream.status.toUpperCase()} />Quote stream {stream.status}</span></div></section>
    {loading ? <LoadingState label="Loading persisted market screen..." /> : error ? <ErrorState message={error.message} /> : !data?.rows.length ? <EmptyState message="No companies match this screen or no supported instruments are available." /> : <>
      {stream.message && <div className="notice notice-muted" role="status">{stream.message}</div>}
      <div className="table-scroll"><table><thead><tr><th>Company</th><th>LTP</th><th>Daily change</th><th>Volume</th><th>Volatility*</th><th>52-week range</th><th>Distance from high / low</th><th>Quote status</th></tr></thead><tbody>{data.rows.map((row) => {
        const live = stream.quotes[row.symbol];
        const ltp = live?.lastPrice ?? row.ltp;
        const status = live?.dataStatus ?? row.freshnessStatus;
        return <tr key={row.symbol}><td><Link href={`/companies/${encodeURIComponent(row.symbol)}`}><strong>{row.symbol}</strong><br />{row.companyName}</Link><small>{row.sector ?? "Sector unavailable"} - {row.category ?? "Category unavailable"}</small></td>
          <td>{formatMoney(status === "UNAVAILABLE" ? null : ltp)}</td><td>{row.dailyChangePercent == null || status === "UNAVAILABLE" ? "Unavailable" : `${row.dailyChangePercent.toFixed(2)}%`}</td>
          <td>{row.volume == null ? "Unavailable" : row.volume.toLocaleString("en-IN")}</td><td>{row.volatilityPercent == null ? "Insufficient candles" : `${row.volatilityPercent.toFixed(2)}%`}</td>
          <td>{row.fiftyTwoWeekHigh == null || row.fiftyTwoWeekLow == null ? "Insufficient candles" : `${formatMoney(row.fiftyTwoWeekLow)} - ${formatMoney(row.fiftyTwoWeekHigh)}`}</td>
          <td>{row.distanceFromFiftyTwoWeekHighPercent == null ? "..." : `${row.distanceFromFiftyTwoWeekHighPercent.toFixed(2)}%`} / {row.distanceFromFiftyTwoWeekLowPercent == null ? "..." : `${row.distanceFromFiftyTwoWeekLowPercent.toFixed(2)}%`}</td><td><StatusBadge status={status} /></td></tr>;
      })}</tbody></table></div><p className="muted">* Volatility is annualized sample standard deviation of available daily close-to-close returns. Historical metrics use persisted candles only; missing values remain unavailable.</p>
    </>}
  </div>;
}
function useMarketQuoteStream(symbols: string[]) {
  const symbolsKey = useMemo(() => [...new Set(symbols)].join(","), [symbols]);
  const [quotes, setQuotes] = useState<Record<string, MarketQuote>>({});
  const [status, setStatus] = useState<StreamStatus>(symbolsKey ? "connecting" : "unavailable");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    const subscribedSymbols = symbolsKey ? symbolsKey.split(",") : [];
    setQuotes({});
    if (subscribedSymbols.length === 0) {
      setStatus("unavailable");
      return;
    }

    let stopped = false;
    let retryCount = 0;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let socket: WebSocket | null = null;
    const desiredSymbols = new Set(subscribedSymbols);

    const connect = () => {
      if (stopped) return;
      setStatus(retryCount > 0 ? "reconnecting" : "connecting");
      try {
        socket = new WebSocket(marketQuoteWebSocketUrl());
      } catch {
        scheduleRetry("Could not connect to the market quote stream.");
        return;
      }

      socket.onopen = () => {
        if (stopped || !socket) return;
        setStatus("live");
        setMessage(null);
        for (const symbol of desiredSymbols) send(socket, { action: "subscribe", symbol });
      };
      socket.onmessage = (event) => {
        try {
          const payload: unknown = JSON.parse(String(event.data));
          if (!payload || typeof payload !== "object") throw new Error("Invalid message object");
          const messageObject = payload as { type?: unknown; quote?: unknown; code?: unknown; message?: unknown };
          if (messageObject.type === "quote") {
            const incomingQuote = messageObject.quote;
            if (!isMarketQuote(incomingQuote) || !desiredSymbols.has(incomingQuote.symbol)) {
              throw new Error("Unexpected quote message");
            }
            setQuotes((current) => ({ ...current, [incomingQuote.symbol]: incomingQuote }));
          } else if (messageObject.type === "error") {
            setMessage(typeof messageObject.message === "string" ? messageObject.message : "The quote stream returned an error.");
          } else if (messageObject.type !== "subscribed" && messageObject.type !== "unsubscribed") {
            throw new Error("Unknown stream message type");
          }
        } catch {
          setMessage("A malformed market quote update was ignored.");
        }
      };
      socket.onerror = () => setMessage("The market quote connection encountered an error.");
      socket.onclose = () => {
        if (!stopped) scheduleRetry("Market quote connection closed.");
      };
    };

    const scheduleRetry = (reason: string) => {
      setMessage(reason);
      if (stopped) return;
      if (retryCount < 1) {
        retryCount += 1;
        setStatus("reconnecting");
        retryTimer = setTimeout(connect, 700);
      } else {
        setStatus("unavailable");
      }
    };

    connect();
    return () => {
      stopped = true;
      if (retryTimer) clearTimeout(retryTimer);
      if (socket) {
        if (socket.readyState === WebSocket.OPEN) {
          for (const symbol of desiredSymbols) send(socket, { action: "unsubscribe", symbol });
        }
        if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) socket.close(1000, "Page changed");
      }
    };
  }, [symbolsKey]);

  return { quotes, status, message };
}

function send(socket: WebSocket, command: { action: "subscribe" | "unsubscribe"; symbol: string }) {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(command));
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

function formatTimestamp(value: string | null | undefined) {
  if (!value) return "Unavailable";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unavailable" : date.toLocaleString("en-IN", { timeZone: "Asia/Kolkata" });
}
