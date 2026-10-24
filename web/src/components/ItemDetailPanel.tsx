import type { ItemDetail } from '../types';
import { formatBytes, formatDate, isSafeHttpUrl } from '../utils';
import {
  ArrowLeftIcon,
  CopyIcon,
  DownloadIcon,
  ExternalLinkIcon,
  ItemTypeIcon,
  TrashIcon,
} from '../Icons';

type ItemDetailPanelProps = {
  detail: ItemDetail | null;
  detailBusy: boolean;
  detailError: string;
  deleteConfirm: boolean;
  deleteBusy: boolean;
  copyMessage: string;
  onBack: () => void;
  onCopyText: (text: string | null) => void;
  onDownload: (downloadUrl: string, displayName?: string | null) => void;
  onSetDeleteConfirm: (confirm: boolean) => void;
  onDelete: () => void;
};

export default function ItemDetailPanel({
  detail,
  detailBusy,
  detailError,
  deleteConfirm,
  deleteBusy,
  copyMessage,
  onBack,
  onCopyText,
  onDownload,
  onSetDeleteConfirm,
  onDelete,
}: ItemDetailPanelProps) {
  return (
    <>
      <div className="detail-nav-header">
        <div className="detail-nav-left">
          <button
            type="button"
            className="nav-back-button"
            onClick={onBack}
            aria-label="Back to items list"
          >
            <ArrowLeftIcon size={14} />
            <span>Back to items</span>
          </button>
          <span className="breadcrumb-separator" aria-hidden="true">
            /
          </span>
          <span className="breadcrumb-current">
            {detail?.displayName?.trim() ||
              (detail
                ? detail.type === 'url'
                  ? 'Saved link'
                  : detail.type === 'file'
                    ? 'File attachment'
                    : 'Text note'
                : 'Item detail')}
          </span>
        </div>
        <div className="detail-nav-right">
          <span className="esc-key-hint hide-mobile" title="Press Escape to return to items">
            Esc
          </span>
        </div>
      </div>

      <div className="detail-toolbar-bar">
        <div className="detail-toolbar-left font-mono tabular-nums">
          {detail ? (
            <div className="detail-toolbar-meta">
              <span className={`detail-type-badge detail-type-badge--${detail.type}`}>
                <ItemTypeIcon type={detail.type} size={12} />
                <span>{detail.type.toUpperCase()}</span>
              </span>
              <span className="detail-meta-item">{formatBytes(detail.sizeBytes)}</span>
              <span className="meta-dot">·</span>
              <span className="detail-meta-item">{formatDate(detail.createdAt)}</span>
            </div>
          ) : (
            <span className="detail-toolbar-placeholder">{detailBusy ? 'Loading details…' : ''}</span>
          )}
        </div>
        <div className="detail-toolbar-right">
          {detail && !deleteConfirm && (
            <div className="detail-toolbar-actions">
              {detail.type === 'file' ? (
                <button
                  type="button"
                  className="detail-toolbar-btn"
                  onClick={() => onDownload(detail.downloadUrl!, detail.displayName)}
                  title="Download attachment"
                  aria-label="Download attachment"
                >
                  <DownloadIcon size={13} />
                  <span>Download</span>
                </button>
              ) : (
                <button
                  type="button"
                  className="detail-toolbar-btn"
                  onClick={() => onCopyText(detail.text)}
                  title="Copy text"
                  aria-label="Copy text"
                >
                  <CopyIcon size={13} />
                  <span>Copy</span>
                </button>
              )}
              <button
                type="button"
                className="detail-toolbar-btn detail-toolbar-btn--danger"
                onClick={() => onSetDeleteConfirm(true)}
                title="Delete item"
                aria-label="Delete item"
              >
                <TrashIcon size={13} />
                <span>Delete</span>
              </button>
            </div>
          )}
        </div>
      </div>

      <div className="detail-view">
        {detailBusy && (
          <div
            className="skeleton-container skeleton-container--detail"
            role="status"
            aria-label="Loading item details…"
          >
            <div className="skeleton-card skeleton-card--detail-header" />
            <div className="skeleton-card skeleton-card--detail-body" />
            <div className="skeleton-card skeleton-card--detail-meta" />
          </div>
        )}

        {detailError && (
          <p className="panel-error" role="alert">
            {detailError}
          </p>
        )}

        {detail && (
          <>
            <div className="detail-title">
              <span className={`item-type-badge item-type-badge--${detail.type}`} aria-hidden="true">
                <ItemTypeIcon type={detail.type} size={18} />
              </span>
              <div>
                <p className="card-kicker">
                  {detail.type === 'url' ? 'LINK' : detail.type === 'file' ? 'FILE ATTACHMENT' : 'TEXT'}
                </p>
                <h3>
                  {detail.displayName?.trim() ||
                    (detail.type === 'url'
                      ? 'Saved link'
                      : detail.type === 'file'
                        ? 'File attachment'
                        : 'Text item')}
                </h3>
              </div>
            </div>

            {detail.type === 'file' ? (
              <>
                <dl className="detail-meta file-detail-meta font-mono tabular-nums">
                  <div>
                    <dt>File type</dt>
                    <dd>{detail.contentType}</dd>
                  </div>
                  <div>
                    <dt>SHA-256</dt>
                    <dd>
                      <code>{detail.sha256}</code>
                    </dd>
                  </div>
                </dl>
                {detail.contentType &&
                ['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/avif'].includes(detail.contentType) &&
                detail.downloadUrl && (
                  <div className="detail-image-preview">
                    <img
                      src={detail.downloadUrl}
                      alt={detail.displayName || 'Attachment preview'}
                      loading="lazy"
                    />
                  </div>
                )}
              </>
            ) : detail.type === 'url' ? (
              <div className="detail-url-box">
                {isSafeHttpUrl(detail.text) ? (
                  <a
                    href={detail.text ?? undefined}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="detail-url-link"
                  >
                    <span className="detail-url-text">{detail.text}</span>
                    <ExternalLinkIcon size={14} className="detail-url-icon" />
                  </a>
                ) : (
                  <div className="detail-url-unsafe">
                    <span className="detail-url-text">{detail.text}</span>
                    <span className="detail-url-warning">Untrusted link scheme</span>
                  </div>
                )}
              </div>
            ) : (
              <pre className="detail-text" tabIndex={0} aria-label="Saved item text">
                {detail.text}
              </pre>
            )}

            <dl className="detail-meta font-mono tabular-nums">
              <div>
                <dt>Sent from</dt>
                <dd>
                  {detail.sourceDevice.name} ·{' '}
                  {detail.sourceDevice.platform === 'web' ? 'This browser' : detail.sourceDevice.platform}
                </dd>
              </div>
              <div>
                <dt>Added</dt>
                <dd>{formatDate(detail.createdAt)}</dd>
              </div>
              <div>
                <dt>Expires</dt>
                <dd>{formatDate(detail.expiresAt)}</dd>
              </div>
              <div>
                <dt>Size</dt>
                <dd>{formatBytes(detail.sizeBytes)}</dd>
              </div>
            </dl>

            {copyMessage && (
              <p className="copy-message" role="status" aria-live="polite">
                {copyMessage}
              </p>
            )}

            {!deleteConfirm ? (
              <div className="detail-actions">
                {detail.type === 'file' ? (
                  <button
                    className="primary-button"
                    type="button"
                    onClick={() => onDownload(detail.downloadUrl!, detail.displayName)}
                  >
                    <DownloadIcon size={16} />
                    <span>Download attachment</span>
                  </button>
                ) : (
                  <button
                    className="primary-button"
                    type="button"
                    onClick={() => onCopyText(detail.text)}
                  >
                    <CopyIcon size={16} />
                    <span>Copy text</span>
                  </button>
                )}
                <button
                  className="danger-button"
                  type="button"
                  onClick={() => onSetDeleteConfirm(true)}
                >
                  <TrashIcon size={15} />
                  <span>Delete</span>
                </button>
              </div>
            ) : (
              <div className="delete-confirm" role="group" aria-label="Confirm item deletion">
                <p>Permanently delete this item from your Vault?</p>
                <button
                  className="danger-button"
                  type="button"
                  disabled={deleteBusy}
                  onClick={onDelete}
                >
                  <TrashIcon size={15} />
                  <span>{deleteBusy ? 'Deleting…' : 'Delete item'}</span>
                </button>
                <button
                  className="secondary-button"
                  type="button"
                  disabled={deleteBusy}
                  onClick={() => onSetDeleteConfirm(false)}
                >
                  Cancel
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}
