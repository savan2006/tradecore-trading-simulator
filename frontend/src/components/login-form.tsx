"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import type { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

export function LoginForm({ registered = false }: { registered?: boolean }) {
  const router = useRouter();
  const { signIn } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await signIn(email, password);
      setPassword("");
      router.replace("/");
    } catch (cause) {
      const status = (cause as ApiError)?.status;
      setError(status === 401
        ? "Email or password is incorrect."
        : status === 400
          ? "Please check your email and password, then try again."
          : status === 502 || status === 503 || status === 504
            ? cause instanceof Error ? cause.message : "The login server is unreachable. Please try again."
            : "Unable to sign in. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="login-card" aria-labelledby="login-heading">
      <p className="eyebrow">TradeCore account</p>
      <h1 id="login-heading">Sign in to continue</h1>
      <p className="muted">Use your existing TradeCore email and password. Authentication uses HTTP Basic.</p>
      {registered && <p className="form-success" role="status">Account created. Sign in to continue.</p>}
      <form className="form-stack" onSubmit={submit}>
        <label>Email<input type="email" autoComplete="username" required value={email} onChange={(event) => setEmail(event.target.value)} /></label>
        <label>Password<input type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button className="button-primary" type="submit" disabled={submitting}>{submitting ? "Checking…" : "Sign in"}</button>
      </form>
      <p className="fine-print">Your password is used to verify this sign-in and is not saved in browser storage.</p>
      <p className="auth-switch">New to TradeCore? <Link href="/register">Create an account</Link></p>
    </section>
  );
}
