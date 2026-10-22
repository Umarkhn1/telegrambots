import { AnimatePresence, motion } from 'framer-motion';
import { CircleAlert, CircleCheck } from 'lucide-react';
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { haptic } from '../lib/tg';

type Kind = 'success' | 'error';
interface ToastItem {
  id: number;
  text: string;
  kind: Kind;
}

const ToastContext = createContext<(text: string, kind?: Kind) => void>(() => {});
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(0);

  const show = useCallback((text: string, kind: Kind = 'success') => {
    const id = ++seq.current;
    haptic(kind === 'success' ? 'success' : 'error');
    setItems((list) => [...list.slice(-1), { id, text, kind }]);
    setTimeout(() => setItems((list) => list.filter((x) => x.id !== id)), 2800);
  }, []);

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toast-host">
        <AnimatePresence>
          {items.map((it) => (
            <motion.div
              key={it.id}
              className={'toast ' + it.kind}
              initial={{ opacity: 0, y: -16, scale: 0.96 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -10, scale: 0.97 }}
              transition={{ type: 'spring', stiffness: 420, damping: 32 }}
            >
              {it.kind === 'success' ? <CircleCheck size={18} /> : <CircleAlert size={18} />}
              <span>{it.text}</span>
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </ToastContext.Provider>
  );
}
