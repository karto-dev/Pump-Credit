import type { Role } from './client';

export interface CustomerRow {
  id: number;
  customerCode: string;
  name: string;
  businessName: string | null;
  phone: string;
  city: string | null;
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
  creditLimit: number;
  confirmedOutstanding: number;
  activeExceptionLimit: number;
  availableCredit: number;
  unverifiedPaymentClaims: number;
  pendingApprovalCount: number;
  disputedCount: number;
  vehicleCount: number;
}

export interface CustomerProfile {
  name: string;
  businessName: string | null;
  contactPerson: string | null;
  phone: string;
  alternatePhone: string | null;
  email: string | null;
  address: string | null;
  city: string | null;
  state: string | null;
  pincode: string | null;
  gstin: string | null;
  pan: string | null;
  creditPeriodDays: number;
}

export interface CustomerDetail extends CustomerProfile {
  id: number;
  customerCode: string;
  creditLimit: number;
  openingBalance: number;
  openingBalanceDate: string | null;
  status: CustomerRow['status'];
  version: number;
  createdAt: string;
  confirmedOutstanding: number;
  activeExceptionLimit: number;
  availableCredit: number;
  unverifiedPaymentClaims: number;
  pendingApprovalCount: number;
  pendingApprovalAmount: number;
  disputedCount: number;
  disputedAmount: number;
}

export interface Vehicle {
  id: number;
  customerId: number;
  vehicleNumber: string;
  vehicleType: string | null;
  defaultFuel: 'PETROL' | 'DIESEL' | null;
  description: string | null;
  isActive: boolean;
  createdAt: string;
}

export interface LimitChange {
  id: number;
  oldLimit: number;
  newLimit: number;
  reason: string;
  changedByName: string;
  changedAt: string;
}

export interface CreditException {
  id: number;
  customerId: number;
  extraLimit: number;
  validFrom: string;
  validUntil: string;
  reason: string;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  authorisedByName: string;
  authorisedAt: string;
  revokedByName: string | null;
  revokedAt: string | null;
  revokeReason: string | null;
}

export interface UserRow {
  id: number;
  username: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  roleCode: Role;
  customerId: number | null;
  customerName: string | null;
  isActive: boolean;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  lockedUntil: string | null;
  createdAt: string;
}

export interface PermissionState {
  code: string;
  module: string;
  description: string;
  fromRole: boolean;
  override: boolean | null;
  effective: boolean;
}

export interface PermissionMatrix {
  roles: { code: Role; name: string; description: string }[];
  permissions: { code: string; module: string; description: string }[];
  grants: Record<Role, string[]>;
}

export type IndentStatus = 'PENDING' | 'ON_HOLD_CREDIT_LIMIT' | 'ACCEPTED' | 'COMPLETED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED';

export interface Indent {
  id: number;
  indentNo: string;
  customerId: number;
  customerName: string;
  customerCode: string;
  vehicleId: number;
  vehicleNumber: string;
  fuelType: 'PETROL' | 'DIESEL';
  requestedQuantity: number | null;
  requestedAmount: number | null;
  fullTank: boolean;
  instructions: string | null;
  status: IndentStatus;
  holdReason: string | null;
  creditExceptionId: number | null;
  createdByName: string;
  createdAt: string;
  acceptedByName: string | null;
  acceptedAt: string | null;
  completedAt: string | null;
  cancelReason: string | null;
  creditTransactionId: number | null;
  txnNo: string | null;
  suppliedQuantity: number | null;
  suppliedAmount: number | null;
}

export type ApprovalStatus = 'AWAITING_CUSTOMER_APPROVAL' | 'CUSTOMER_APPROVED' | 'CUSTOMER_REJECTED' | 'NO_RESPONSE'
  | 'UNDER_DISPUTE' | 'RESOLVED';

export interface Sale {
  id: number;
  txnNo: string;
  customerId: number;
  customerName: string;
  customerCode: string;
  vehicleId: number;
  vehicleNumber: string;
  fuelType: 'PETROL' | 'DIESEL';
  quantity: number;
  ratePerLitre: number;
  amount: number;
  suppliedAt: string;
  physicalBillNo: string | null;
  remarks: string | null;
  indentId: number | null;
  indentNo: string | null;
  cashierId: number;
  cashierName: string;
  approvalStatus: ApprovalStatus;
  approvalUpdatedAt: string | null;
  status: 'POSTED' | 'REVERSED';
  hasBill: boolean;
  disputeId: number | null;
  disputeNo: string | null;
  disputeStatus: DisputeStatus | null;
  createdAt: string;
}

export interface ApprovalStep {
  id: number;
  fromStatus: ApprovalStatus | null;
  toStatus: ApprovalStatus;
  reason: string | null;
  actedByName: string;
  actedAt: string;
}

export type DisputeStatus = 'OPEN' | 'UNDER_INVESTIGATION' | 'RESOLVED';
export type Resolution = 'BILL_UPHELD' | 'ADJUSTED' | 'REVERSED';

