export type ApiError = Error & { status?: number };

export type Instrument = {
  symbol: string;
  companyName: string;
  exchange: string;
  instrumentType: string;
  currency: string;
  tradable: boolean;
};

export type Quote = {
  symbol: string;
  exchange: string;
  lastPrice: number | null;
  previousClose: number | null;
  marketTimestamp: string | null;
  providerUpdatedTimestamp: string | null;
  dataStatus: "LIVE" | "STALE" | "UNAVAILABLE" | string;
  freshnessAgeSeconds: number | null;
};

export type LearningProfile = {
  instrumentId: string;
  exchange: string;
  symbol: string;
  companyName: string;
  sector: string;
  businessType: string;
  businessDescription: string;
  majorBusinessFactors: string[];
  commonPriceDrivers: string[];
  importantRisks: string[];
  educationalObservations: string[];
  updatedAt: string;
};

export type Candle = {
  tradingDate: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

export type PriceChange = { absolute: number; percent: number } | null;

export type CompanyOverview = {
  learningProfile: LearningProfile;
  latestQuote: Quote;
  recentDailyCandles: Candle[];
  latestQuoteChange: PriceChange;
  candlePeriodChange: PriceChange;
  quoteStatus: string;
};

export type Portfolio = {
  currency: string;
  availableBalance: number;
  reservedBalance: number;
  investedCost: number;
  currentMarketValue: number | null;
  realizedPnl: number;
  unrealizedPnl: number | null;
  totalPnl: number | null;
  positionCount: number;
  valuationStatus: string;
  positions: {
    exchange: string;
    symbol: string;
    tradingMode: string;
    quantity: number;
    reservedQuantity: number;
    sellableQuantity: number;
    averageCost: number;
    currentPrice: number | null;
    marketValue: number | null;
    unrealizedPnl: number | null;
    realizedPnl: number;
    valuationStatus: string;
  }[];
};

export type Order = {
  orderId: string;
  exchange: string;
  symbol: string;
  side: string;
  orderType: string;
  tradingMode: string;
  requestedQuantity: number;
  executedQuantity: number;
  remainingQuantity: number;
  limitPrice: number | null;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type OrderPage = { content: Order[]; page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean };
export type Trade = {
  executionId: string;
  orderId: string;
  exchange: string;
  symbol: string;
  side: string;
  tradingMode: string;
  executedQuantity: number;
  executionPrice: number;
  executedAt: string;
};
export type TradePage = { content: Trade[]; page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean };
export type Cancellation = { orderId: string; status: string; releasedFunds: number; releasedSellQuantity: number; updatedAt: string };
export type PlaceOrderRequest = {
  exchange: string;
  symbol: string;
  side: "BUY" | "SELL";
  orderType: "MARKET" | "LIMIT";
  tradingMode: "DELIVERY" | "INTRADAY";
  quantity: number;
  limitPrice?: number;
};
export type PlacedOrder = PlaceOrderRequest & {
  orderId: string;
  status: string;
  requestedQuantity: number;
  executedQuantity: number;
  remainingQuantity: number;
  createdAt: string;
};

export type Watchlist = {
  id: string;
  name: string;
  items: { id: string; symbol: string; exchange: string; companyName: string; quote: Quote | null }[];
};

export type DashboardData = {
  instruments: Instrument[] | null;
  marketQuotes: Quote[] | null;
  portfolio: Portfolio | null;
  orders: OrderPage | null;
  watchlists: Watchlist[] | null;
};

async function request<T>(path: string, basicCredential: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/backend${path}`, {
    method: "GET",
    headers: { Authorization: `Basic ${basicCredential}`, Accept: "application/json" },
    cache: "no-store",
    signal,
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string; title?: string; error?: string } | null;
    const fallback = response.status === 401
      ? "Your session is not authorized. Please sign in again."
      : payload?.error ? `${payload.error} (HTTP ${response.status})` : `Request failed (${response.status}).`;
    const error = new Error(payload?.detail ?? payload?.message ?? payload?.title ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  return response.json() as Promise<T>;
}

async function mutate<T>(path: string, basicCredential: string): Promise<T> {
  const response = await fetch(`/api/backend${path}`, {
    method: "POST",
    headers: { Authorization: `Basic ${basicCredential}`, Accept: "application/json" },
    cache: "no-store",
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string; title?: string; error?: string } | null;
    const fallback = response.status === 401
      ? "Your session is not authorized. Please sign in again."
      : payload?.error ? `${payload.error} (HTTP ${response.status})` : `Request failed (${response.status}).`;
    const error = new Error(payload?.detail ?? payload?.message ?? payload?.title ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  return response.json() as Promise<T>;
}

async function placeOrderRequest(basicCredential: string, body: PlaceOrderRequest): Promise<PlacedOrder> {
  const response = await fetch("/api/backend/api/v1/orders", {
    method: "POST",
    headers: {
      Authorization: `Basic ${basicCredential}`,
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string; title?: string; error?: string } | null;
    const fallback = response.status === 400
      ? "Order rejected as invalid. Check side, order type, trading mode, positive whole-number quantity, and LIMIT price."
      : payload?.error
        ? `${payload.error} (HTTP ${response.status})`
        : `Order request failed (${response.status}).`;
    const error = new Error(payload?.detail ?? payload?.message ?? payload?.title ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  return response.json() as Promise<PlacedOrder>;
}

export const api = {
  verifyLogin: (basic: string, signal?: AbortSignal) =>
    request<Instrument[]>("/api/v1/market/instruments?query=TCS", basic, signal),
  instruments: (basic: string, signal?: AbortSignal) => request<Instrument[]>("/api/v1/market/instruments", basic, signal),
  quotes: (basic: string, symbols: string[], signal?: AbortSignal) =>
    request<Quote[]>(`/api/v1/market/quotes?${symbols.map((symbol) => `symbols=${encodeURIComponent(symbol)}`).join("&")}`, basic, signal),
  learningProfiles: (basic: string, signal?: AbortSignal) =>
    request<LearningProfile[]>("/api/v1/learning/companies", basic, signal),
  companyOverview: (basic: string, symbol: string, signal?: AbortSignal) =>
    request<CompanyOverview>(`/api/v1/learning/companies/${encodeURIComponent(symbol)}/overview`, basic, signal),
  portfolio: (basic: string, signal?: AbortSignal) => request<Portfolio>("/api/v1/portfolio/me", basic, signal),
  orders: (basic: string, options: { page?: number; size?: number; status?: string; symbol?: string } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.size ?? 20) });
    if (options.status) params.set("status", options.status);
    if (options.symbol?.trim()) params.set("symbol", options.symbol.trim());
    return request<OrderPage>(`/api/v1/orders?${params.toString()}`, basic, signal);
  },
  order: (basic: string, orderId: string, signal?: AbortSignal) =>
    request<Order>(`/api/v1/orders/${encodeURIComponent(orderId)}`, basic, signal),
  trades: (basic: string, page = 0, size = 10, signal?: AbortSignal) =>
    request<TradePage>(`/api/v1/trades?page=${page}&size=${size}`, basic, signal),
  cancelOrder: (basic: string, orderId: string) =>
    mutate<Cancellation>(`/api/v1/orders/${encodeURIComponent(orderId)}/cancel`, basic),
  placeOrder: placeOrderRequest,
  watchlists: (basic: string, signal?: AbortSignal) => request<Watchlist[]>("/api/v1/watchlists", basic, signal),
};

export async function loadDashboard(basic: string, signal: AbortSignal): Promise<DashboardData> {
  const results = await Promise.allSettled([
    api.instruments(basic, signal),
    api.quotes(basic, ["TCS", "RELIANCE", "HDFCBANK"], signal),
    api.portfolio(basic, signal),
    api.orders(basic, { page: 0, size: 5 }, signal),
    api.watchlists(basic, signal),
  ]);
  return {
    instruments: results[0].status === "fulfilled" ? results[0].value : null,
    marketQuotes: results[1].status === "fulfilled" ? results[1].value : null,
    portfolio: results[2].status === "fulfilled" ? results[2].value : null,
    orders: results[3].status === "fulfilled" ? results[3].value : null,
    watchlists: results[4].status === "fulfilled" ? results[4].value : null,
  };
}

export function encodeBasicCredential(email: string, password: string): string {
  const bytes = new TextEncoder().encode(`${email}:${password}`);
  let binary = "";
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary);
}

export function formatMoney(value: number | null | undefined, currency = "INR"): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 2 }).format(value);
}
