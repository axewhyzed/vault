import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import { ArrowLeftIcon, ArrowRightIcon, CheckIcon, CloseIcon } from '../Icons';

type TourStep = {
  targetSelector: string;
  title: string;
  content: string;
  placement: 'right' | 'bottom' | 'left' | 'top';
};

const TOUR_STEPS: TourStep[] = [
  {
    targetSelector: '[data-tour="compose-hub"]',
    title: 'Unified Compose Hub',
    content:
      'Quickly drop text notes, save links, or upload files up to 2 GB. Transfers are encrypted and sent instantly to your connected devices without page hopping.',
    placement: 'right',
  },
  {
    targetSelector: '[data-tour="retention-pill"]',
    title: 'Self-Destructing Retention',
    content:
      'Set how long each item stays active—from 1 hour to 7 days. Vault automatically purges expired items so your storage and privacy stay clean.',
    placement: 'bottom',
  },
  {
    targetSelector: '[data-tour="inbox-panel"]',
    title: 'High-Density Inbox',
    content:
      'Browse incoming transfers in a compact 52px timeline. Press / to search instantly, or filter between Files, Links, and Text notes.',
    placement: 'bottom',
  },
  {
    targetSelector: '[data-tour="item-actions"]',
    title: 'Instant One-Tap Actions',
    content:
      'Copy snippets or links to your clipboard with one click, download attachments immediately, or open details to inspect full SHA-256 cryptographic hashes.',
    placement: 'left',
  },
  {
    targetSelector: '[data-tour="pair-phone"]',
    title: 'Encrypted Phone Pairing',
    content:
      'Pair your Android phone by scanning a secure 60-second QR code. Everything syncs directly over your self-hosted Vault relay without cloud tracking.',
    placement: 'bottom',
  },
];

type TourGuideModalProps = {
  isOpen: boolean;
  onClose: () => void;
  onFinish?: () => void;
};

type Rect = {
  top: number;
  left: number;
  width: number;
  height: number;
  right: number;
  bottom: number;
};

