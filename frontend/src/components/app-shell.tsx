"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { api, type MarketSession } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { LoginForm } from "@/components/login-form";

const links = [
  ["Dashboard", "/"],
  ["Markets", "/markets"],
  ["Strategy Lab", "/strategy-lab"],
  ["Compare", "/compare"],
  ["Portfolio", "/portfolio"],
  ["Performance", "/performance"],
  ["Risk", "/risk"],
  ["Orders", "/orders"],
  ["Journal", "/journal"],
  ["Watchlists", "/watchlists"],
  ["Notifications", "/notifications"],
] as const;

export function AppShell({ children }: Readonly<{ children: React.ReactNode }>) {
  const { session, adminAccess, signOut } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const isLogin = pathname === "/login";
  const isPublicAuthPage = isLogin || pathname === "/register";
  const [unreadCount, setUnreadCount] = useState<number | null>(null);
  const [marketSession, setMarketSession] = useState<MarketSession | null>(null);

  useEffect(() => {
    const handleExpiredSession = () => {
      void signOut();
      router.replace("/login");
    };
    window.addEventListener("tradecore:session-expired", handleExpiredSession);
    return () => window.removeEventListener("tradecore:session-expired", handleExpiredSession);
  }, [router, signOut]);

  useEffect(() => {
    if (!session) {
      setUnreadCount(null);
      return;
    }
    const controller = new AbortController();
    api.unreadNotificationCount(session.basicCredential, controller.signal)
      .then((result) => setUnreadCount(result.unreadCount))
      .catch(() => { if (!controller.signal.aborted) setUnreadCount(null); });
    const updateCount = (event: Event) => {
      const count = (event as CustomEvent<number>).detail;
      if (Number.isFinite(count) && count >= 0) setUnreadCount(count);
    };
    window.addEventListener("tradecore:unread-count-updated", updateCount);
    return () => {
      controller.abort();
      window.removeEventListener("tradecore:unread-count-updated", updateCount);
    };
  }, [session]);

  useEffect(() => {
    if (!session) {
      setMarketSession(null);
      return;
    }
    const controller = new AbortController();
    const refresh = () => api.marketSession(session.basicCredential, controller.signal)
      .then(setMarketSession)
      .catch(() => { if (!controller.signal.aborted) setMarketSession(null); });
    void refresh();
    const interval = window.setInterval(() => void refresh(), 60_000);
    return () => { controller.abort(); window.clearInterval(interval); };
  }, [session]);

  return (
    <div className="app-frame">
      <header className="topbar">
        <Link className="wordmark" href="/" aria-label="TradeCore dashboard">TradeCore<span className="brand-dot">.</span></Link>
        <nav className="top-nav" aria-label="Main navigation">
          {links.map(([label, href]) => (
            <Link key={href} href={href} aria-current={pathname === href || href === "/markets" && pathname.startsWith("/companies/") ? "page" : undefined} className={pathname === href || href === "/markets" && pathname.startsWith("/companies/") ? "nav-link active" : "nav-link"}>
              {label}{href === "/notifications" && unreadCount != null && unreadCount > 0 && <span className="nav-unread-count" aria-label={`${unreadCount} unread notifications`}>{unreadCount > 99 ? "99+" : unreadCount}</span>}
            </Link>
          ))}
          {session && adminAccess === "allowed" && <Link href="/admin" aria-current={pathname === "/admin" ? "page" : undefined} className={pathname === "/admin" ? "nav-link active" : "nav-link"}>Admin</Link>}
          {session && adminAccess === "allowed" && <Link href="/admin/risk-limits" aria-current={pathname === "/admin/risk-limits" ? "page" : undefined} className={pathname === "/admin/risk-limits" ? "nav-link active" : "nav-link"}>Risk limits</Link>}
          {session && adminAccess === "allowed" && <Link href="/admin/audit-logs" aria-current={pathname === "/admin/audit-logs" ? "page" : undefined} className={pathname === "/admin/audit-logs" ? "nav-link active" : "nav-link"}>Audit logs</Link>}
        </nav>
        <div className="session-tools">
          {session && marketSession && <span className={`market-session-badge ${marketSession.status === "OPEN" ? "is-open" : "is-closed"}`} title={marketSession.reason ?? undefined}>
            Market {marketSession.status.toLowerCase()}{marketSession.status === "CLOSED" && ` · next open ${marketSession.nextOpenAt ? formatMarketOpen(marketSession.nextOpenAt) : "unavailable"}`}
          </span>}
          {session ? <><span className="session-email">{session.email}</span><button className="button-quiet" onClick={() => { signOut(); router.replace("/login"); }}>Sign out</button></> : <Link className="button-quiet" href="/login">Sign in</Link>}
        </div>
      </header>
      <main className="main-content">
        {session || isPublicAuthPage ? children : <div className="gate"><LoginForm /></div>}
      </main>
      <footer className="footer"><span>TradeCore</span><span>Learning with virtual capital</span></footer>
    </div>
  );
}

function formatMarketOpen(value: string) {
  return new Date(value).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", weekday: "short", hour: "2-digit", minute: "2-digit" });
}
