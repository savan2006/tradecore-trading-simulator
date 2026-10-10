"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState, type FormEvent } from "react";
import { api, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

export default function RegisterPage() {
  const router = useRouter();
  const { session } = useAuth();
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const submissionLock = useRef(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submissionLock.current) return;
    setError(null);
    const name = displayName.trim();
    const normalizedEmail = email.trim();
    if (!name || !normalizedEmail || !password || !confirmPassword) {
      setError("Complete all fields before creating your account.");
      return;
    }
    if (name.length > 120) {
      setError("Display name must be 120 characters or fewer.");
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail) || normalizedEmail.length > 320) {
      setError("Enter a valid email address (up to 320 characters).");
      return;
    }
    if (password.length < 8 || password.length > 72) {
      setError("Password must contain between 8 and 72 characters.");
      return;
    }
    if (password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    submissionLock.current = true;
    setSubmitting(true);
    try {
      await api.register({ displayName: name, email: normalizedEmail, password });
      setPassword("");
      setConfirmPassword("");
      router.replace("/login?registered=1");
    } catch (cause) {
      const status = (cause as ApiError)?.status;
      setError(status === 409
        ? "An account with this email is already registered. Try signing in instead."
        : status === 401
          ? "The registration request was not authorized. Please try again."
          : status === 400
            ? `${cause instanceof Error ? cause.message : "Registration details were rejected."} Check the display name, email, and password requirements.`
          : cause instanceof Error ? cause.message : "Unable to create your account. Check your connection and try again.");
    } finally {
      submissionLock.current = false;
      setSubmitting(false);
    }
  }

  if (session) {
    return <div className="login-page"><section className="login-card"><p className="eyebrow">TradeCore account</p><h1>Already signed in</h1><p className="muted">Sign out before creating another account.</p><Link className="text-link" href="/">Return to dashboard</Link></section></div>;
  }

  return <div className="login-page">
    <section className="login-card" aria-labelledby="register-heading">
      <p className="eyebrow">TradeCore account</p>
      <h1 id="register-heading">Create your account</h1>
      <p className="muted">Create a virtual trading account for company learning and paper trading.</p>
      <form className="form-stack" onSubmit={submit} noValidate>
        <label>Display name<input autoComplete="name" maxLength={120} required value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label>
        <label>Email<input type="email" autoComplete="email" maxLength={320} required value={email} onChange={(event) => setEmail(event.target.value)} /></label>
        <label>Password<input type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        <label>Confirm password<input type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} /></label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button className="button-primary" type="submit" disabled={submitting}>{submitting ? "Creating account…" : "Create account"}</button>
      </form>
      <p className="fine-print">Your password is sent only to the registration endpoint and is not saved in browser storage.</p>
      <p className="auth-switch">Already registered? <Link href="/login">Sign in</Link></p>
    </section>
  </div>;
}
