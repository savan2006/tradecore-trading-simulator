"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { api, encodeBasicCredential } from "@/lib/api";

type AuthState = { email: string; basicCredential: string };
type AuthContextValue = {
  session: AuthState | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [session, setSession] = useState<AuthState | null>(null);
  const signIn = useCallback(async (email: string, password: string) => {
    const basicCredential = encodeBasicCredential(email.trim(), password);
    await api.verifyLogin(basicCredential);
    setSession({ email: email.trim(), basicCredential });
  }, []);
  const signOut = useCallback(() => setSession(null), []);
  const value = useMemo(() => ({ session, signIn, signOut }), [session, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
