"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { api, type ApiError, type TradeJournalEntry, type TradeJournalPage } from "@/lib/api";
import { EmptyState, ErrorState, LoadingState, LoginRequired, PageHeading, StatusBadge } from "@/components/page-states";

const PAGE_SIZE = 20;
type JournalForm = { orderId: string; thesis: string; strategyTag: string; wentWell: string; wentWrong: string; lessonLearned: string; rating: string };
const EMPTY_FORM: JournalForm = { orderId: "", thesis: "", strategyTag: "", wentWell: "", wentWrong: "", lessonLearned: "", rating: "" };

export default function JournalPage() {
  const { session } = useAuth();
  const [entries, setEntries] = useState<TradeJournalPage | null>(null);
  const [page, setPage] = useState(0);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [form, setForm] = useState<JournalForm>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionMessage, setActionMessage] = useState<string | null>(null);

  useEffect(() => {
    const orderId = new URLSearchParams(window.location.search).get("orderId");
    if (orderId) setForm((current) => ({ ...current, orderId }));
  }, []);

  useEffect(() => {
    if (!session) return;
    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);
    api.journal(session.basicCredential, page, PAGE_SIZE, controller.signal)
      .then(setEntries)
      .catch((error: unknown) => { if (!controller.signal.aborted) setLoadError(messageOf(error)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [session, page, refreshVersion]);

  if (!session) return <div className="content-wrap"><PageHeading eyebrow="Learning" title="Trade journal" description="Capture your reasoning and lessons after a completed virtual trade." /><LoginRequired /></div>;

  function resetForm() {
    const orderId = new URLSearchParams(window.location.search).get("orderId") ?? "";
    setEditingId(null);
    setForm({ ...EMPTY_FORM, orderId });
    setActionError(null);
    setActionMessage(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session || saving) return;
    setSaving(true);
    setActionError(null);
    setActionMessage(null);
    const input = {
      thesis: form.thesis.trim(), strategyTag: form.strategyTag.trim() || undefined,
      wentWell: form.wentWell.trim() || undefined, wentWrong: form.wentWrong.trim() || undefined,
      lessonLearned: form.lessonLearned.trim() || undefined, rating: form.rating ? Number(form.rating) : null,
    };
    try {
      if (editingId) await api.updateJournalEntry(session.basicCredential, editingId, input);
      else await api.createJournalEntry(session.basicCredential, { ...input, orderId: form.orderId.trim() });
      const successMessage = editingId ? "Journal entry updated." : "Trade review saved.";
      resetForm();
      setActionMessage(successMessage);
      setRefreshVersion((value) => value + 1);
    } catch (error) {
      setActionError(messageOf(error));
    } finally {
      setSaving(false);
    }
  }

  function editEntry(entry: TradeJournalEntry) {
    setEditingId(entry.id);
    setForm({ orderId: entry.orderId, thesis: entry.thesis, strategyTag: entry.strategyTag ?? "",
      wentWell: entry.wentWell ?? "", wentWrong: entry.wentWrong ?? "",
      lessonLearned: entry.lessonLearned ?? "", rating: entry.rating == null ? "" : String(entry.rating) });
    setActionError(null);
    setActionMessage(null);
  }

  async function deleteEntry(id: string) {
    if (!session || deletingId) return;
    setDeletingId(id);
    setActionError(null);
    setActionMessage(null);
    try {
      await api.deleteJournalEntry(session.basicCredential, id);
      setActionMessage("Journal entry deleted.");
      if (editingId === id) resetForm();
      setRefreshVersion((value) => value + 1);
    } catch (error) {
      setActionError(messageOf(error));
    } finally {
      setDeletingId(null);
    }
  }

  return <div className="content-wrap journal-page">
    <PageHeading eyebrow="Learning" title="Trade journal" description="Review completed virtual trades and keep a record of your decisions." />
    {(actionError || actionMessage) && <div className={`notice ${actionError ? "notice-error" : "notice-success"}`} role={actionError ? "alert" : "status"}>{actionError ?? actionMessage}</div>}
    <section className="panel journal-editor">
      <div className="panel-heading"><div><p className="eyebrow">Post-trade review</p><h2>{editingId ? "Edit journal entry" : "New journal entry"}</h2></div>{editingId && <button type="button" className="text-button" onClick={resetForm}>Cancel edit</button>}</div>
      <p className="panel-footnote">Entries can only be saved for your own filled trades. Use the review link in Orders &amp; trades to start with a trade reference.</p>
      <form className="journal-form" onSubmit={submit}>
        <label>Order reference<input value={form.orderId} disabled={Boolean(editingId)} required maxLength={36} onChange={(event) => setForm({ ...form, orderId: event.target.value })} placeholder="Order ID from a completed trade" /></label>
        <label>Reason / thesis<textarea value={form.thesis} required maxLength={2000} rows={3} onChange={(event) => setForm({ ...form, thesis: event.target.value })} placeholder="Why did you take this trade?" /></label>
        <label>Strategy / tag<input value={form.strategyTag} maxLength={80} onChange={(event) => setForm({ ...form, strategyTag: event.target.value })} placeholder="Optional tag" /></label>
        <label>What went well<textarea value={form.wentWell} maxLength={2000} rows={2} onChange={(event) => setForm({ ...form, wentWell: event.target.value })} /></label>
        <label>What went wrong<textarea value={form.wentWrong} maxLength={2000} rows={2} onChange={(event) => setForm({ ...form, wentWrong: event.target.value })} /></label>
        <label>Lesson learned<textarea value={form.lessonLearned} maxLength={2000} rows={2} onChange={(event) => setForm({ ...form, lessonLearned: event.target.value })} /></label>
        <label>Rating<select value={form.rating} onChange={(event) => setForm({ ...form, rating: event.target.value })}><option value="">No rating</option><option value="1">1 / 5</option><option value="2">2 / 5</option><option value="3">3 / 5</option><option value="4">4 / 5</option><option value="5">5 / 5</option></select></label>
        <div className="journal-form-actions"><button className="button-primary" type="submit" disabled={saving}>{saving ? "Saving…" : editingId ? "Update entry" : "Save review"}</button>{!editingId && <Link className="text-link" href="/orders">View completed trades</Link>}</div>
      </form>
    </section>
    <section className="panel journal-history">
      <div className="panel-heading"><div><p className="eyebrow">Newest first</p><h2>Your reviews</h2></div>{entries && <span className="muted">{entries.totalElements.toLocaleString("en-IN")} total</span>}</div>
      {loading ? <LoadingState label="Loading journal entries…" /> : loadError ? <ErrorState message={loadError} /> : !entries || entries.content.length === 0 ? <EmptyState message="No trade reviews yet. Start from a filled order or completed trade." /> : <>
        <div className="journal-entry-list">{entries.content.map((entry) => <article className="journal-entry" key={entry.id}>
          <div className="journal-entry-heading"><div><p className="eyebrow">{entry.exchange} · {entry.symbol} · {entry.side} · {entry.tradingMode}</p><h3>{entry.thesis}</h3><p className="muted">Order {entry.orderId} · Quantity {entry.quantity}</p></div><div className="journal-entry-rating">{entry.rating == null ? <span className="muted">No rating</span> : <StatusBadge status={`${entry.rating} / 5`} />}</div></div>
          {entry.strategyTag && <p className="journal-tag">{entry.strategyTag}</p>}
          <div className="journal-notes">{entry.wentWell && <Note title="Went well" text={entry.wentWell} />}{entry.wentWrong && <Note title="Went wrong" text={entry.wentWrong} />}{entry.lessonLearned && <Note title="Lesson learned" text={entry.lessonLearned} />}</div>
          <div className="journal-entry-footer"><span className="muted">Created {formatDate(entry.createdAt)} · Updated {formatDate(entry.updatedAt)}</span><div><button type="button" className="text-button" onClick={() => editEntry(entry)}>Edit</button><button type="button" className="text-button journal-delete" disabled={deletingId === entry.id} onClick={() => deleteEntry(entry.id)}>{deletingId === entry.id ? "Deleting…" : "Delete"}</button></div></div>
        </article>)}</div>
        <div className="admin-pagination"><span>Page {page + 1}{entries.totalPages > 0 ? ` of ${entries.totalPages}` : ""}</span><div><button type="button" className="button-quiet" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button><button type="button" className="button-quiet" disabled={!entries.hasNext} onClick={() => setPage(page + 1)}>Next</button></div></div>
      </>}
    </section>
  </div>;
}

function Note({ title, text }: { title: string; text: string }) {
  return <div><strong>{title}</strong><p>{text}</p></div>;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Not available" : date.toLocaleString();
}

function messageOf(error: unknown) {
  if (error instanceof Error) {
    const status = (error as ApiError).status;
    if (status === 401) return "Your session is no longer authorized. Sign in again.";
    if (status === 403) return "You can only manage your own trade journal.";
    if (status === 404) return "The completed trade or journal entry could not be found for your account.";
    return error.message;
  }
  return "The request could not be completed.";
}
