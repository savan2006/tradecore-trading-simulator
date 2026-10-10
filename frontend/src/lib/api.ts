export type ApiError = Error & { status?: number };

export function marketQuoteWebSocketUrl() {
  const configuredUrl = process.env.NEXT_PUBLIC_WEBSOCKET_URL;
  const base = process.env.NEXT_PUBLIC_API_BASE_URL || `${window.location.protocol}//${window.location.hostname}:8080`;
  const url = configuredUrl ? new URL(configuredUrl) : new URL("/ws/market-quotes", base);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  if (window.location.protocol === "https:") url.protocol = "wss:";
  return url.toString();
}

function notifyUnauthorized(status: number) {
  if (status === 401 && typeof window !== "undefined") {
    window.dispatchEvent(new Event("tradecore:session-expired"));
  }
}

function fallbackForStatus(status: number, fallback: string) {
  if (status === 401) return "Your session has expired. Please sign in again.";
  if (status === 403) return "You are not allowed to perform this action.";
  return fallback;
}

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

export type MarketScreenerRow = {
  symbol: string;
  companyName: string;
  sector: string | null;
  category: string | null;
  ltp: number | null;
  dailyChangePercent: number | null;
  volume: number | null;
  volatilityPercent: number | null;
  fiftyTwoWeekHigh: number | null;
  fiftyTwoWeekLow: number | null;
  distanceFromFiftyTwoWeekHighPercent: number | null;
  distanceFromFiftyTwoWeekLowPercent: number | null;
  freshnessStatus: string;
  marketStatus: string;
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

export type CompanyComparison = {
  from: string;
  to: string;
  companies: {
    symbol: string;
    companyName: string;
    sector: string;
    businessType: string;
    businessDescription: string;
    startDate: string | null;
    startClose: number | null;
    endDate: string | null;
    endClose: number | null;
    absoluteReturn: number | null;
    percentageReturn: number | null;
    annualizedVolatilityPercent: number | null;
    maximumDrawdownPercent: number | null;
    numberOfTradingDays: number;
    latestAvailableCandleDate: string | null;
    insufficientHistoricalData: boolean;
    dataNote: string | null;
  }[];
  indexedSeries: { symbol: string; points: { date: string; indexedValue: number }[] }[];
};

export type Candle = {
  tradingDate: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

export type StrategyBacktestRequest = {
  symbol: string;
  fromDate: string;
  toDate: string;
  strategy: "SIMPLE_MOVING_AVERAGE_CROSSOVER" | "RSI_MEAN_REVERSION";
  startingCapital: number;
  fastPeriod?: number;
  slowPeriod?: number;
  rsiPeriod?: number;
  oversoldThreshold?: number;
  overboughtThreshold?: number;
};

export type StrategyBacktest = {
  strategy: StrategyBacktestRequest["strategy"];
  symbol: string;
  fromDate: string;
  toDate: string;
  startingCapital: number;
  endingCapital: number;
  totalReturnPercent: number;
  realizedPnl: number;
  numberOfTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRatePercent: number;
  averageWinningTrade: number | null;
  averageLosingTrade: number | null;
  maximumDrawdownPercent: number;
  buyAndHoldReturnPercent: number;
  equityCurve: { date: string; equity: number }[];
  simulatedTrades: {
    entryDate: string;
    entryPrice: number;
    exitDate: string | null;
    exitPrice: number | null;
    quantity: number;
    investedAmount: number;
    realizedResult: number | null;
    status: "OPEN" | "CLOSED";
  }[];
  executionConvention: string;
  costTreatment: string;
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
  triggerPrice: number | null;
  status: string;
  createdAt: string;
  updatedAt: string;
};
export type OrderModification = { quantity: number; limitPrice?: number; triggerPrice?: number };
export type OrderEvent = { type: string; previousState: string | null; newState: string; occurredAt: string };
export type MarketSession = {
  tradingDate: string;
  status: "OPEN" | "CLOSED";
  reason: "WEEKEND" | "HOLIDAY" | "OUTSIDE_HOURS" | "SPECIAL_SESSION" | null;
  openTime: string | null;
  closeTime: string | null;
  nextOpenAt: string | null;
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
export type TradeJournalEntry = {
  id: string;
  orderId: string;
  exchange: string;
  symbol: string;
  side: string;
  tradingMode: string;
  quantity: number;
  thesis: string;
  strategyTag: string | null;
  wentWell: string | null;
  wentWrong: string | null;
  lessonLearned: string | null;
  rating: number | null;
  createdAt: string;
  updatedAt: string;
};
export type TradeJournalPage = { content: TradeJournalEntry[]; page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean };
export type Cancellation = { orderId: string; status: string; releasedFunds: number; releasedSellQuantity: number; updatedAt: string };
export type PlaceOrderRequest = {
  exchange: string;
  symbol: string;
  side: "BUY" | "SELL";
  orderType: "MARKET" | "LIMIT" | "STOP_MARKET";
  tradingMode: "DELIVERY" | "INTRADAY";
  quantity: number;
  limitPrice?: number;
  triggerPrice?: number;
};
export type PlacedOrder = PlaceOrderRequest & {
  orderId: string;
  status: string;
  requestedQuantity: number;
  executedQuantity: number;
  remainingQuantity: number;
  createdAt: string;
};
export type OrderPreview = {
  valid: boolean;
  validationErrors: string[];
  exchange: string;
  symbol: string;
  side: string;
  orderType: string;
  tradingMode: string;
  quantity: number;
  currentEligiblePrice: number | null;
  estimatedOrderValue: number | null;
  estimatedBuyReservation: number | null;
  sellableQuantity: number | null;
  applicableRiskLimitFailures: string[];
  quoteFreshnessStatus: string;
  quoteFreshnessAgeSeconds: number | null;
  marketSessionEligibility: string;
  marketSessionStatus: string;
};

export type Watchlist = {
  id: string;
  name: string;
  items: { id: string; symbol: string; exchange: string; companyName: string; quote: Quote | null }[];
};

export type PriceAlert = {
  id: string;
  watchlistId: string;
  instrumentId: string;
  symbol: string;
  exchange: string;
  condition: "ABOVE" | "BELOW" | string;
  targetPrice: number;
  active: boolean;
  createdAt: string;
  triggeredAt: string | null;
};

export type UnreadNotificationCount = { unreadCount: number };
export type NotificationItem = {
  id: string;
  notificationType: string;
  title: string;
  message: string;
  createdAt: string;
  readAt: string | null;
};
export type NotificationPage = { items: NotificationItem[]; page: number; size: number; totalElements: number; totalPages: number };
export type ReadAllNotificationsResult = { updatedCount: number };
export type RiskLimit = {
  scope: string;
  limitType: string;
  configuredValue: number;
  exchange: string | null;
  symbol: string | null;
  currentUsage: number | null;
  remainingValue: number | null;
  effectiveFrom: string | null;
  effectiveUntil: string | null;
};
export type AdminRiskLimit = {
  id: string;
  accountId: string | null;
  accountEmail: string | null;
  scope: "GLOBAL" | "ACCOUNT" | "INSTRUMENT" | string;
  limitType: string;
  configuredValue: number;
  exchange: string | null;
  symbol: string | null;
  enabled: boolean;
  effectiveNow: boolean;
  effectiveFrom: string | null;
  effectiveUntil: string | null;
  createdAt: string;
};
export type AdminRiskLimitRequest = {
  scope: "GLOBAL" | "ACCOUNT" | "INSTRUMENT";
  limitType: string;
  accountId: string | null;
  exchange: string | null;
  symbol: string | null;
  configuredValue: number;
  enabled: boolean;
  effectiveFrom: string | null;
  effectiveUntil: string | null;
};
export type PerformancePoint = {
  symbol: string;
  tradingMode: string;
  closedAt: string;
  realizedPnl: number;
};
export type ClosedPositionPerformance = { symbol: string; tradingMode: string; realizedPnl: number };
export type Performance = {
  totalOrders: number;
  filledOrders: number;
  cancelledOrders: number;
  totalExecutions: number;
  currentOpenPositions: number;
  realizedPnl: number;
  unrealizedPnl: number | null;
  totalPnl: number | null;
  currentPortfolioValue: number | null;
  valuationStatus: string;
  buyOrders: number;
  sellOrders: number;
  deliveryOrders: number;
  intradayOrders: number;
  profitableClosedPositions: number;
  losingClosedPositions: number;
  bestRealizedPosition: ClosedPositionPerformance | null;
  worstRealizedPosition: ClosedPositionPerformance | null;
  recentPerformance: PerformancePoint[];
};
export type RegistrationResult = {
  userId: string;
  email: string;
  displayName: string;
  accountId: string;
  accountStatus: string;
  currency: string;
  availableBalance: number;
  reservedBalance: number;
  accountCreatedAt: string;
};
export type AdminOverview = {
  totalUsers: number;
  activeTradingAccounts: number;
  pendingOrders: number;
  filledOrders: number;
  cancelledOrders: number;
  openPositions: number;
  unreadNotifications: number;
  supportedInstruments: number;
  latestMarketDataRefreshAt: string | null;
  latestMarketDataRefreshStatus: string;
};
export type AdminUser = { id: string; email: string; displayName: string; role: string; status: string; createdAt: string };
export type AdminUserPage = { content: AdminUser[]; page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean };
export type AdminOrder = {
  orderId: string;
  exchange: string;
  symbol: string;
  side: string;
  orderType: string;
  tradingMode: string;
  requestedQuantity: number;
  executedQuantity: number;
  remainingQuantity: number;
  status: string;
  createdAt: string;
  updatedAt: string;
};
export type AdminOrderPage = { content: AdminOrder[]; page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean };
export type AdminMarketStatus = {
  quoteRefreshEnabled: boolean;
  quoteRefreshInterval: string;
  lastQuoteRefreshAttemptAt: string | null;
  lastQuoteRefreshOutcome: string;
  lastSuccessfulQuoteRefreshAt: string | null;
  quoteRefreshFailureCount: number;
  candleRefreshEnabled: boolean;
  candleRefreshCron: string;
  lastCandleRefreshAttemptAt: string | null;
  lastCandleRefreshOutcome: string;
  lastSuccessfulCandleRefreshAt: string | null;
  candleRefreshFailureCount: number;
  orderExecutionEnabled: boolean;
  orderExecutionInterval: string;
  squareOffCheckInterval: string;
  latestPersistedQuoteAt: string | null;
    jobs: Array<{ job: string; startedAt: string | null; finishedAt: string | null; durationMs: number | null;
      outcome: string; processed: number; updated: number; skipped: number; failed: number; lastError: string | null }>;
};
export type AdminAuditLog = {
  id: string;
  actorId: string | null;
  actorEmail: string | null;
  action: string;
  targetType: string;
  targetId: string | null;
  occurredAt: string;
  outcome: string;
};
export type AdminAuditLogPage = {
  content: AdminAuditLog[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

export type DashboardData = {
  instruments: Instrument[] | null;
  marketQuotes: Quote[] | null;
  portfolio: Portfolio | null;
  orders: OrderPage | null;
  watchlists: Watchlist[] | null;
  unreadNotifications: UnreadNotificationCount | null;
  errors: Partial<Record<"instruments" | "marketQuotes" | "portfolio" | "orders" | "watchlists" | "unreadNotifications", string>>;
};

async function request<T>(path: string, basicCredential: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/backend${path}`, {
    method: "GET",
    headers: { Accept: "application/json" },
    cache: "no-store",
    signal,
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string; title?: string; error?: string } | null;
    notifyUnauthorized(response.status);
    const fallback = fallbackForStatus(response.status, payload?.error ? `${payload.error} (HTTP ${response.status})` : `Request failed (${response.status}).`);
    const error = new Error(response.status === 403 ? fallback : payload?.detail ?? payload?.message ?? payload?.title ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  return response.json() as Promise<T>;
}

async function mutate<T>(path: string, basicCredential: string): Promise<T> {
  const response = await fetch(`/api/backend${path}`, {
    method: "POST",
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string; title?: string; error?: string } | null;
    notifyUnauthorized(response.status);
    const fallback = fallbackForStatus(response.status, payload?.error ? `${payload.error} (HTTP ${response.status})` : `Request failed (${response.status}).`);
    const error = new Error(response.status === 403 ? fallback : payload?.detail ?? payload?.message ?? payload?.title ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  return response.json() as Promise<T>;
}

async function write<T>(method: "POST" | "PUT" | "DELETE", path: string, basicCredential: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  const response = await fetch(`/api/backend${path}`, {
    method,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    cache: "no-store",
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string; title?: string; error?: string } | null;
    notifyUnauthorized(response.status);
    const fallback = fallbackForStatus(response.status, payload?.error ? `${payload.error} (HTTP ${response.status})` : `Request failed (${response.status}).`);
    const error = new Error(response.status === 403 ? fallback : payload?.detail ?? payload?.message ?? payload?.title ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

async function registerRequest(body: { displayName: string; email: string; password: string }): Promise<RegistrationResult> {
  const response = await fetch("/api/backend/api/v1/auth/register", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as {
      detail?: string; message?: string; title?: string; error?: string;
    } | null;
    notifyUnauthorized(response.status);
    const fallback = response.status === 409
      ? "An account with this email is already registered."
      : response.status === 400
        ? "The server rejected these details. Check your display name, email, and password requirements."
          : response.status === 401
            ? "Your session has expired. Please sign in again."
            : response.status === 403
              ? "You are not allowed to create an account."
          : `Registration failed (${response.status}).`;
    const error = new Error(response.status === 403 ? fallback : payload?.detail ?? payload?.message ?? payload?.title ?? payload?.error ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  return response.json() as Promise<RegistrationResult>;
}

async function placeOrderRequest(basicCredential: string, body: PlaceOrderRequest, idempotencyKey: string): Promise<PlacedOrder> {
  const response = await fetch("/api/backend/api/v1/orders", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string; title?: string; error?: string } | null;
    notifyUnauthorized(response.status);
    const fallback = response.status === 400
      ? "Order rejected as invalid. Check side, order type, trading mode, positive whole-number quantity, and required order prices."
      : response.status === 401
        ? "Your session has expired. Please sign in again."
        : response.status === 403
          ? "You are not allowed to place this order."
      : payload?.error
        ? `${payload.error} (HTTP ${response.status})`
        : `Order request failed (${response.status}).`;
    const error = new Error(response.status === 403 ? fallback : payload?.detail ?? payload?.message ?? payload?.title ?? fallback) as ApiError;
    error.status = response.status;
    throw error;
  }
  return response.json() as Promise<PlacedOrder>;
}

export const api = {
  register: registerRequest,
  verifyLogin: (basic: string, signal?: AbortSignal) =>
    request<Instrument[]>("/api/v1/market/instruments?query=TCS", basic, signal),
  instruments: (basic: string, signal?: AbortSignal) => request<Instrument[]>("/api/v1/market/instruments", basic, signal),
  strategyBacktest: (basic: string, input: StrategyBacktestRequest) =>
    write<StrategyBacktest>("POST", "/api/v1/strategy-lab/backtests", basic, input),
  quotes: (basic: string, symbols: string[], signal?: AbortSignal) =>
    request<Quote[]>(`/api/v1/market/quotes?${symbols.map((symbol) => `symbols=${encodeURIComponent(symbol)}`).join("&")}`, basic, signal),
  marketScreener: (basic: string, options: { search?: string; sector?: string; sort?: string; limit?: number } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    Object.entries(options).forEach(([key, value]) => { if (value != null && value !== "") params.set(key, String(value)); });
    return request<MarketScreenerRow[]>(`/api/v1/market/screener?${params.toString()}`, basic, signal);
  },
  learningProfiles: (basic: string, signal?: AbortSignal) =>
    request<LearningProfile[]>("/api/v1/learning/companies", basic, signal),
  companyOverview: (basic: string, symbol: string, signal?: AbortSignal) =>
    request<CompanyOverview>(`/api/v1/learning/companies/${encodeURIComponent(symbol)}/overview`, basic, signal),
  compareCompanies: (basic: string, symbols: string[], from: string, to: string, signal?: AbortSignal) => {
    const params = new URLSearchParams({ symbols: symbols.join(","), from, to });
    return request<CompanyComparison>(`/api/v1/learning/compare?${params.toString()}`, basic, signal);
  },
  portfolio: (basic: string, signal?: AbortSignal) => request<Portfolio>("/api/v1/portfolio/me", basic, signal),
  orders: (basic: string, options: { page?: number; size?: number; status?: string; symbol?: string } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.size ?? 20) });
    if (options.status) params.set("status", options.status);
    if (options.symbol?.trim()) params.set("symbol", options.symbol.trim());
    return request<OrderPage>(`/api/v1/orders?${params.toString()}`, basic, signal);
  },
  order: (basic: string, orderId: string, signal?: AbortSignal) =>
    request<Order>(`/api/v1/orders/${encodeURIComponent(orderId)}`, basic, signal),
  orderEvents: (basic: string, orderId: string, signal?: AbortSignal) =>
    request<OrderEvent[]>(`/api/v1/orders/${encodeURIComponent(orderId)}/events`, basic, signal),
  marketSession: (basic: string, signal?: AbortSignal) =>
    request<MarketSession>("/api/v1/market/session", basic, signal),
  trades: (basic: string, page = 0, size = 10, signal?: AbortSignal) =>
    request<TradePage>(`/api/v1/trades?page=${page}&size=${size}`, basic, signal),
  journal: (basic: string, page = 0, size = 20, signal?: AbortSignal) =>
    request<TradeJournalPage>(`/api/v1/journal?page=${Math.max(0, page)}&size=${Math.min(100, Math.max(1, size))}`, basic, signal),
  journalEntry: (basic: string, id: string, signal?: AbortSignal) =>
    request<TradeJournalEntry>(`/api/v1/journal/${encodeURIComponent(id)}`, basic, signal),
  createJournalEntry: (basic: string, input: {
    orderId: string; thesis: string; strategyTag?: string; wentWell?: string;
    wentWrong?: string; lessonLearned?: string; rating?: number | null;
  }) => write<TradeJournalEntry>("POST", "/api/v1/journal", basic, input),
  updateJournalEntry: (basic: string, id: string, input: {
    thesis: string; strategyTag?: string; wentWell?: string;
    wentWrong?: string; lessonLearned?: string; rating?: number | null;
  }) => write<TradeJournalEntry>("PUT", `/api/v1/journal/${encodeURIComponent(id)}`, basic, input),
  deleteJournalEntry: (basic: string, id: string) =>
    write<void>("DELETE", `/api/v1/journal/${encodeURIComponent(id)}`, basic),
  cancelOrder: (basic: string, orderId: string) =>
    mutate<Cancellation>(`/api/v1/orders/${encodeURIComponent(orderId)}/cancel`, basic),
  modifyOrder: (basic: string, orderId: string, input: OrderModification, idempotencyKey: string) =>
    write<Order>("PUT", `/api/v1/orders/${encodeURIComponent(orderId)}`, basic, input, idempotencyKey),
  placeOrder: placeOrderRequest,
  previewOrder: (basic: string, body: PlaceOrderRequest) =>
    write<OrderPreview>("POST", "/api/v1/orders/preview", basic, body),
  watchlists: (basic: string, signal?: AbortSignal) => request<Watchlist[]>("/api/v1/watchlists", basic, signal),
  createWatchlist: (basic: string, name: string) => write<Watchlist>("POST", "/api/v1/watchlists", basic, { name }),
  renameWatchlist: (basic: string, id: string, name: string) =>
    write<Watchlist>("PUT", `/api/v1/watchlists/${encodeURIComponent(id)}`, basic, { name }),
  deleteWatchlist: (basic: string, id: string) => write<void>("DELETE", `/api/v1/watchlists/${encodeURIComponent(id)}`, basic),
  addWatchlistItem: (basic: string, id: string, exchange: string, symbol: string) =>
    write<Watchlist>("POST", `/api/v1/watchlists/${encodeURIComponent(id)}/items`, basic, { exchange, symbol }),
  removeWatchlistItem: (basic: string, id: string, symbol: string) =>
    write<void>("DELETE", `/api/v1/watchlists/${encodeURIComponent(id)}/items/${encodeURIComponent(symbol)}`, basic),
  priceAlerts: (basic: string, signal?: AbortSignal) => request<PriceAlert[]>("/api/v1/alerts", basic, signal),
  createPriceAlert: (basic: string, input: { watchlistId: string; instrumentId: string; condition: "ABOVE" | "BELOW"; targetPrice: number }) =>
    write<PriceAlert>("POST", "/api/v1/alerts", basic, input),
  deletePriceAlert: (basic: string, id: string) => write<void>("DELETE", `/api/v1/alerts/${encodeURIComponent(id)}`, basic),
  unreadNotificationCount: (basic: string, signal?: AbortSignal) =>
    request<UnreadNotificationCount>("/api/v1/notifications/unread-count", basic, signal),
  notifications: (basic: string, options: { page?: number; size?: number; unreadOnly?: boolean } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({
      page: String(Math.max(0, Math.floor(options.page ?? 0))),
      size: String(Math.min(100, Math.max(1, Math.floor(options.size ?? 20)))),
      unreadOnly: String(options.unreadOnly ?? false),
    });
    return request<NotificationPage>(`/api/v1/notifications?${params.toString()}`, basic, signal);
  },
  markNotificationRead: (basic: string, id: string) =>
    write<NotificationItem>("POST", `/api/v1/notifications/${encodeURIComponent(id)}/read`, basic),
  markAllNotificationsRead: (basic: string) =>
    write<ReadAllNotificationsResult>("POST", "/api/v1/notifications/read-all", basic),
  adminOverview: (basic: string, signal?: AbortSignal) =>
    request<AdminOverview>("/api/v1/admin/overview", basic, signal),
  adminUsers: (basic: string, options: { page?: number; size?: number; search?: string } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.size ?? 20) });
    if (options.search?.trim()) params.set("search", options.search.trim());
    return request<AdminUserPage>(`/api/v1/admin/users?${params.toString()}`, basic, signal);
  },
  adminOrders: (basic: string, options: {
    page?: number; size?: number; status?: string; symbol?: string; tradingMode?: string; from?: string; to?: string;
  } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.size ?? 20) });
    if (options.status) params.set("status", options.status);
    if (options.symbol?.trim()) params.set("symbol", options.symbol.trim());
    if (options.tradingMode) params.set("tradingMode", options.tradingMode);
    if (options.from) params.set("from", options.from);
    if (options.to) params.set("to", options.to);
    return request<AdminOrderPage>(`/api/v1/admin/orders?${params.toString()}`, basic, signal);
  },
  adminMarketStatus: (basic: string, signal?: AbortSignal) =>
    request<AdminMarketStatus>("/api/v1/admin/market-status", basic, signal),
  adminAuditLogs: (basic: string, options: {
    page?: number; size?: number; action?: string; actor?: string; targetType?: string;
    outcome?: string; from?: string; to?: string;
  } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.size ?? 20) });
    if (options.action?.trim()) params.set("action", options.action.trim());
    if (options.actor?.trim()) params.set("actor", options.actor.trim());
    if (options.targetType?.trim()) params.set("targetType", options.targetType.trim());
    if (options.outcome) params.set("outcome", options.outcome);
    if (options.from) params.set("from", options.from);
    if (options.to) params.set("to", options.to);
    return request<AdminAuditLogPage>(`/api/v1/admin/audit-logs?${params.toString()}`, basic, signal);
  },
  adminRiskLimits: (basic: string, signal?: AbortSignal) =>
    request<AdminRiskLimit[]>("/api/v1/admin/risk-limits", basic, signal),
  createAdminRiskLimit: (basic: string, body: AdminRiskLimitRequest) =>
    write<AdminRiskLimit>("POST", "/api/v1/admin/risk-limits", basic, body),
  updateAdminRiskLimit: (basic: string, id: string, body: AdminRiskLimitRequest) =>
    write<AdminRiskLimit>("PUT", `/api/v1/admin/risk-limits/${encodeURIComponent(id)}`, basic, body),
  activateAdminRiskLimit: (basic: string, id: string) =>
    write<AdminRiskLimit>("POST", `/api/v1/admin/risk-limits/${encodeURIComponent(id)}/activate`, basic),
  deactivateAdminRiskLimit: (basic: string, id: string) =>
    write<AdminRiskLimit>("POST", `/api/v1/admin/risk-limits/${encodeURIComponent(id)}/deactivate`, basic),
  riskMe: (basic: string, signal?: AbortSignal) =>
    request<RiskLimit[]>("/api/v1/risk/me", basic, signal),
  performanceMe: (basic: string, signal?: AbortSignal) =>
    request<Performance>("/api/v1/performance/me", basic, signal),
};

export async function loadDashboard(basic: string, signal: AbortSignal): Promise<DashboardData> {
  const results = await Promise.allSettled([
    api.instruments(basic, signal),
    api.quotes(basic, ["TCS", "RELIANCE", "HDFCBANK"], signal),
    api.portfolio(basic, signal),
    api.orders(basic, { page: 0, size: 5 }, signal),
    api.watchlists(basic, signal),
  ]);
  if (signal.aborted) throw new DOMException("The request was aborted.", "AbortError");
  const valueOrNull = <T,>(index: number) => results[index].status === "fulfilled" ? results[index].value as T : null;
  const errorOrUndefined = (index: number) => results[index].status === "rejected"
    ? results[index].reason instanceof Error ? results[index].reason.message : "The request could not be completed."
    : undefined;
  return {
    instruments: valueOrNull<Instrument[]>(0),
    marketQuotes: valueOrNull<Quote[]>(1),
    portfolio: valueOrNull<Portfolio>(2),
    orders: valueOrNull<OrderPage>(3),
    watchlists: valueOrNull<Watchlist[]>(4),
    unreadNotifications: null,
    errors: {
      instruments: errorOrUndefined(0),
      marketQuotes: errorOrUndefined(1),
      portfolio: errorOrUndefined(2),
      orders: errorOrUndefined(3),
      watchlists: errorOrUndefined(4),
      unreadNotifications: undefined,
    },
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
