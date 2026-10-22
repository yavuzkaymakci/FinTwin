import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});

// jsdom lacks layout APIs used by the chat (auto-scroll) and Radix/Recharts (resize observation).
Element.prototype.scrollIntoView = function scrollIntoView() {};
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
