import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { configureApiAuth } from '../api/client';
import { server } from './server';

// Pages are lazy route chunks; the first render in a file waits for Vite to transform
// them (Recharts included), which can exceed the 1s default on a loaded CI runner.
configure({ asyncUtilTimeout: 10_000 });

/** jsdom has no layout engine, so nothing ever resizes; Recharts only needs the API. */
class ResizeObserverStub implements ResizeObserver {
  observe(): void {
    // No layout in jsdom: there is nothing to observe.
  }
  unobserve(): void {
    // See observe().
  }
  disconnect(): void {
    // See observe().
  }
}
globalThis.ResizeObserver = ResizeObserverStub;

/**
 * jsdom has HTMLDialogElement but not its methods. This models the parts the app uses:
 * open state, close() firing "close", and Escape firing a cancelable "cancel".
 */
if (!Reflect.has(HTMLDialogElement.prototype, 'showModal')) {
  Object.defineProperty(HTMLDialogElement.prototype, 'open', {
    get(this: HTMLDialogElement) {
      return this.hasAttribute('open');
    },
    configurable: true,
  });
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '');
    this.querySelector<HTMLElement>('input, button, [tabindex]')?.focus();
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return;
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const dialog = document.querySelector<HTMLDialogElement>('dialog[open]');
    if (dialog?.dispatchEvent(new Event('cancel', { cancelable: true }))) dialog.close();
  });
}

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  window.localStorage.clear();
  window.sessionStorage.clear();
  configureApiAuth(undefined);
});

afterAll(() => {
  server.close();
});
