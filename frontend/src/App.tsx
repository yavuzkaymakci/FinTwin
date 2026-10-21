import { BackendHealthCard } from './components/BackendHealthCard';

export default function App() {
  return (
    <main className="container">
      <header className="page-header">
        <h1>FinTwin</h1>
        <p>Digital twin of a retail banking portfolio — system status</p>
      </header>
      <BackendHealthCard />
    </main>
  );
}
