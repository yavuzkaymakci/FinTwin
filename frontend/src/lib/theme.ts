import { useCallback, useSyncExternalStore } from 'react';

export type Theme = 'light' | 'dark';
const KEY = 'fintwin.theme';
const listeners = new Set<() => void>();

function current(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light';
}

export function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark');
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    // ignore
  }
  listeners.forEach((l) => l());
}

/** Light / dark theme (class on <html>, persisted; initial value applied by index.html before paint). */
export function useTheme(): [Theme, () => void] {
  const theme = useSyncExternalStore((l) => {
    listeners.add(l);
    return () => listeners.delete(l);
  }, current, () => 'light' as Theme);
  const toggle = useCallback(() => applyTheme(current() === 'dark' ? 'light' : 'dark'), []);
  return [theme, toggle];
}
