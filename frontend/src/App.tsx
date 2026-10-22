import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import type { Role } from '@/auth/identity';
import { useIdentity } from '@/auth/identity';
import { EmptyState } from '@/components/common';
import { AppShell } from '@/components/layout/AppShell';
import { AssistantPage } from '@/pages/analyst/AssistantPage';
import { CustomerDetailPage } from '@/pages/analyst/CustomerDetailPage';
import { CustomersPage } from '@/pages/analyst/CustomersPage';
import { DashboardPage } from '@/pages/analyst/DashboardPage';
import { LivePage } from '@/pages/analyst/LivePage';
import { ComparePage, RunPage } from '@/pages/analyst/RunPage';
import { ScenariosPage } from '@/pages/analyst/ScenariosPage';
import { SystemPage } from '@/pages/analyst/SystemPage';
import { CoachPage } from '@/pages/customer/CoachPage';
import { MyOverviewPage } from '@/pages/customer/MyOverviewPage';

/** Sends a user to their own home when a page belongs to the other role. */
function RequireRole({ role }: { role: Role }) {
  const identity = useIdentity();
  if (identity.role !== role) return <Navigate to={identity.role === 'CUSTOMER' ? '/me' : '/'} replace />;
  return <Outlet />;
}

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route element={<RequireRole role="RISK_ANALYST" />}>
          <Route index element={<DashboardPage />} />
          <Route path="customers" element={<CustomersPage />} />
          <Route path="customers/:id" element={<CustomerDetailPage />} />
          <Route path="scenarios" element={<ScenariosPage />} />
          <Route path="runs/compare" element={<ComparePage />} />
          <Route path="runs/:id" element={<RunPage />} />
          <Route path="assistant" element={<AssistantPage />} />
          <Route path="live" element={<LivePage />} />
          <Route path="system" element={<SystemPage />} />
        </Route>
        <Route element={<RequireRole role="CUSTOMER" />}>
          <Route path="me" element={<MyOverviewPage />} />
          <Route path="me/coach" element={<CoachPage />} />
        </Route>
        <Route path="*" element={<EmptyState title="Sayfa bulunamadı" description="Soldaki menüden bir ekran seçin." />} />
      </Route>
    </Routes>
  );
}
