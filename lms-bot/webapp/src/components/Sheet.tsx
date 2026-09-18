import { AnimatePresence, motion, useDragControls, type PanInfo } from 'framer-motion';
import { X } from 'lucide-react';
import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useBackButton } from '../lib/tg';

interface Props {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  subtitle?: ReactNode;
  children: ReactNode;
}

/**
 * Нижняя шторка с «ползунком»: тянется вниз за шапку, закрывается свайпом,
 * тапом по фону или системной кнопкой «Назад» Telegram.
 */
export function Sheet({ open, onClose, title, subtitle, children }: Props) {
  const drag = useDragControls();
  useBackButton(open, onClose);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const onDragEnd = (_: unknown, info: PanInfo) => {
    if (info.offset.y > 110 || info.velocity.y > 600) onClose();
  };

  return createPortal(
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            className="overlay"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.22 }}
            onClick={onClose}
          />
          <motion.div
            className="sheet"
            role="dialog"
            aria-modal="true"
            initial={{ y: '100%' }}
            animate={{ y: 0 }}
            exit={{ y: '100%' }}
            transition={{ type: 'spring', stiffness: 380, damping: 38, mass: 0.9 }}
            drag="y"
            dragControls={drag}
            dragListener={false}
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0.04, bottom: 0.7 }}
            onDragEnd={onDragEnd}
          >
            <div className="sheet-grab" onPointerDown={(e) => drag.start(e)}>
              <div className="sheet-handle" />
              {(title || subtitle) && (
                <div className="sheet-title-row">
                  <div style={{ minWidth: 0 }}>
                    {title && <div className="sheet-title">{title}</div>}
                    {subtitle && <div className="row-sub" style={{ marginTop: 4 }}>{subtitle}</div>}
                  </div>
                  <button className="sheet-close" onClick={onClose} onPointerDown={(e) => e.stopPropagation()} aria-label="close">
                    <X size={17} strokeWidth={2.4} />
                  </button>
                </div>
              )}
            </div>
            <div className="sheet-body">{children}</div>
          </motion.div>
        </>
      )}
    </AnimatePresence>,
    document.body,
  );
}
