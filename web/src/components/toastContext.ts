import { createContext, use } from 'react';

export interface ToastMessage {
  title: string;
  detail?: string | undefined;
}

export type ShowToast = (toast: ToastMessage) => void;

export const ToastContext = createContext<ShowToast | undefined>(undefined);

export function useToast(): ShowToast {
  const show = use(ToastContext);
  if (!show) throw new Error('useToast must be used inside <ToastProvider>');
  return show;
}
