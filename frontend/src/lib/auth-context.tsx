"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ApiError } from "@/lib/api";

type AuthState = { email: string; basicCredential: string };
const COOKIE_SESSION_MARKER = "cookie-session";
type AuthContextValue = {
  session: AuthState | null;
  unreadCount: number | null;
  setUnreadCount: (count: number | null) => void;
  adminAccess: "signed-out" | "checking" | "allowed" | "forbidden" | "unauthorized" | "unavailable";
  refreshAdminAccess: () => void;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [session, setSession] = useState<AuthState | null>(null);
  const [unreadCount, setUnreadCount] = useState<number | null>(null);
  const [adminAccess, setAdminAccess] = useState<AuthContextValue["adminAccess"]>("signed-out");
  const signIn = useCallback(async (email: string, password: string) => {
    const response = await fetch("/api/session/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: email.trim(), password }), cache: "no-store" });
    if (!response.ok) {
      const error = new Error((await response.json().catch(() => null))?.message ?? "Unable to sign in.") as ApiError;
      error.status = response.status;
      throw error;
    }
    const user = await response.json() as { email: string; admin: boolean };
    setSession((current) => current?.email === user.email ? current : { email: user.email, basicCredential: COOKIE_SESSION_MARKER });
    setAdminAccess(user.admin ? "allowed" : "forbidden");
  }, []);
  const signOut = useCallback(async () => {
    try {
      await fetch("/api/session/logout", { method: "POST", cache: "no-store" });
    } finally {
      setSession(null);
      setAdminAccess("signed-out");
    }
  }, []);
  const refreshAdminAccess = useCallback(() => {
    setAdminAccess("checking");
    fetch("/api/session/me", { cache: "no-store" }).then(async (response) => {
      if (!response.ok) throw new Error("Session could not be verified.");
      const user = await response.json() as { email: string; admin: boolean };
      setSession((current) => current?.email === user.email ? current : { email: user.email, basicCredential: COOKIE_SESSION_MARKER });
      setAdminAccess(user.admin ? "allowed" : "forbidden");
    }).catch(() => setAdminAccess("unavailable"));
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/session/me", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) return;
        const user = await response.json() as { email?: string; admin?: boolean };
        if (user.email) {
          setSession((current) => current?.email === user.email ? current : { email: user.email!, basicCredential: COOKIE_SESSION_MARKER });
          setAdminAccess(user.admin ? "allowed" : "forbidden");
        }
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!session) setAdminAccess("signed-out");
  }, [session?.email]);

  const value = useMemo(() => ({ session, unreadCount, setUnreadCount, adminAccess, refreshAdminAccess, signIn, signOut }),
    [session, unreadCount, adminAccess, refreshAdminAccess, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
