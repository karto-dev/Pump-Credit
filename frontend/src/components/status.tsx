import type { AdjustmentStatus, ApprovalStatus, DisputeStatus, IndentStatus, PaymentStatus, Resolution } from '../api/types';
import { Badge } from './ui';

const INDENT: Record<IndentStatus, [string, 'ok' | 'warn' | 'bad' | 'neutral']> = {
  PENDING: ['Pending', 'warn'],
  ON_HOLD_CREDIT_LIMIT: ['On hold – credit limit', 'bad'],
  ACCEPTED: ['Accepted', 'ok'],
  COMPLETED: ['Supplied', 'neutral'],
  CANCELLED: ['Cancelled', 'neutral'],
  REJECTED: ['Rejected', 'bad'],
  EXPIRED: ['Expired', 'neutral'],
};

const APPROVAL: Record<ApprovalStatus, [string, 'ok' | 'warn' | 'bad' | 'neutral']> = {
  AWAITING_CUSTOMER_APPROVAL: ['Awaiting approval', 'warn'],
  CUSTOMER_APPROVED: ['Approved', 'ok'],
  CUSTOMER_REJECTED: ['Rejected', 'bad'],
  NO_RESPONSE: ['No response', 'neutral'],
  UNDER_DISPUTE: ['Under dispute', 'bad'],
  RESOLVED: ['Resolved', 'ok'],
};

export const IndentBadge = ({ status }: { status: IndentStatus }) => {
  const [label, tone] = INDENT[status];
  return <Badge tone={tone}>{label}</Badge>;
};

export const ApprovalBadge = ({ status }: { status: ApprovalStatus }) => {
  const [label, tone] = APPROVAL[status];
  return <Badge tone={tone}>{label}</Badge>;
};

export const APPROVAL_LABELS = Object.fromEntries(Object.entries(APPROVAL).map(([k, [l]]) => [k, l])) as Record<ApprovalStatus, string>;

const DISPUTE: Record<DisputeStatus, [string, 'ok' | 'warn' | 'bad' | 'neutral']> = {
  OPEN: ['Open', 'bad'],
  UNDER_INVESTIGATION: ['Under investigation', 'warn'],
  RESOLVED: ['Resolved', 'ok'],
};

const ADJUSTMENT: Record<AdjustmentStatus, [string, 'ok' | 'warn' | 'bad' | 'neutral']> = {
  PENDING_APPROVAL: ['Awaiting approval', 'warn'],
  APPROVED: ['Approved', 'ok'],
  REJECTED: ['Rejected', 'neutral'],
};

export const RESOLUTION_LABELS: Record<Resolution, string> = {
  BILL_UPHELD: 'Bill upheld',
  ADJUSTED: 'Amount adjusted',
  REVERSED: 'Bill reversed',
};

export const DisputeBadge = ({ status }: { status: DisputeStatus }) => {
  const [label, tone] = DISPUTE[status];
  return <Badge tone={tone}>{label}</Badge>;
};

export const AdjustmentBadge = ({ status }: { status: AdjustmentStatus }) => {
  const [label, tone] = ADJUSTMENT[status];
  return <Badge tone={tone}>{label}</Badge>;
};

const PAYMENT: Record<PaymentStatus, [string, 'ok' | 'warn' | 'bad' | 'neutral']> = {
  CUSTOMER_SUBMITTED: ['Submitted', 'warn'],
  AWAITING_CASHIER_ENTRY: ['Awaiting cashier', 'warn'],
  CASHIER_RECORDED: ['Recorded', 'warn'],
  AWAITING_VERIFICATION: ['Under verification', 'warn'],
  VERIFIED: ['Verified', 'ok'],
  REJECTED: ['Rejected', 'bad'],
  ADDITIONAL_PROOF_REQUIRED: ['More proof needed', 'bad'],
  DUPLICATE_PAYMENT: ['Duplicate', 'neutral'],
  CANCELLED: ['Withdrawn', 'neutral'],
};

export const PAYMENT_LABELS = Object.fromEntries(Object.entries(PAYMENT).map(([k, [l]]) => [k, l])) as Record<PaymentStatus, string>;

export const PaymentBadge = ({ status }: { status: PaymentStatus }) => {
  const [label, tone] = PAYMENT[status];
  return <Badge tone={tone}>{label}</Badge>;
};
