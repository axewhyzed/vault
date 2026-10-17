import type { ItemDetail } from '../types';
import { formatBytes, formatDate } from '../utils';
import {
  ArrowLeftIcon,
  CopyIcon,
  DownloadIcon,
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
        <button
          type="button"
          className="nav-back-button"
          onClick={onBack}
          aria-label="Back to items list"
        >
          <ArrowLeftIcon size={15} />
          <span>Back to items</span>
        </button>
        <span className="breadcrumb-separator" aria-hidden="true">
          /
        </span>
        <span className="breadcrumb-current">Item detail</span>
        <span className="esc-key-hint hide-mobile">Esc</span>
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
              <dl className="detail-meta file-detail-meta">
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
            ) : (
              <pre className="detail-text" tabIndex={0} aria-label="Saved item text">
                {detail.text}
              </pre>
            )}

            <dl className="detail-meta">
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
