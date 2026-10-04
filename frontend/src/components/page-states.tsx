import Link from "next/link";

export function PageHeading({ eyebrow, title, description }: { eyebrow: string; title: string; description?: string }) {
  return <div className="page-heading"><p className="eyebrow">{eyebrow}</p><h1>{title}</h1>{description && <p className="muted heading-copy">{description}</p>}</div>;
}

export function LoadingState({ label = "Loading data…" }: { label?: string }) {
  return <div className="state-card" role="status"><span className="spinner" />{label}</div>;
}

export function ErrorState({ message }: { message: string }) {
  return <div className="state-card error-state" role="alert"><strong>Could not load this information</strong><span>{message}</span><span>Check the backend connection or sign in again.</span></div>;
}

export function EmptyState({ message }: { message: string }) {
  return <div className="empty-state">{message}</div>;
}

export function LoginRequired() {
  return <div className="state-card"><span>Your session is not active.</span><Link className="text-link" href="/login">Sign in to continue</Link></div>;
}

export function StatusBadge({ status }: { status: string }) {
  const normalized = status.toLowerCase();
  return <span className={`status-badge status-${normalized}`}>{status}</span>;
}
