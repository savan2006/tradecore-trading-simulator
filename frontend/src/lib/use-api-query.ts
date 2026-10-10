"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";

export type QueryState<T> = { data: T | null; error: Error | null; loading: boolean };

export function useApiQuery<T>(load: (credential: string, signal: AbortSignal) => Promise<T>, key = "") {
  const { session } = useAuth();
  const [state, setState] = useState<QueryState<T>>({ data: null, error: null, loading: true });

  useEffect(() => {
    if (!session) {
      setState({ data: null, error: null, loading: false });
      return;
    }
    const controller = new AbortController();
    setState((previous) => ({ ...previous, loading: true, error: null }));
    load(session.basicCredential, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setState({ data, error: null, loading: false });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted && !(error instanceof Error && error.name === "AbortError")) {
          setState({ data: null, error: error instanceof Error ? error : new Error("Unable to load data."), loading: false });
        }
      });
    return () => controller.abort();
  }, [session?.email, load, key]);

  return state;
}
