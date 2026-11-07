import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import Layout from './components/Layout';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import ChangePassword from './pages/ChangePassword';
import Customers from './pages/customers/Customers';
import CustomerDetail from './pages/customers/CustomerDetail';
import Users from './pages/admin/Users';
import Permissions from './pages/admin/Permissions';
import FuelRates from './pages/admin/FuelRates';
import Indents from './pages/indents/Indents';
import NewIndent from './pages/indents/NewIndent';
import Sales from './pages/sales/Sales';
import NewSale from './pages/sales/NewSale';
import Disputes from './pages/disputes/Disputes';
import Adjustments from './pages/disputes/Adjustments';
import Payments from './pages/payments/Payments';
import SubmitPayment from './pages/payments/SubmitPayment';
import RecordPayment from './pages/payments/RecordPayment';
import Statement from './pages/Statement';
import Reports from './pages/reports/Reports';
import Admin, { ADMIN_PERMS } from './pages/admin/Admin';
import 'bootstrap/dist/css/bootstrap.min.css';
import 'bootstrap-icons/font/bootstrap-icons.min.css';
import './styles.css';

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="content muted"><span className="spinner-border spinner-border-sm me-2" />Loading…</div>;
  if (!user) return <Navigate to="/login" replace />;
  // A temporary password must be replaced before anything else.
  if (user.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

/** UI-level gate only; the API enforces the same permission. */
function RequirePerm({ perm, children }: { perm: string; children: ReactNode }) {
  const { can } = useAuth();
  return can(perm) ? <>{children}</> : <Navigate to="/" replace />;
}

const ADJUSTMENT_PERMS = ['ADJUSTMENT_APPROVE', 'ADJUSTMENT_CREATE', 'LEDGER_VIEW_ALL'];

function RequireAnyPerm({ perms, children }: { perms: string[]; children: ReactNode }) {
  const { can } = useAuth();
  return perms.some(can) ? <>{children}</> : <Navigate to="/" replace />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route element={<RequireAuth><Layout /></RequireAuth>}>
            <Route path="/" element={<Dashboard />} />
            <Route path="/change-password" element={<ChangePassword />} />
            <Route path="/customers" element={<Customers />} />
            <Route path="/customers/:id" element={<CustomerDetail />} />
            <Route path="/indents" element={<Indents />} />
            <Route path="/indents/new" element={<RequirePerm perm="INDENT_CREATE"><NewIndent /></RequirePerm>} />
            <Route path="/sales" element={<Sales />} />
            <Route path="/sales/new" element={<RequirePerm perm="CREDIT_TXN_CREATE"><NewSale /></RequirePerm>} />
            <Route path="/disputes" element={<Disputes />} />
            <Route path="/adjustments" element={<RequireAnyPerm perms={ADJUSTMENT_PERMS}><Adjustments /></RequireAnyPerm>} />
            <Route path="/payments" element={<Payments />} />
            <Route path="/payments/new" element={<RequirePerm perm="PAYMENT_SUBMIT"><SubmitPayment /></RequirePerm>} />
            <Route path="/payments/record" element={<RequirePerm perm="PAYMENT_RECORD"><RecordPayment /></RequirePerm>} />
            <Route path="/statement" element={<RequirePerm perm="LEDGER_VIEW_OWN"><Statement /></RequirePerm>} />
            <Route path="/customers/:id/statement" element={<RequirePerm perm="LEDGER_VIEW_ALL"><Statement /></RequirePerm>} />
            <Route path="/reports" element={<RequireAnyPerm perms={['REPORT_VIEW', 'REPORT_FINANCIAL']}><Reports /></RequireAnyPerm>} />
            <Route path="/admin" element={<RequireAnyPerm perms={ADMIN_PERMS}><Admin /></RequireAnyPerm>} />
            <Route path="/fuel-rates" element={<RequirePerm perm="FUEL_RATE_MANAGE"><FuelRates /></RequirePerm>} />
            <Route path="/users" element={<RequirePerm perm="USER_MANAGE"><Users /></RequirePerm>} />
            <Route path="/permissions" element={<RequirePerm perm="PERMISSION_MANAGE"><Permissions /></RequirePerm>} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
);
