"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";

export function LoginForm() {
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
      setError(cause instanceof Error ? cause.message : "Unable to sign in. Check your connection and try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="login-card" aria-labelledby="login-heading">
      <p className="eyebrow">TradeCore account</p>
      <h1 id="login-heading">Sign in to continue</h1>
      <p className="muted">Use your existing TradeCore email and password. Authentication uses HTTP Basic.</p>
      <form className="form-stack" onSubmit={submit}>
        <label>Email<input type="email" autoComplete="username" required value={email} onChange={(event) => setEmail(event.target.value)} /></label>
        <label>Password<input type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button className="button-primary" type="submit" disabled={submitting}>{submitting ? "Checking…" : "Sign in"}</button>
      </form>
      <p className="fine-print">Your password is used to verify this sign-in and is not saved in browser storage.</p>
    </section>
  );
}
