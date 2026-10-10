"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, type ApiError, type NotificationPage } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";

const PAGE_SIZE = 20;

export default function NotificationsPage() {
  const { session } = useAuth();
  const [page, setPage] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [data, setData] = useState<NotificationPage | null>(null);
  const [unreadCount, setUnreadCount] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [countError, setCountError] = useState<string | null>(null);
  const [pending, setPending] = useState<string | null>(null);
  const pendingRef = useRef(false);

  useEffect(() => {
    if (!session) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setCountError(null);
    setData(null);
    Promise.allSettled([
      api.notifications(session.basicCredential, { page, size: PAGE_SIZE, unreadOnly }, controller.signal),
      api.unreadNotificationCount(session.basicCredential, controller.signal),
    ]).then(([pageResult, countResult]) => {
      if (controller.signal.aborted) return;
      if (pageResult.status === "fulfilled") setData(pageResult.value);
      else setError(messageOf(pageResult.reason));
      if (countResult.status === "fulfilled") {
        setUnreadCount(countResult.value.unreadCount);
        publishUnreadCount(countResult.value.unreadCount);
      } else setCountError(messageOf(countResult.reason));
      setLoading(false);
    });
    return () => controller.abort();
  }, [session, page, unreadOnly, refreshKey]);

  const refresh = useCallback(() => setRefreshKey((value) => value + 1), []);

  async function markOneAsRead(id: string) {
    if (!session || pendingRef.current) return;
    pendingRef.current = true;
    setPending(id);
    setError(null);
    try {
      const updated = await api.markNotificationRead(session.basicCredential, id);
      setData((current) => current ? { ...current, items: current.items.map((item) => item.id === id ? updated : item) } : current);
      const nextCount = Math.max(0, (unreadCount ?? 0) - 1);
      setUnreadCount(nextCount);
      publishUnreadCount(nextCount);
      refresh();
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      pendingRef.current = false;
      setPending(null);
    }
  }

  async function markAllAsRead() {
    if (!session || pendingRef.current || unreadCount === 0) return;
    pendingRef.current = true;
    setPending("all");
    setError(null);
    try {
      const result = await api.markAllNotificationsRead(session.basicCredential);
      const nextCount = Math.max(0, (unreadCount ?? result.updatedCount) - result.updatedCount);
      setUnreadCount(nextCount);
      publishUnreadCount(nextCount);
      refresh();
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      pendingRef.current = false;
      setPending(null);
    }
  }

  if (!session) return <div className="content-wrap"><PageHeading eyebrow="Account updates" title="Notifications" /><LoginRequired /></div>;

  const totalPages = data?.totalPages ?? 0;
  const hasUnread = (unreadCount ?? 0) > 0;

  return <div className="content-wrap">
    <PageHeading eyebrow="Account updates" title="Notifications" description="Review price alerts and other updates from your virtual trading account." />

    {error && <div className="notification-error"><ErrorState message={error} /></div>}
    {countError && <p className="form-error" role="alert">Unread count is unavailable. {countError}</p>}

    <section className="panel notification-panel">
      <div className="notification-toolbar">
        <div><p className="eyebrow">Inbox</p><h2>{unreadCount == null ? "Notifications" : `${unreadCount} unread`}</h2></div>
        <button className="secondary-button" type="button" disabled={!hasUnread || Boolean(pending)} onClick={() => void markAllAsRead()}>{pending === "all" ? "Marking all read…" : "Mark all as read"}</button>
      </div>

      <div className="notification-controls" aria-label="Notification filters">
        <div className="filter-tabs">
          <button className={!unreadOnly ? "filter-tab active" : "filter-tab"} type="button" aria-pressed={!unreadOnly} onClick={() => { setPage(0); setUnreadOnly(false); }}>All</button>
          <button className={unreadOnly ? "filter-tab active" : "filter-tab"} type="button" aria-pressed={unreadOnly} onClick={() => { setPage(0); setUnreadOnly(true); }}>Unread</button>
        </div>
        <span className="muted">Showing up to {PAGE_SIZE} per page</span>
      </div>

      {loading && !data ? <LoadingState label="Loading notifications…" /> : error && !data ? null : !data?.items.length ? <EmptyState message={unreadOnly ? "You have no unread notifications." : "No notifications yet."} /> : <div className="notification-list">
        {data.items.map((item) => {
          const isUnread = item.readAt == null;
          return <article className={isUnread ? "notification-item unread" : "notification-item"} key={item.id}>
            <div className="notification-copy">
              <div className="notification-title-row"><h3>{item.title}</h3><StatusBadge status={isUnread ? "UNREAD" : "READ"} /></div>
              <p>{item.message}</p>
              <time dateTime={item.createdAt}>{formatDate(item.createdAt)}</time>
            </div>
            {isUnread && <button className="text-button notification-read-button" type="button" disabled={Boolean(pending)} onClick={() => void markOneAsRead(item.id)}>{pending === item.id ? "Saving…" : "Mark as read"}</button>}
          </article>;
        })}
      </div>}

      {data && data.items.length > 0 && <div className="pagination notification-pagination">
        <button className="secondary-button" type="button" disabled={loading || page <= 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
        <span>Page {page + 1} of {Math.max(1, totalPages)} · {data.totalElements} notifications</span>
        <button className="secondary-button" type="button" disabled={loading || page + 1 >= totalPages} onClick={() => setPage((value) => Math.min(Math.max(0, totalPages - 1), value + 1))}>Next</button>
      </div>}
      {unreadCount === 0 && <p className="panel-footnote notification-clear-note">You’re all caught up. There are no unread notifications.</p>}
    </section>
  </div>;
}

function publishUnreadCount(count: number) {
  window.dispatchEvent(new CustomEvent<number>("tradecore:unread-count-updated", { detail: count }));
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("en-IN", { timeZone: "Asia/Kolkata" });
}

function messageOf(error: unknown) {
  const apiError = error as ApiError | null;
  if (apiError?.status === 401) return "Your session has expired. Please sign in again.";
  if (error instanceof Error) return error.message;
  return "The request could not be completed.";
}
