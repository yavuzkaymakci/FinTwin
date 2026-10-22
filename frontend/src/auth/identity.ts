/**
 * The one place that knows who the user is. Until JWT authentication (step 8) the backend reads the identity from
 * the X-Demo-User / X-Demo-Role / X-Demo-Customer-Id headers; everything else in the UI asks this module for the
 * current identity and the request headers, so replacing it with a token is a change here only.
 */
import { useSyncExternalStore } from 'react';

export type Role = 'RISK_ANALYST' | 'CUSTOMER';

export interface Identity {
  role: Role;
  userId: string;
  /** The customer the user is (role CUSTOMER); null for analysts. */
  customerId: number | null;
}

const STORAGE_KEY = 'fintwin.identity';
export const DEFAULT_CUSTOMER_ID = 6;
const ANALYST: Identity = { role: 'RISK_ANALYST', userId: 'ayse.analist', customerId: null };

function load(): Identity {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Identity;
      if (parsed.role === 'RISK_ANALYST' || (parsed.role === 'CUSTOMER' && typeof parsed.customerId === 'number')) {
        return parsed;
      }
    }
  } catch {
    // storage unavailable or corrupt: fall back to the default
  }
  return ANALYST;
}

let current: Identity = load();
const listeners = new Set<() => void>();

export function getIdentity(): Identity {
  return current;
}

export function setIdentity(next: Identity): void {
  current = next;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // ignore: the identity still applies to this tab
  }
  listeners.forEach((l) => l());
}

export function asAnalyst(): Identity {
  return ANALYST;
}

export function asCustomer(customerId: number): Identity {
  return { role: 'CUSTOMER', userId: `musteri-${customerId}`, customerId };
}

/** Request headers carrying the identity (development scheme, see the module comment). */
export function identityHeaders(identity: Identity = current): Record<string, string> {
  const headers: Record<string, string> = { 'X-Demo-User': identity.userId, 'X-Demo-Role': identity.role };
  if (identity.customerId !== null) {
    headers['X-Demo-Customer-Id'] = String(identity.customerId);
  }
  return headers;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useIdentity(): Identity {
  return useSyncExternalStore(subscribe, getIdentity, getIdentity);
}
