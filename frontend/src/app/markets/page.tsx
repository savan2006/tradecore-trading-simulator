"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api, formatMoney, type LearningProfile, type Quote } from "@/lib/api";
import { useApiQuery } from "@/lib/use-api-query";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusBadge } from "@/components/page-states";

type MarketQuote = Quote & {
  open: number | null;
  high: number | null;
  low: number | null;
  volume: number | null;
};

type MarketsData = { companies: LearningProfile[]; quotes: MarketQuote[] | null; quoteError: string | null };
type StreamStatus = "connecting" | "live" | "reconnecting" | "unavailable";

export default function MarketsPage() {
  const load = useCallback(loadMarkets, []);
  const { data, error, loading } = useApiQuery(load);
  const symbols = useMemo(() => data?.companies.map((company) => company.symbol) ?? [], [data]);
  const stream = useMarketQuoteStream(symbols);
  const initialQuotes = useMemo(() => new Map((data?.quotes ?? []).map((quote) => [quote.symbol, quote])), [data]);

  return <div className="content-wrap">
    <PageHeading eyebrow="Learning universe" title="Markets" description="Explore supported companies and business context alongside persisted market quotes that refresh automatically." />
    {loading ? <LoadingState label="Loading supported companies and persisted quotes…" /> : error ? <ErrorState message={error.message} /> : !data?.companies.length ? <EmptyState message="No learning profiles are available yet." /> : <>
      <div className="section-summary"><strong>{data.companies.length} supported companies</strong><span className="market-stream-status"><StatusBadge status={stream.status.toUpperCase()} />Quote stream {stream.status === "live" ? "connected" : stream.status === "reconnecting" ? "reconnecting" : stream.status === "connecting" ? "connecting" : "unavailable"}</span></div>
      {(data.quoteError || stream.message) && <div className="notice notice-muted" role="status">{data.quoteError && <span>Initial REST quotes could not be loaded: {data.quoteError}</span>}{stream.message && <span>{stream.message}</span>}</div>}
      <section className="company-grid">{data.companies.map((company) => {
        const quote = stream.quotes[company.symbol] ?? initialQuotes.get(company.symbol) ?? null;
        return <Link className="company-card" href={`/companies/${encodeURIComponent(company.symbol)}`} key={company.symbol}>
          <span className="company-symbol">{company.symbol}</span><span className="company-sector">{company.sector}</span><strong>{company.companyName}</strong><span className="muted">{company.businessType}</span>
          <div className="market-card-quote">
            <div className="market-card-quote-heading"><strong>LTP {formatMoney(quote?.lastPrice)}</strong><StatusBadge status={quote?.dataStatus ?? "UNAVAILABLE"} /></div>
            <div className="detail-row"><span>Open / High / Low</span><strong>{formatMoney(quote?.open)} / {formatMoney(quote?.high)} / {formatMoney(quote?.low)}</strong></div>
            <div className="detail-row"><span>Previous close</span><strong>{formatMoney(quote?.previousClose)}</strong></div>
            <div className="detail-row"><span>Volume</span><strong>{quote?.volume == null ? "Unavailable" : quote.volume.toLocaleString("en-IN")}</strong></div>
            <div className="market-provider-time">Provider update: {formatTimestamp(quote?.providerUpdatedTimestamp)}</div>
          </div>
          <span className="text-link">Open company overview →</span>
        </Link>;
      })}</section>
    </>}
  </div>;
}

async function loadMarkets(credential: string, signal: AbortSignal): Promise<MarketsData> {
  const companies = await api.learningProfiles(credential, signal);
  if (companies.length === 0) return { companies, quotes: [], quoteError: null };
  try {
    const quotes = await api.quotes(credential, companies.map((company) => company.symbol), signal);
    return { companies, quotes: quotes as MarketQuote[], quoteError: null };
  } catch (error) {
    if (signal.aborted) throw error;
    return { companies, quotes: null, quoteError: error instanceof Error ? error.message : "The quote request failed." };
  }
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

function marketQuoteWebSocketUrl() {
  const configuredBase = process.env.NEXT_PUBLIC_API_BASE_URL;
  const base = configuredBase || `${window.location.protocol}//${window.location.hostname}:8080`;
  const url = new URL("/ws/market-quotes", base);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function formatTimestamp(value: string | null | undefined) {
  if (!value) return "Unavailable";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unavailable" : date.toLocaleString();
}
