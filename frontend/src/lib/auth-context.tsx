"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, type AdminOverview, type ApiError } from "@/lib/api";

type AuthState = { email: string; basicCredential: string };
const COOKIE_SESSION_MARKER = "cookie-session";
type AuthContextValue = {
  session: AuthState | null;
  adminAccess: "signed-out" | "checking" | "allowed" | "forbidden" | "unauthorized" | "unavailable";
  adminOverview: AdminOverview | null;
  refreshAdminAccess: () => void;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [session, setSession] = useState<AuthState | null>(null);
  const [adminAccess, setAdminAccess] = useState<AuthContextValue["adminAccess"]>("signed-out");
  const [adminOverview, setAdminOverview] = useState<AdminOverview | null>(null);
  const [accessRefresh, setAccessRefresh] = useState(0);
  const signIn = useCallback(async (email: string, password: string) => {
    const response = await fetch("/api/session/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: email.trim(), password }), cache: "no-store" });
    if (!response.ok) {
      const error = new Error((await response.json().catch(() => null))?.message ?? "Unable to sign in.") as ApiError;
      error.status = response.status;
      throw error;
    }
    const user = await response.json() as { email: string };
    setSession({ email: user.email, basicCredential: COOKIE_SESSION_MARKER });
  }, []);
  const signOut = useCallback(async () => {
    try {
      await fetch("/api/session/logout", { method: "POST", cache: "no-store" });
    } finally {
      setSession(null);
      setAdminAccess("signed-out");
      setAdminOverview(null);
    }
  }, []);
  const refreshAdminAccess = useCallback(() => setAccessRefresh((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/session/me", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) return;
        const user = await response.json() as { email?: string };
        if (user.email) setSession({ email: user.email, basicCredential: COOKIE_SESSION_MARKER });
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!session) {
      setAdminAccess("signed-out");
      setAdminOverview(null);
      return;
    }
    const controller = new AbortController();
    setAdminAccess("checking");
    setAdminOverview(null);
    api.adminOverview("", controller.signal)
      .then((overview) => {
        if (!controller.signal.aborted) {
          setAdminOverview(overview);
          setAdminAccess("allowed");
        }
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        const status = (error as ApiError)?.status;
        setAdminOverview(null);
        setAdminAccess(status === 403 ? "forbidden" : status === 401 ? "unauthorized" : "unavailable");
      });
    return () => controller.abort();
  }, [session, accessRefresh]);

  const value = useMemo(() => ({ session, adminAccess, adminOverview, refreshAdminAccess, signIn, signOut }),
    [session, adminAccess, adminOverview, refreshAdminAccess, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