export interface Dispute {
  id: number;
  disputeNo: string;
  status: DisputeStatus;
  reason: string;
  raisedAt: string;
  raisedByName: string;
  assignedTo: number | null;
  assignedToName: string | null;
  resolutionType: Resolution | null;
  resolutionNotes: string | null;
  adjustmentId: number | null;
  adjustmentNo: string | null;
  adjustmentStatus: AdjustmentStatus | null;
  adjustmentAmount: number | null;
  resolvedByName: string | null;
  resolvedAt: string | null;
  saleId: number;
  txnNo: string;
  customerId: number;
  customerName: string;
  vehicleNumber: string;
  fuelType: 'PETROL' | 'DIESEL';
  quantity: number;
  ratePerLitre: number;
  amount: number;
  suppliedAt: string;
  cashierName: string;
  approvalStatus: ApprovalStatus;
  saleStatus: 'POSTED' | 'REVERSED';
}

export interface DisputeSummary {
  awaitingCount: number;
  awaitingAmount: number;
  noResponseCount: number;
  noResponseAmount: number;
  openDisputes: number;
  underInvestigation: number;
  disputedAmount: number;
  pendingAdjustments: number;
}

export type AdjustmentStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED';

export interface Adjustment {
  id: number;
  adjustmentNo: string;
  customerId: number;
  customerName: string;
  adjType: 'DEBIT' | 'CREDIT';
  amount: number;
  reason: string;
  saleId: number | null;
  txnNo: string | null;
  disputeId: number | null;
  disputeNo: string | null;
  status: AdjustmentStatus;
  createdBy: number;
  createdByName: string;
  createdAt: string;
  approvedByName: string | null;
  approvedAt: string | null;
  approvalRemarks: string | null;
}

export interface FuelRate {
  fuelType: 'PETROL' | 'DIESEL';
  ratePerLitre: number;
  effectiveFrom: string;
  setByName: string | null;
}

export interface Notification {
  id: number;
  eventType: string;
  title: string;
  message: string;
  entityType: string | null;
  entityId: number | null;
  isRead: boolean;
  createdAt: string;
}

export type PaymentMethod = 'UPI' | 'NEFT' | 'IMPS' | 'RTGS' | 'CASH' | 'CHEQUE';
export type PaymentStatus = 'CUSTOMER_SUBMITTED' | 'AWAITING_CASHIER_ENTRY' | 'CASHIER_RECORDED' | 'AWAITING_VERIFICATION'
  | 'VERIFIED' | 'REJECTED' | 'ADDITIONAL_PROOF_REQUIRED' | 'DUPLICATE_PAYMENT' | 'CANCELLED';

export interface Payment {
  id: number;
  paymentNo: string;
  customerId: number;
  customerName: string;
  customerCode: string;
  amount: number;
  paymentDate: string;
  method: PaymentMethod;
  referenceNo: string | null;
  status: PaymentStatus;
  duplicateFlag: boolean;
  duplicateOfNo: string | null;
  verifiedAmount: number | null;
  verifiedByName: string | null;
  verifiedAt: string | null;
  submissionId: number | null;
  submissionNo: string | null;
  claimedAmount: number | null;
  submittedByName: string | null;
  submittedAt: string | null;
  submissionRemarks: string | null;
  cashierEntryId: number | null;
  entryNo: string | null;
  receivedAmount: number | null;
  recordedBy: number | null;
  recordedByName: string | null;
  recordedAt: string | null;
  chequeBank: string | null;
  entryRemarks: string | null;
  proofCount: number;
  lastAction: string | null;
  lastRemarks: string | null;
  createdAt: string;
}

export interface PaymentHistory {
  id: number;
  action: string;
  fromStatus: PaymentStatus;
  toStatus: PaymentStatus;
  verifiedAmount: number | null;
  evidenceChecked: string | null;
  remarks: string;
  actedByName: string;
  actedAt: string;
}

export interface PaymentProof {
  id: number;
  originalFileName: string | null;
  contentType: string;
  sizeBytes: number;
  uploadedByName: string;
  uploadedAt: string;
}

export interface PaymentSummary {
  awaitingCashCount: number;
  awaitingCashAmount: number;
  awaitingVerificationCount: number;
  awaitingVerificationAmount: number;
  proofRequestedCount: number;
  verifiedToday: number;
  verifiedThisMonth: number;
}

export type ReportKind = 'TEXT' | 'MONEY' | 'QTY' | 'INT' | 'PERCENT' | 'DATE' | 'DATETIME';
export type ReportValue = string | number | null;

/** Same shape for every report and statement; the server also renders it to Excel / PDF. */
export interface Report {
  title: string;
  subtitle: string[];
  summary: { label: string; value: ReportValue; kind: ReportKind }[];
  columns: { key: string; header: string; kind: ReportKind }[];
  rows: ReportValue[][];
  totals: ReportValue[] | null;
}

export interface StaffDashboard {
  totalOutstanding: number;
  customersWithBalance: number;
  customersOverLimit: number;
  overdueAmount: number;
  overdueCustomers: number;
  salesToday: number;
  fillsToday: number;
  salesThisMonth: number;
  verifiedThisMonth: number;
  verifiedToday: number;
  pendingVerification: number;
  pendingVerificationAmount: number;
  cashToCollect: number;
  unverifiedClaims: number;
  heldIndents: number;
  activeUsers: number;
  activeExceptions: number;
}
