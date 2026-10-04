"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { LoginForm } from "@/components/login-form";

const links = [
  ["Dashboard", "/"],
  ["Markets", "/markets"],
  ["Portfolio", "/portfolio"],
  ["Orders", "/orders"],
  ["Watchlists", "/watchlists"],
] as const;

export function AppShell({ children }: Readonly<{ children: React.ReactNode }>) {
  const { session, signOut } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const isLogin = pathname === "/login";

  return (
    <div className="app-frame">
      <header className="topbar">
        <Link className="wordmark" href="/" aria-label="TradeCore dashboard">TradeCore<span className="brand-dot">.</span></Link>
        <nav className="top-nav" aria-label="Main navigation">
          {links.map(([label, href]) => (
            <Link key={href} href={href} className={pathname === href ? "nav-link active" : "nav-link"}>{label}</Link>
          ))}
        </nav>
        <div className="session-tools">
          {session ? <><span className="session-email">{session.email}</span><button className="button-quiet" onClick={() => { signOut(); router.replace("/login"); }}>Sign out</button></> : <Link className="button-quiet" href="/login">Sign in</Link>}
        </div>
      </header>
      <main className="main-content">
        {session || isLogin ? children : <div className="gate"><LoginForm /></div>}
      </main>
      <footer className="footer"><span>TradeCore</span><span>Learning with virtual capital</span></footer>
    </div>
  );
}
