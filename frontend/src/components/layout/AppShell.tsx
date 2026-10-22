import * as React from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Activity, Bot, Briefcase, Cpu, LayoutDashboard, Menu, Moon, PiggyBank, Radio, Sparkles, Sun, Users, X,
} from 'lucide-react';
import { asAnalyst, asCustomer, DEFAULT_CUSTOMER_ID, setIdentity, useIdentity } from '@/auth/identity';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/primitives';
import { useTheme } from '@/lib/theme';
import { cn } from '@/lib/utils';
import { SystemIndicator } from './SystemIndicator';
import { StatusBanner } from './StatusBanner';

interface NavItem {
  to: string;
  label: string;
  icon: React.ReactNode;
  end?: boolean;
}

const ANALYST_NAV: NavItem[] = [
  { to: '/', label: 'Portföy', icon: <LayoutDashboard />, end: true },
  { to: '/customers', label: 'Müşteriler', icon: <Users /> },
  { to: '/scenarios', label: 'Senaryolar', icon: <Briefcase /> },
  { to: '/assistant', label: 'Senaryo asistanı', icon: <Sparkles /> },
  { to: '/live', label: 'Canlı akış', icon: <Radio /> },
  { to: '/system', label: 'Sistem ve modeller', icon: <Cpu /> },
];

const CUSTOMER_NAV: NavItem[] = [
  { to: '/me', label: 'Özetim', icon: <PiggyBank />, end: true },
  { to: '/me/coach', label: 'Finans koçu', icon: <Bot /> },
];

function Logo() {
  return (
    <div className="flex items-center gap-2.5 px-2">
      <svg viewBox="0 0 32 32" className="size-8" aria-hidden="true">
        <rect width="32" height="32" rx="8" className="fill-sidebar-accent" />
        <circle cx="12.5" cy="16" r="6.5" fill="none" stroke="hsl(var(--primary))" strokeWidth="2.5" />
        <circle cx="19.5" cy="16" r="6.5" fill="none" stroke="currentColor" strokeWidth="2.5" opacity="0.85" />
      </svg>
      <div className="leading-tight">
        <div className="text-[15px] font-semibold tracking-tight">FinTwin</div>
        <div className="text-[11px] text-sidebar-muted">Portföy dijital ikizi</div>
      </div>
    </div>
  );
}

function Sidebar({ items, onNavigate }: { items: NavItem[]; onNavigate?: () => void }) {
  return (
    <nav className="flex h-full flex-col gap-6 bg-sidebar px-3 py-5 text-sidebar-foreground">
      <Logo />
      <ul className="space-y-0.5">
        {items.map((item) => (
          <li key={item.to}>
            <NavLink to={item.to} end={item.end} onClick={onNavigate}
              className={({ isActive }) => cn('flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium '
                + 'transition-colors [&_svg]:size-4', isActive
                ? 'bg-sidebar-accent text-sidebar-foreground shadow-[inset_2px_0_0_hsl(var(--primary))]'
                : 'text-sidebar-muted hover:bg-sidebar-accent/60 hover:text-sidebar-foreground')}>
              {item.icon}
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
      <div className="mt-auto rounded-lg bg-sidebar-accent/70 p-3 text-[11px] leading-relaxed text-sidebar-muted">
        <div className="mb-1 flex items-center gap-1.5 font-medium text-sidebar-foreground">
          <Activity className="size-3.5 text-primary" /> Sentetik veri
        </div>
        5.000 müşterilik sentetik portföy, Kafka ile beslenen olay tabanlı ikizler.
      </div>
    </nav>
  );
}

function RoleSwitcher() {
  const identity = useIdentity();
  const navigate = useNavigate();
  const [customer, setCustomer] = React.useState(String(identity.customerId ?? DEFAULT_CUSTOMER_ID));

  React.useEffect(() => {
    if (identity.customerId !== null) setCustomer(String(identity.customerId));
  }, [identity.customerId]);

  const choose = (role: 'RISK_ANALYST' | 'CUSTOMER') => {
    if (role === identity.role) return;
    if (role === 'RISK_ANALYST') {
      setIdentity(asAnalyst());
      navigate('/');
    } else {
      setIdentity(asCustomer(Number(customer) || DEFAULT_CUSTOMER_ID));
      navigate('/me');
    }
  };

  const applyCustomer = (e: React.FormEvent) => {
    e.preventDefault();
    const id = Number(customer);
    if (Number.isInteger(id) && id > 0) setIdentity(asCustomer(id));
  };

  return (
    <div className="flex items-center gap-2">
      <div role="radiogroup" aria-label="Rol" className="inline-flex rounded-lg bg-muted p-0.5 text-sm">
        {([['RISK_ANALYST', 'Risk analisti'], ['CUSTOMER', 'Müşteri']] as const).map(([role, label]) => (
          <button key={role} role="radio" aria-checked={identity.role === role} onClick={() => choose(role)}
            className={cn('rounded-md px-3 py-1 font-medium transition-all', identity.role === role
              ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}>
            {label}
          </button>
        ))}
      </div>
      {identity.role === 'CUSTOMER' && (
        <form onSubmit={applyCustomer} className="flex items-center gap-1" aria-label="Görüntülenen müşteri">
          <span className="hidden text-xs text-muted-foreground lg:inline">Müşteri no</span>
          <Input value={customer} onChange={(e) => setCustomer(e.target.value.replace(/\D/g, ''))}
                 className="h-8 w-20" inputMode="numeric" aria-label="Müşteri numarası" />
          <Button size="sm" variant="outline" type="submit">Göster</Button>
        </form>
      )}
    </div>
  );
}

export function AppShell() {
  const identity = useIdentity();
  const [theme, toggleTheme] = useTheme();
  const [mobileOpen, setMobileOpen] = React.useState(false);
  const location = useLocation();
  const items = identity.role === 'CUSTOMER' ? CUSTOMER_NAV : ANALYST_NAV;

  React.useEffect(() => setMobileOpen(false), [location.pathname]);

  return (
    <div className="flex min-h-screen bg-background">
      <aside className="sticky top-0 hidden h-screen w-60 shrink-0 lg:block"><Sidebar items={items} /></aside>
      {mobileOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-black/50" onClick={() => setMobileOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-64 animate-in slide-in-from-left">
            <Sidebar items={items} onNavigate={() => setMobileOpen(false)} />
          </aside>
        </div>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b bg-background/85 px-4
          backdrop-blur supports-[backdrop-filter]:bg-background/70 sm:px-6">
          <Button variant="ghost" size="icon" className="lg:hidden" aria-label="Menü"
                  onClick={() => setMobileOpen((o) => !o)}>
            {mobileOpen ? <X /> : <Menu />}
          </Button>
          <RoleSwitcher />
          <div className="ml-auto flex items-center gap-1.5">
            <SystemIndicator />
            <Button variant="ghost" size="icon" onClick={toggleTheme}
                    aria-label={theme === 'dark' ? 'Açık temaya geç' : 'Koyu temaya geç'}>
              {theme === 'dark' ? <Sun /> : <Moon />}
            </Button>
          </div>
        </header>
        <StatusBanner />
        <main className="mx-auto w-full max-w-[1400px] flex-1 px-4 py-6 sm:px-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