export default function TourGuideModal({ isOpen, onClose, onFinish }: TourGuideModalProps) {
  const [currentStep, setCurrentStep] = useState(0);
  const [targetRect, setTargetRect] = useState<Rect | null>(null);

  const step = TOUR_STEPS[currentStep];

  const updateTargetRect = useCallback(() => {
    if (!step) return;
    const el = document.querySelector(step.targetSelector);
    if (el) {
      const rect = el.getBoundingClientRect();
      setTargetRect({
        top: rect.top,
        left: rect.left,
        width: rect.width,
        height: rect.height,
        right: rect.right,
        bottom: rect.bottom,
      });
    } else {
      setTargetRect(null);
    }
  }, [step]);

  useLayoutEffect(() => {
    if (!isOpen) return;
    updateTargetRect();
    const handleScrollOrResize = () => updateTargetRect();
    window.addEventListener('resize', handleScrollOrResize, { passive: true });
    window.addEventListener('scroll', handleScrollOrResize, { passive: true });
    return () => {
      window.removeEventListener('resize', handleScrollOrResize);
      window.removeEventListener('scroll', handleScrollOrResize);
    };
  }, [isOpen, currentStep, updateTargetRect]);

  // Keyboard navigation
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      } else if (e.key === 'ArrowRight' || e.key === 'Enter') {
        e.preventDefault();
        if (currentStep < TOUR_STEPS.length - 1) {
          setCurrentStep((prev) => prev + 1);
        } else if (onFinish) {
          onFinish();
        } else {
          onClose();
        }
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        if (currentStep > 0) {
          setCurrentStep((prev) => prev - 1);
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, currentStep, onClose, onFinish]);

  if (!isOpen || !step) return null;

  // Tooltip positioning computation
  const popoverWidth = 340;
  const popoverHeight = 220;
  const padding = 16;
  const viewportW = typeof window !== 'undefined' ? window.innerWidth : 1200;
  const viewportH = typeof window !== 'undefined' ? window.innerHeight : 800;

  let popoverStyle: React.CSSProperties = {
    position: 'fixed',
    zIndex: 10001,
  };

  if (targetRect) {
    let top = targetRect.top;
    let left = targetRect.left;

    // Default to preferred placement, adjusted for mobile or edge collision
    const isMobile = viewportW < 768;
    const placement = isMobile ? 'bottom' : step.placement;

    if (placement === 'right') {
      left = targetRect.right + padding;
      top = Math.max(padding, Math.min(viewportH - popoverHeight - padding, targetRect.top + targetRect.height / 2 - popoverHeight / 2));
      // Fallback if offscreen right
      if (left + popoverWidth > viewportW - padding) {
        left = Math.max(padding, targetRect.left - popoverWidth - padding);
      }
    } else if (placement === 'left') {
      left = targetRect.left - popoverWidth - padding;
      top = Math.max(padding, Math.min(viewportH - popoverHeight - padding, targetRect.top + targetRect.height / 2 - popoverHeight / 2));
      // Fallback if offscreen left
      if (left < padding) {
        left = Math.min(viewportW - popoverWidth - padding, targetRect.right + padding);
      }
    } else if (placement === 'bottom') {
      top = targetRect.bottom + padding;
      left = Math.max(padding, Math.min(viewportW - popoverWidth - padding, targetRect.left + targetRect.width / 2 - popoverWidth / 2));
      // Fallback if offscreen bottom
      if (top + popoverHeight > viewportH - padding) {
        top = Math.max(padding, targetRect.top - popoverHeight - padding);
      }
    } else {
      // top
      top = targetRect.top - popoverHeight - padding;
      left = Math.max(padding, Math.min(viewportW - popoverWidth - padding, targetRect.left + targetRect.width / 2 - popoverWidth / 2));
      if (top < padding) {
        top = targetRect.bottom + padding;
      }
    }

    // Ensure within viewport
    top = Math.max(padding, Math.min(viewportH - popoverHeight - padding, top));
    left = Math.max(padding, Math.min(viewportW - popoverWidth - padding, left));

    popoverStyle = {
      ...popoverStyle,
      top: `${top}px`,
      left: `${left}px`,
    };
  } else {
    // Center fallback if target element is not found
    popoverStyle = {
      ...popoverStyle,
      top: '50%',
      left: '50%',
      transform: 'translate(-50%, -50%)',
    };
  }

  const isLastStep = currentStep === TOUR_STEPS.length - 1;

  const handleNext = () => {
    if (isLastStep) {
      if (onFinish) {
        onFinish();
      } else {
        onClose();
      }
    } else {
      setCurrentStep((prev) => prev + 1);
    }
  };

  const handleBack = () => {
    if (currentStep > 0) {
      setCurrentStep((prev) => prev - 1);
    }
  };

  return (
    <div className="tour-overlay-portal" role="dialog" aria-modal="true" aria-labelledby="tour-step-title">
      {/* SVG Mask Spotlight Cutout */}
      <svg className="tour-spotlight-svg" aria-hidden="true">
        <defs>
          <mask id="vault-tour-mask">
            {/* White background reveals everything */}
            <rect x="0" y="0" width="100%" height="100%" fill="#ffffff" />
            {/* Black cutout makes target area transparent */}
            {targetRect && (
              <rect
                x={targetRect.left - 6}
                y={targetRect.top - 6}
                width={targetRect.width + 12}
                height={targetRect.height + 12}
                rx="8"
                fill="#000000"
              />
            )}
          </mask>
        </defs>
        {/* Semi-transparent dark overlay through the mask */}
        <rect
          x="0"
          y="0"
          width="100%"
          height="100%"
          fill="rgba(5, 8, 14, 0.72)"
          mask="url(#vault-tour-mask)"
          onClick={onClose}
        />
      </svg>

      {/* Glowing boundary box around the spotlighted element */}
      {targetRect && (
        <div
          className="tour-spotlight-box"
          style={{
            top: targetRect.top - 6,
            left: targetRect.left - 6,
            width: targetRect.width + 12,
            height: targetRect.height + 12,
          }}
          aria-hidden="true"
        />
      )}

      {/* Floating Tour Popover Card */}
      <div className="tour-popover-card" style={popoverStyle}>
        <div className="tour-popover-header">
          <div className="tour-step-pill">
            <span className="tour-step-num font-mono">
              STEP {currentStep + 1} OF {TOUR_STEPS.length}
            </span>
          </div>
          <button
            type="button"
            className="tour-close-btn"
            onClick={onClose}
            aria-label="Exit tour (Escape)"
            title="Exit tour (Esc)"
          >
            <CloseIcon size={14} />
          </button>
        </div>

        <div className="tour-popover-body">
          <h3 id="tour-step-title" className="tour-title">
            {step.title}
          </h3>
          <p className="tour-description">{step.content}</p>
        </div>

        <div className="tour-popover-footer">
          <div className="tour-dots" aria-hidden="true">
            {TOUR_STEPS.map((_, idx) => (
              <button
                key={idx}
                type="button"
                className={`tour-dot ${idx === currentStep ? 'is-active' : ''}`}
                onClick={() => setCurrentStep(idx)}
                aria-label={`Go to step ${idx + 1}`}
              />
            ))}
          </div>

          <div className="tour-actions">
            {currentStep > 0 && (
              <button
                type="button"
                className="secondary-button tour-btn-back"
                onClick={handleBack}
                aria-label="Previous step"
              >
                <ArrowLeftIcon size={14} />
                <span>Back</span>
              </button>
            )}

            <button
              type="button"
              className="primary-button tour-btn-next"
              onClick={handleNext}
              aria-label={isLastStep ? 'Explore Vault' : 'Next step'}
            >
              <span>{isLastStep ? 'Explore Vault' : 'Next'}</span>
              {isLastStep ? <CheckIcon size={14} /> : <ArrowRightIcon size={14} />}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
