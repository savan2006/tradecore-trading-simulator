export default function Home() {
  return (
    <main className="page">
      <header className="topbar">
        <a className="wordmark" href="/" aria-label="TradeCore home">TradeCore</a>
        <span className="environment">Foundation</span>
      </header>
      <section className="intro" aria-labelledby="page-title">
        <p className="eyebrow">Virtual trading platform</p>
        <h1 id="page-title">A clear view of the markets starts here.</h1>
        <p className="description">
          TradeCore is being prepared. Market and account features will appear as they are built.
        </p>
      </section>
      <footer className="footer">TradeCore · Learning with virtual capital</footer>
    </main>
  );
}
